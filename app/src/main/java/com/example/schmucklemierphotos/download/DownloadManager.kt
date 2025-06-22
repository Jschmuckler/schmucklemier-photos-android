package com.example.schmucklemierphotos.download

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.schmucklemierphotos.GCPStorageManager
import com.example.schmucklemierphotos.download.DownloadService.Companion.ACTION_START_DOWNLOAD
import com.example.schmucklemierphotos.download.DownloadService.Companion.EXTRA_BUCKET_NAME
import com.example.schmucklemierphotos.download.DownloadService.Companion.EXTRA_DOWNLOAD_ID
import com.example.schmucklemierphotos.download.DownloadService.Companion.EXTRA_FILE_NAME
import com.example.schmucklemierphotos.download.DownloadService.Companion.EXTRA_FILE_PATH
import com.example.schmucklemierphotos.download.DownloadService.Companion.EXTRA_FILE_URL
import com.example.schmucklemierphotos.download.DownloadService.Companion.EXTRA_MIME_TYPE
import com.example.schmucklemierphotos.model.GalleryItem
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import java.util.UUID

/**
 * Manager class to handle download operations using the DownloadService
 */
class DownloadManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DownloadManager"
        
        // Singleton instance
        @Volatile
        private var INSTANCE: DownloadManager? = null
        
        fun getInstance(context: Context): DownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Service connection for binding to DownloadService
    private var downloadService: DownloadService? = null
    private var bound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DownloadService.DownloadBinder
            downloadService = binder.getService()
            bound = true
            Log.d(TAG, "Service connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
            bound = false
            Log.d(TAG, "Service disconnected")
        }
    }
    
    /**
     * Bind to the download service
     */
    fun bindService() {
        if (!bound) {
            val intent = Intent(context, DownloadService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    /**
     * Unbind from the download service
     */
    fun unbindService() {
        if (bound) {
            context.unbindService(serviceConnection)
            bound = false
        }
    }
    
    /**
     * Download a file from a URL
     */
    fun downloadFile(
        url: String,
        item: GalleryItem,
        account: GoogleSignInAccount,
        bucketName: String
    ) {
        // Generate a unique download ID
        val downloadId = UUID.randomUUID().toString()
        
        // Create a download intent
        val intent = Intent(context, DownloadService::class.java).apply {
            action = ACTION_START_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            putExtra(EXTRA_FILE_URL, url)
            putExtra(EXTRA_FILE_PATH, item.path)
            putExtra(EXTRA_FILE_NAME, item.path.substringAfterLast('/'))
            
            // Add MIME type if available
            when (item) {
                is GalleryItem.ImageFile -> putExtra(EXTRA_MIME_TYPE, item.mimeType ?: "image/*")
                is GalleryItem.VideoFile -> putExtra(EXTRA_MIME_TYPE, item.mimeType ?: "video/*")
                is GalleryItem.DocumentFile -> putExtra(EXTRA_MIME_TYPE, item.mimeType ?: "application/octet-stream")
                else -> putExtra(EXTRA_MIME_TYPE, "application/octet-stream")
            }
            
            // Add bucket name for reference
            putExtra(EXTRA_BUCKET_NAME, bucketName)
        }
        
        // Start the service (which will handle the download)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        
        // Bind to the service if not already bound
        bindService()
    }
    
    /**
     * Cancel a download
     */
    fun cancelDownload(downloadId: String) {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }
        
        context.startService(intent)
    }
}