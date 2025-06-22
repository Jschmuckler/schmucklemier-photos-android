package com.example.schmucklemierphotos.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.app.DownloadManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.schmucklemierphotos.MainActivity
import com.example.schmucklemierphotos.R
import com.example.schmucklemierphotos.cache.FileCache
import com.example.schmucklemierphotos.model.GalleryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A foreground service for downloading files in the background
 * Handles notification updates and allows downloads to continue even when the app is backgrounded
 */
class DownloadService : Service() {
    
    companion object {
        private const val TAG = "DownloadService"
        private const val NOTIFICATION_CHANNEL_ID = "download_channel"
        private const val SERVICE_CHANNEL_ID = "download_channel_service"
        private const val NOTIFICATION_GROUP = "downloads_group"
        private const val SUMMARY_NOTIFICATION_ID = 1000
        private const val FOREGROUND_SERVICE_ID = 2000 // Use a different ID space than regular notifications
        
        // Flag constant for Android N+ to keep notifications when stopping foreground
        private const val STOP_FOREGROUND_DETACH = 2
        
        // Intent actions
        const val ACTION_START_DOWNLOAD = "com.example.schmucklemierphotos.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.schmucklemierphotos.CANCEL_DOWNLOAD"
        
        // Intent extras
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_FILE_URL = "file_url"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_BUCKET_NAME = "bucket_name"
    }
    
    // Service binding
    private val binder = DownloadBinder()
    
    // Download tracking
    private val activeDownloads = ConcurrentHashMap<String, DownloadTask>()
    private val notificationIdCounter = AtomicInteger(SUMMARY_NOTIFICATION_ID + 1)
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var serviceJob: Job? = null
    
    // Notification manager
    private lateinit var notificationManager: NotificationManager
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Download service created")
        
        // Initialize notification manager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create notification channel for Android O and above
        createNotificationChannel()
        
        // Create a summary notification for grouped notifications
        updateSummaryNotification()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: UUID.randomUUID().toString()
                val url = intent.getStringExtra(EXTRA_FILE_URL)
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME)
                val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
                val bucketName = intent.getStringExtra(EXTRA_BUCKET_NAME)
                
                if (url != null && filePath != null && fileName != null) {
                    startDownload(downloadId, url, filePath, fileName, mimeType, bucketName)
                }
            }
            
            ACTION_CANCEL_DOWNLOAD -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                if (downloadId != null) {
                    cancelDownload(downloadId)
                }
            }
        }
        
        // Make sure we're running as a foreground service only if we have active downloads
        val activeCount = activeDownloads.count { it.value.status == DownloadStatus.DOWNLOADING || it.value.status == DownloadStatus.PENDING }
        val completedCount = activeDownloads.count { it.value.status == DownloadStatus.COMPLETED }
        
        if (activeCount > 0) {
            // If we have active downloads, ensure we're running as foreground service
            ensureForegroundService()
        } else if (activeDownloads.isEmpty()) {
            // If no downloads at all, stop the service
            stopSelf()
        } else {
            // We have completed/failed downloads but no active ones
            // Still run the service, but not in foreground mode
            ensureForegroundService()
            
            // Keep the service running for a while to ensure notifications remain visible
            // after downloads complete. We'll clean up old completed downloads periodically.
            cleanupOldCompletedDownloads()
        }
        
        // If service is killed, restart with pending intents
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Download service destroyed")
        
        // Cancel all active downloads
        for (downloadId in activeDownloads.keys) {
            cancelDownload(downloadId)
        }
        
        // Cancel the service coroutine scope
        serviceJob?.cancel()
        
        super.onDestroy()
    }
    
    /**
     * Start a new download
     */
    private fun startDownload(
        downloadId: String,
        url: String,
        filePath: String,
        fileName: String,
        mimeType: String?,
        bucketName: String?
    ) {
        // Create notification ID for this download
        val notificationId = notificationIdCounter.getAndIncrement()
        
        // Create a download task
        val downloadTask = DownloadTask(
            id = downloadId,
            url = url,
            filePath = filePath,
            fileName = fileName,
            mimeType = mimeType ?: getMimeTypeFromFileName(fileName),
            notificationId = notificationId,
            status = DownloadStatus.PENDING
        )
        
        // Add to active downloads
        activeDownloads[downloadId] = downloadTask
        
        // Create initial notification
        updateNotificationForDownload(downloadTask, 0, -1)
        
        // Update summary notification
        updateSummaryNotification()
        
        // Make sure we're running as a foreground service
        ensureForegroundService()
        
        // Start the download in a coroutine
        serviceJob = serviceScope.launch {
            performDownload(downloadTask)
        }
    }
    
    /**
     * Cancel an active download
     */
    private fun cancelDownload(downloadId: String) {
        val downloadTask = activeDownloads[downloadId] ?: return
        
        // Update status to canceled
        downloadTask.status = DownloadStatus.CANCELED
        
        // Remove from active downloads
        activeDownloads.remove(downloadId)
        
        // Cancel the notification
        notificationManager.cancel(downloadTask.notificationId)
        
        // Update summary notification
        updateSummaryNotification()
        
        // If no more active downloads, update the foreground service status
        ensureForegroundService()
    }
    
    /**
     * Perform the actual download in the background
     */
    private suspend fun performDownload(downloadTask: DownloadTask) {
        var downloadUri: Uri? = null
        
        try {
            Log.d(TAG, "Starting download: ${downloadTask.fileName}")
            downloadTask.status = DownloadStatus.DOWNLOADING
            
            // Check if file is already in cache
            val fileCache = FileCache(this)
            val cachedFile = fileCache.getFile(downloadTask.filePath)
            
            if (cachedFile != null && cachedFile.exists()) {
                // Get file from cache
                downloadTask.size = cachedFile.length()
                
                // Save file to downloads with appropriate approach based on Android version
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Use MediaStore for Android 10+
                    downloadUri = saveToMediaStoreFromCache(cachedFile, downloadTask)
                } else {
                    // Use direct file system access for Android 9 and below
                    val downloadFile = saveToDownloadsDirectoryFromCache(cachedFile, downloadTask)
                    if (downloadFile != null) {
                        // Make file visible in Downloads app
                        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                        val contentUri = Uri.fromFile(downloadFile)
                        mediaScanIntent.data = contentUri
                        sendBroadcast(mediaScanIntent)
                    }
                }
                
                // Update notification to completed
                downloadTask.status = DownloadStatus.COMPLETED
                updateNotificationForDownload(downloadTask, 100, downloadTask.size)
            } else {
                // Download from URL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Use MediaStore for Android 10+
                    downloadUri = downloadToMediaStore(downloadTask)
                } else {
                    // Use direct file system access for Android 9 and below
                    val downloadFile = downloadToDownloadsDirectory(downloadTask)
                    if (downloadFile != null) {
                        // Make file visible in Downloads app
                        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                        val contentUri = Uri.fromFile(downloadFile)
                        mediaScanIntent.data = contentUri
                        sendBroadcast(mediaScanIntent)
                    }
                }
            }
            
            // Update summary notification
            updateSummaryNotification()
            
            // Set completion time for auto-cleanup
            val completedTask = activeDownloads[downloadTask.id]
            if (completedTask != null && completedTask.status == DownloadStatus.COMPLETED) {
                completedTask.completionTime = System.currentTimeMillis()
            }
            
            // If no more active downloads, update the foreground service status
            // This will ensure the "Downloading Files" notification is removed
            val hasActiveDownloads = activeDownloads.any { 
                it.value.status == DownloadStatus.DOWNLOADING || it.value.status == DownloadStatus.PENDING 
            }
            
            if (!hasActiveDownloads) {
                Log.d(TAG, "All downloads completed or canceled")
                
                // Cancel foreground service notification
                notificationManager.cancel(FOREGROUND_SERVICE_ID)
                
                // Update the service state
                ensureForegroundService()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            
            // Clean up MediaStore entry if it exists
            if (downloadUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    contentResolver.delete(downloadUri, null, null)
                } catch (e2: Exception) {
                    Log.e(TAG, "Error cleaning up failed download: ${e2.message}")
                }
            }
            
            // Update notification to failed
            downloadTask.status = DownloadStatus.FAILED
            downloadTask.error = e.message ?: "Unknown error"
            updateNotificationForDownload(downloadTask, 0, -1)
            
            // Remove from active downloads
            activeDownloads.remove(downloadTask.id)
            
            // Update summary notification
            updateSummaryNotification()
        }
    }
    
    /**
     * Download file to MediaStore (Android 10+)
     */
    private suspend fun downloadToMediaStore(downloadTask: DownloadTask): Uri? = withContext(Dispatchers.IO) {
        var downloadUri: Uri? = null
        
        try {
            // Create a MediaStore entry
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, downloadTask.fileName)
                put(MediaStore.Downloads.MIME_TYPE, downloadTask.mimeType)
                
                // Set the date to current time to ensure it appears as a new download
                val currentTime = System.currentTimeMillis()
                put(MediaStore.Downloads.DATE_ADDED, currentTime / 1000)
                put(MediaStore.Downloads.DATE_MODIFIED, currentTime / 1000)
                
                // Mark as pending
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            
            // Insert into MediaStore
            downloadUri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw IOException("Failed to create MediaStore entry")
            
            // Open URL connection
            val connection = URL(downloadTask.url).openConnection() as HttpURLConnection
            connection.connect()
            
            // Get file size
            val fileLength = connection.contentLength
            downloadTask.size = fileLength.toLong()
            
            // Get output stream from MediaStore
            val outputStream = contentResolver.openOutputStream(downloadUri)
                ?: throw IOException("Failed to open output stream")
            
            // Get input stream from URL
            val inputStream = connection.inputStream
            
            // Buffer for reading data
            val buffer = ByteArray(4 * 1024)
            var bytesRead: Int
            var downloadedBytes: Long = 0
            var lastProgressUpdateTime = System.currentTimeMillis()
            
            // Read data and write to file
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                // If download was canceled, stop
                if (downloadTask.status == DownloadStatus.CANCELED) {
                    throw IOException("Download canceled")
                }
                
                // Write to output stream
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                
                // Calculate progress
                val progress = if (fileLength > 0) {
                    (downloadedBytes * 100 / fileLength).toInt()
                } else {
                    -1
                }
                
                // Update notification (but not too frequently)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProgressUpdateTime > 500 || progress == 100) {
                    lastProgressUpdateTime = currentTime
                    withContext(Dispatchers.Main) {
                        updateNotificationForDownload(downloadTask, progress, downloadedBytes)
                    }
                }
            }
            
            // Close streams
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            // Mark as not pending to make it visible
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(downloadUri, contentValues, null, null)
            
            // First, mark the task as completed and update our internal state
            downloadTask.status = DownloadStatus.COMPLETED
            downloadTask.completionTime = System.currentTimeMillis()
            activeDownloads[downloadTask.id] = downloadTask
            
            // Then update notifications on the main thread
            withContext(Dispatchers.Main) {
                // Check if this was the last active download
                val hasActiveDownloads = activeDownloads.any { 
                    it.value.status == DownloadStatus.DOWNLOADING || it.value.status == DownloadStatus.PENDING 
                }
                
                // If this was the last active download, cancel foreground notification first
                if (!hasActiveDownloads) {
                    Log.d(TAG, "Last download completed, canceling foreground notification first")
                    // First immediately cancel the foreground notification
                    notificationManager.cancel(FOREGROUND_SERVICE_ID)
                    
                    // Then stop foreground service state
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH) // Keep download notifications visible
                    } else {
                        stopForeground(false) // Keep download notifications visible
                    }
                }
                
                // Now show the completion notification
                val completionBuilder = createCompletionNotification(downloadTask, downloadUri)
                notificationManager.notify(downloadTask.notificationId, completionBuilder.build())
                
                // Update summary notification last
                updateSummaryNotification()
                
                // Make sure foreground service state is correct
                ensureForegroundService()
            }
            
            return@withContext downloadUri
            
        } catch (e: Exception) {
            // Clean up MediaStore entry if it exists
            if (downloadUri != null) {
                try {
                    contentResolver.delete(downloadUri, null, null)
                } catch (e2: Exception) {
                    Log.e(TAG, "Error cleaning up failed download: ${e2.message}")
                }
            }
            
            throw e
        }
    }
    
    /**
     * Download file to downloads directory (Android 9 and below)
     */
    private suspend fun downloadToDownloadsDirectory(downloadTask: DownloadTask): File? = withContext(Dispatchers.IO) {
        try {
            // Get downloads directory
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            // Create output file
            val outputFile = File(downloadsDir, downloadTask.fileName)
            
            // Open URL connection
            val connection = URL(downloadTask.url).openConnection() as HttpURLConnection
            connection.connect()
            
            // Get file size
            val fileLength = connection.contentLength
            downloadTask.size = fileLength.toLong()
            
            // Create output stream
            val outputStream = FileOutputStream(outputFile)
            
            // Get input stream from URL
            val inputStream = connection.inputStream
            
            // Buffer for reading data
            val buffer = ByteArray(4 * 1024)
            var bytesRead: Int
            var downloadedBytes: Long = 0
            var lastProgressUpdateTime = System.currentTimeMillis()
            
            // Read data and write to file
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                // If download was canceled, stop
                if (downloadTask.status == DownloadStatus.CANCELED) {
                    outputStream.close()
                    inputStream.close()
                    outputFile.delete()
                    throw IOException("Download canceled")
                }
                
                // Write to output stream
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                
                // Calculate progress
                val progress = if (fileLength > 0) {
                    (downloadedBytes * 100 / fileLength).toInt()
                } else {
                    -1
                }
                
                // Update notification (but not too frequently)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProgressUpdateTime > 500 || progress == 100) {
                    lastProgressUpdateTime = currentTime
                    withContext(Dispatchers.Main) {
                        updateNotificationForDownload(downloadTask, progress, downloadedBytes)
                    }
                }
            }
            
            // Close streams
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            // Update file timestamp to ensure it appears as a new download
            val currentTime = System.currentTimeMillis()
            outputFile.setLastModified(currentTime)
            
            // First, mark the task as completed and update our internal state
            downloadTask.status = DownloadStatus.COMPLETED
            downloadTask.completionTime = currentTime
            activeDownloads[downloadTask.id] = downloadTask
            
            // Then update notifications on the main thread
            withContext(Dispatchers.Main) {
                // Check if this was the last active download
                val hasActiveDownloads = activeDownloads.any { 
                    it.value.status == DownloadStatus.DOWNLOADING || it.value.status == DownloadStatus.PENDING 
                }
                
                // If this was the last active download, cancel foreground notification first
                if (!hasActiveDownloads) {
                    Log.d(TAG, "Last download completed, canceling foreground notification first")
                    // First immediately cancel the foreground notification
                    notificationManager.cancel(FOREGROUND_SERVICE_ID)
                    
                    // Then stop foreground service state
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH) // Keep download notifications visible
                    } else {
                        stopForeground(false) // Keep download notifications visible
                    }
                }
                
                // Create a file URI for the download
                val fileUri = Uri.fromFile(outputFile)
                
                // Now show the completion notification
                val completionBuilder = createCompletionNotification(downloadTask, fileUri)
                notificationManager.notify(downloadTask.notificationId, completionBuilder.build())
                
                // Update summary notification last
                updateSummaryNotification()
                
                // Make sure foreground service state is correct
                ensureForegroundService()
            }
            
            return@withContext outputFile
            
        } catch (e: Exception) {
            throw e
        }
    }
    
    /**
     * Save a cached file to MediaStore (Android 10+)
     */
    private suspend fun saveToMediaStoreFromCache(cachedFile: File, downloadTask: DownloadTask): Uri = withContext(Dispatchers.IO) {
        // Create a MediaStore entry
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, downloadTask.fileName)
            put(MediaStore.Downloads.MIME_TYPE, downloadTask.mimeType)
            
            // Set the date to current time to ensure it appears as a new download
            val currentTime = System.currentTimeMillis()
            put(MediaStore.Downloads.DATE_ADDED, currentTime / 1000)
            put(MediaStore.Downloads.DATE_MODIFIED, currentTime / 1000)
            
            // Mark as pending
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        
        // Insert into MediaStore
        val downloadUri = contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: throw IOException("Failed to create MediaStore entry")
        
        try {
            // Get output stream from MediaStore
            val outputStream = contentResolver.openOutputStream(downloadUri)
                ?: throw IOException("Failed to open output stream")
            
            // Copy from cache
            cachedFile.inputStream().use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                    
                    // Update notification for progress
                    withContext(Dispatchers.Main) {
                        updateNotificationForDownload(downloadTask, 100, cachedFile.length())
                    }
                }
            }
            
            // Mark as not pending to make it visible
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(downloadUri, contentValues, null, null)
            
            // First, mark the task as completed and update our internal state
            downloadTask.status = DownloadStatus.COMPLETED
            downloadTask.completionTime = System.currentTimeMillis()
            activeDownloads[downloadTask.id] = downloadTask
            
            // Then update notifications on the main thread
            withContext(Dispatchers.Main) {
                // Check if this was the last active download
                val hasActiveDownloads = activeDownloads.any { 
                    it.value.status == DownloadStatus.DOWNLOADING || it.value.status == DownloadStatus.PENDING 
                }
                
                // If this was the last active download, cancel foreground notification first
                if (!hasActiveDownloads) {
                    Log.d(TAG, "Last download completed, canceling foreground notification first")
                    // First immediately cancel the foreground notification
                    notificationManager.cancel(FOREGROUND_SERVICE_ID)
                    
                    // Then stop foreground service state
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH) // Keep download notifications visible
                    } else {
                        stopForeground(false) // Keep download notifications visible
                    }
                }
                
                // Now show the completion notification
                val completionBuilder = createCompletionNotification(downloadTask, downloadUri)
                notificationManager.notify(downloadTask.notificationId, completionBuilder.build())
                
                // Update summary notification last
                updateSummaryNotification()
                
                // Make sure foreground service state is correct
                ensureForegroundService()
            }
            
            return@withContext downloadUri
        } catch (e: Exception) {
            // Clean up MediaStore entry if it exists
            try {
                contentResolver.delete(downloadUri, null, null)
            } catch (e2: Exception) {
                Log.e(TAG, "Error cleaning up failed download: ${e2.message}")
            }
            
            throw e
        }
    }
    
    /**
     * Save a cached file to downloads directory (Android 9 and below)
     */
    private suspend fun saveToDownloadsDirectoryFromCache(cachedFile: File, downloadTask: DownloadTask): File? = withContext(Dispatchers.IO) {
        try {
            // Get downloads directory
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            // Create output file
            val outputFile = File(downloadsDir, downloadTask.fileName)
            
            // Copy from cache
            cachedFile.copyTo(outputFile, overwrite = true)
            
            // Update file timestamp to ensure it appears as a new download
            val currentTime = System.currentTimeMillis()
            outputFile.setLastModified(currentTime)
            
            // First, mark the task as completed and update our internal state
            downloadTask.status = DownloadStatus.COMPLETED
            downloadTask.completionTime = currentTime
            activeDownloads[downloadTask.id] = downloadTask
            
            // Then update notifications on the main thread
            withContext(Dispatchers.Main) {
                // Check if this was the last active download
                val hasActiveDownloads = activeDownloads.any { 
                    it.value.status == DownloadStatus.DOWNLOADING || it.value.status == DownloadStatus.PENDING 
                }
                
                // If this was the last active download, cancel foreground notification first
                if (!hasActiveDownloads) {
                    Log.d(TAG, "Last download completed, canceling foreground notification first")
                    // First immediately cancel the foreground notification
                    notificationManager.cancel(FOREGROUND_SERVICE_ID)
                    
                    // Then stop foreground service state
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH) // Keep download notifications visible
                    } else {
                        stopForeground(false) // Keep download notifications visible
                    }
                }
                
                // Create a file URI for the download
                val fileUri = Uri.fromFile(outputFile)
                
                // Now show the completion notification
                val completionBuilder = createCompletionNotification(downloadTask, fileUri)
                notificationManager.notify(downloadTask.notificationId, completionBuilder.build())
                
                // Update summary notification last
                updateSummaryNotification()
                
                // Make sure foreground service state is correct
                ensureForegroundService()
            }
            
            return@withContext outputFile
        } catch (e: Exception) {
            throw e
        }
    }
    
    /**
     * Create the notification channel for Android O and above
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create a separate channel for download notifications
            val downloadChannelId = NOTIFICATION_CHANNEL_ID
            val downloadChannelName = "Downloads"
            val downloadChannelDesc = "File download notifications"
            val downloadImportance = NotificationManager.IMPORTANCE_DEFAULT
            
            val downloadChannel = NotificationChannel(downloadChannelId, downloadChannelName, downloadImportance).apply {
                description = downloadChannelDesc
                // Show badge for downloads
                setShowBadge(true)
                // Make sound for notifications
                enableVibration(true)
                enableLights(true)
            }
            
            // Create a separate channel for the foreground service
            val serviceChannelId = SERVICE_CHANNEL_ID
            val serviceChannelName = "Download Service"
            val serviceChannelDesc = "Background download service notifications"
            val serviceImportance = NotificationManager.IMPORTANCE_LOW
            
            val serviceChannel = NotificationChannel(serviceChannelId, serviceChannelName, serviceImportance).apply {
                description = serviceChannelDesc
                // Don't show badge for service
                setShowBadge(false)
                // Don't need sounds for service
                enableVibration(false)
                enableLights(false)
            }
            
            // Create both channels
            notificationManager.createNotificationChannel(downloadChannel)
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }
    
    /**
     * Create and show a completion notification for a download
     * This notification persists after the service is stopped and can be clicked to open the file
     */
    private fun createCompletionNotification(downloadTask: DownloadTask, downloadUri: Uri?): NotificationCompat.Builder {
        // Create intent to open file - initialize with a default value
        val openFileIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        val finalIntent: Intent
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10+, use MediaStore content URI if provided
            if (downloadUri != null) {
                finalIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(downloadUri, downloadTask.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                // Query for the downloaded file by name
                val projection = arrayOf(
                    MediaStore.Downloads._ID
                )
                val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(downloadTask.fileName)
                
                var mediaUri: Uri? = null
                
                contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        mediaUri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, 
                            id.toString()
                        )
                    }
                }
                
                finalIntent = if (mediaUri != null) {
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(mediaUri, downloadTask.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    openFileIntent
                }
            }
        } else {
            // For older Android versions, try to open the file directly
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, downloadTask.fileName)
            
            finalIntent = if (file.exists()) {
                try {
                    val fileUri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        file
                    )
                    
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, downloadTask.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating FileProvider URI: ${e.message}")
                    openFileIntent
                }
            } else {
                openFileIntent
            }
        }
        
        // Create a pending intent that will safely resolve to an activity that can handle it
        val canBeHandled = finalIntent.resolveActivity(packageManager) != null
        val pendingIntent = PendingIntent.getActivity(
            this,
            downloadTask.notificationId,
            if (canBeHandled) finalIntent else openFileIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Get file size for notification content
        val fileSize = if (downloadTask.size > 0) {
            formatFileSize(downloadTask.size)
        } else {
            ""
        }
        
        // Build the notification
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(downloadTask.fileName)
            .setContentText("Download complete • $fileSize")
            .setGroup(NOTIFICATION_GROUP)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)  // Dismiss when clicked
            .setOnlyAlertOnce(false)  // Make sound/vibration for completion
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Add timestamp for when the download completed
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
    }
    
    /**
     * Update the notification for a specific download
     */
    private fun updateNotificationForDownload(
        downloadTask: DownloadTask,
        progress: Int,
        bytesDownloaded: Long
    ) {
        // Build the notification
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(downloadTask.fileName)
            .setGroup(NOTIFICATION_GROUP)
            .setOnlyAlertOnce(true)
            .setOngoing(downloadTask.status == DownloadStatus.DOWNLOADING)
            // Make sure notifications are visible in foreground
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Prevent Android from treating it as a foreground service notification
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        
        // Build notification content based on status
        when (downloadTask.status) {
            DownloadStatus.PENDING -> {
                builder.setContentText("Preparing download...")
                    .setProgress(0, 0, true)
            }
            
            DownloadStatus.DOWNLOADING -> {
                if (progress >= 0 && bytesDownloaded >= 0) {
                    val contentText = if (downloadTask.size > 0) {
                        val downloadedMB = bytesDownloaded / (1024 * 1024).toFloat()
                        val totalMB = downloadTask.size / (1024 * 1024).toFloat()
                        String.format("%.1f MB / %.1f MB", downloadedMB, totalMB)
                    } else {
                        formatFileSize(bytesDownloaded)
                    }
                    
                    builder.setContentText(contentText)
                        .setProgress(100, progress, progress < 0)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                } else {
                    builder.setContentText("Downloading...")
                        .setProgress(0, 0, true)
                }
                
                // Add cancel action
                val cancelIntent = Intent(this, DownloadService::class.java).apply {
                    action = ACTION_CANCEL_DOWNLOAD
                    putExtra(EXTRA_DOWNLOAD_ID, downloadTask.id)
                }
                val cancelPendingIntent = PendingIntent.getService(
                    this,
                    downloadTask.notificationId,
                    cancelIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
                
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    cancelPendingIntent
                )
                
                // Show the notification
                notificationManager.notify(downloadTask.notificationId, builder.build())
            }
            
            DownloadStatus.COMPLETED -> {
                // Create a completion notification using the helper method
                val completionBuilder = createCompletionNotification(downloadTask, null)
                notificationManager.notify(downloadTask.notificationId, completionBuilder.build())
            }
            
            DownloadStatus.FAILED -> {
                builder.setContentText("Download failed: ${downloadTask.error}")
                    .setProgress(0, 0, false)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setAutoCancel(true)
                
                // Show the notification
                notificationManager.notify(downloadTask.notificationId, builder.build())
            }
            
            DownloadStatus.CANCELED -> {
                builder.setContentText("Download canceled")
                    .setProgress(0, 0, false)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setAutoCancel(true)
                
                // Show the notification
                notificationManager.notify(downloadTask.notificationId, builder.build())
            }
        }
        
        // Update summary
        updateSummaryNotification()
    }
    
    /**
     * Update or create summary notification for grouped downloads
     */
    private fun updateSummaryNotification() {
        // Count downloads by status
        val completedCount = activeDownloads.count { it.value.status == DownloadStatus.COMPLETED }
        val activeCount = activeDownloads.count { it.value.status == DownloadStatus.DOWNLOADING || it.value.status == DownloadStatus.PENDING }
        val failedCount = activeDownloads.count { it.value.status == DownloadStatus.FAILED || it.value.status == DownloadStatus.CANCELED }
        
        // Only create summary if there are any downloads to show
        if (activeDownloads.isEmpty() || (activeCount == 0 && completedCount == 0 && failedCount == 0)) {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
            notificationManager.cancel(FOREGROUND_SERVICE_ID)
            return
        }
        
        // Always cancel the foreground service notification if there are no active downloads
        if (activeCount == 0) {
            notificationManager.cancel(FOREGROUND_SERVICE_ID)
        }
        
        // If only one completed download and no active downloads, don't show summary
        // This lets the individual download notification stand out
        if (activeCount == 0 && completedCount == 1 && failedCount == 0) {
            // Just make sure we don't have an existing summary
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
            return
        }
        
        // Create summary title and content
        val summaryTitle = when {
            activeCount > 0 -> "Downloading $activeCount ${if (activeCount == 1) "file" else "files"}"
            completedCount > 0 -> "Downloaded $completedCount ${if (completedCount == 1) "file" else "files"}"
            failedCount > 0 -> "Failed $failedCount ${if (failedCount == 1) "download" else "downloads"}"
            else -> "Downloads"
        }
        
        // Create summary content
        val summaryContent = buildString {
            if (activeCount > 0) append("$activeCount active")
            if (completedCount > 0) {
                if (length > 0) append(", ")
                append("$completedCount completed")
            }
            if (failedCount > 0) {
                if (length > 0) append(", ")
                append("$failedCount failed")
            }
        }
        
        // Create intent to open Downloads app
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build the summary notification
        val summaryNotification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(summaryTitle)
            .setContentText(summaryContent)
            .setSmallIcon(
                if (activeCount > 0) android.R.drawable.stat_sys_download
                else android.R.drawable.stat_sys_download_done
            )
            .setGroup(NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .setOngoing(activeCount > 0)
            .setAutoCancel(activeCount == 0)  // Dismissable when no active downloads
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Prevent Android from treating it as a foreground service notification
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        
        // Show the summary notification
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, summaryNotification)
    }
    
    /**
     * Ensure the service is running in the foreground
     */
    private fun ensureForegroundService() {
        // Get count of active downloads for notification content
        val activeCount = activeDownloads.count { it.value.status == DownloadStatus.DOWNLOADING || it.value.status == DownloadStatus.PENDING }
        val completedCount = activeDownloads.count { it.value.status == DownloadStatus.COMPLETED }
        val failedCount = activeDownloads.count { it.value.status == DownloadStatus.FAILED || it.value.status == DownloadStatus.CANCELED }
        
        // No active downloads at all - stop foreground mode but keep service running for completed notifications
        if (activeCount == 0) {
            Log.d(TAG, "No active downloads, stopping foreground service")
            
            // Cancel foreground service notification
            notificationManager.cancel(FOREGROUND_SERVICE_ID)
            
            // Stop foreground mode but keep completed notifications visible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH) // Keep download notifications visible
            } else {
                stopForeground(false) // Keep download notifications visible
            }
            
            // If there are no downloads at all, we can stop the service entirely
            if (activeDownloads.isEmpty()) {
                Log.d(TAG, "No downloads at all, stopping service")
                stopSelf()
            }
            
            return
        }
        
        // Only show service notification if we have active downloads
        if (activeCount > 0) {
            Log.d(TAG, "Active downloads: $activeCount, running as foreground service")
            
            // Create a service notification that shows count of active downloads
            val serviceNotification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
                .setContentTitle("Downloading Files")
                .setContentText("Processing $activeCount ${if (activeCount == 1) "file" else "files"}")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                // Important: Don't use the same group for service notification
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW) // Use LOW for service notification
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            
            // Start as foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FOREGROUND_SERVICE_ID, serviceNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(FOREGROUND_SERVICE_ID, serviceNotification)
            }
        }
    }
    
    /**
     * Get MIME type from file name
     */
    private fun getMimeTypeFromFileName(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".gif", true) -> "image/gif"
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mp3", true) -> "audio/mp3"
            fileName.endsWith(".pdf", true) -> "application/pdf"
            fileName.endsWith(".doc", true) || fileName.endsWith(".docx", true) -> "application/msword"
            fileName.endsWith(".xls", true) || fileName.endsWith(".xlsx", true) -> "application/vnd.ms-excel"
            fileName.endsWith(".ppt", true) || fileName.endsWith(".pptx", true) -> "application/vnd.ms-powerpoint"
            fileName.endsWith(".txt", true) -> "text/plain"
            fileName.endsWith(".zip", true) -> "application/zip"
            fileName.endsWith(".rar", true) -> "application/x-rar-compressed"
            fileName.endsWith(".7z", true) -> "application/x-7z-compressed"
            fileName.endsWith(".tar", true) -> "application/x-tar"
            fileName.endsWith(".gz", true) -> "application/gzip"
            else -> "application/octet-stream"
        }
    }
    
    /**
     * Format file size to human-readable string
     */
    private fun formatFileSize(sizeBytes: Long): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> String.format("%.1f KB", sizeBytes / 1024f)
            sizeBytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", sizeBytes / (1024 * 1024f))
            else -> String.format("%.1f GB", sizeBytes / (1024 * 1024 * 1024f))
        }
    }
    
    /**
     * Get current timestamp as a string
     */
    private fun getCurrentTimestamp(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return dateFormat.format(Date())
    }
    
    /**
     * Clean up old completed downloads from the activeDownloads map
     * This keeps the service from accumulating too many completed downloads
     * while still allowing them to remain visible to the user for a while
     */
    private fun cleanupOldCompletedDownloads() {
        // Keep track of completion times for downloads
        val currentTime = System.currentTimeMillis()
        
        // If we don't have a record of when this download completed, add it now
        for ((id, task) in activeDownloads) {
            if (task.status == DownloadStatus.COMPLETED && task.completionTime == 0L) {
                task.completionTime = currentTime
            }
        }
        
        // Find completed downloads older than 1 hour (3600000 ms)
        val oldCompletedDownloads = activeDownloads.filter { (_, task) ->
            task.status == DownloadStatus.COMPLETED && 
            task.completionTime > 0 &&
            (currentTime - task.completionTime) > 3600000
        }.keys
        
        // Remove old completed downloads
        for (id in oldCompletedDownloads) {
            Log.d(TAG, "Cleaning up old completed download: $id")
            activeDownloads.remove(id)
        }
        
        // If we have no more downloads at all, stop the service
        if (activeDownloads.isEmpty()) {
            Log.d(TAG, "No more downloads, stopping service")
            stopSelf()
        }
    }
    
    /**
     * Binder class for service binding
     */
    inner class DownloadBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
}

/**
 * Download status enum
 */
enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELED
}

/**
 * Download task data class
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val filePath: String,
    val fileName: String,
    val mimeType: String,
    val notificationId: Int,
    var status: DownloadStatus,
    var size: Long = -1,
    var error: String = "",
    var completionTime: Long = 0 // Time when download completed (for auto-cleanup)
)