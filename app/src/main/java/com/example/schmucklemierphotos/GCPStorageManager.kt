package com.example.schmucklemierphotos

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.services.storage.Storage
import com.google.api.services.storage.model.Objects
import com.google.api.services.storage.model.StorageObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager class to handle Google Cloud Storage operations
 */
class GCPStorageManager(
    private val context: Context,
    private val authManager: GCPAuthenticationManager
) {

    companion object {
        private const val TAG = "GCPStorageManager"
        private const val MAX_RESULTS = 1000 // Maximum number of results to return in a single request
    }

    /**
     * Lists all directories (folders) in a GCS bucket
     * @param account The Google Sign-In account to use for authentication
     * @param bucketName The name of the GCS bucket
     * @param delimiter The delimiter character to use for directory-like listing (typically '/')
     * @param prefix Optional prefix to list objects under a specific path
     * @return List of directory names found in the bucket
     */
    suspend fun listBucketDirectories(
        account: GoogleSignInAccount,
        bucketName: String,
        delimiter: String = "/",
        prefix: String = ""
    ): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Listing directories in bucket: $bucketName, prefix: $prefix")
                
                // Create a storage service client
                val storage = authManager.createStorageService(account)
                
                // List objects with delimiter to simulate directory listing
                val listRequest = storage.objects().list(bucketName)
                    .setDelimiter(delimiter)
                    .setPrefix(prefix)
                    .setMaxResults(MAX_RESULTS.toLong())
                
                // Execute the request
                val objects = listRequest.execute()
                
                // Extract prefixes (directories)
                val directories = objects.prefixes ?: emptyList()
                
                Log.d(TAG, "Found ${directories.size} directories in bucket")
                
                // Return the list of directories
                directories
                
            } catch (e: Exception) {
                Log.e(TAG, "Error listing directories: ${e.message}", e)
                throw e
            }
        }
    }
    
    /**
     * Lists all files in a GCS bucket at a specific prefix path
     * @param account The Google Sign-In account to use for authentication
     * @param bucketName The name of the GCS bucket
     * @param delimiter The delimiter character to use for directory-like listing (typically '/')
     * @param prefix Optional prefix to list objects under a specific path
     * @return List of StorageObject items representing files in the bucket
     */
    suspend fun listBucketFiles(
        account: GoogleSignInAccount,
        bucketName: String,
        delimiter: String = "/",
        prefix: String = ""
    ): List<StorageObject> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Listing files in bucket: $bucketName, prefix: $prefix")
                
                // Create a storage service client
                val storage = authManager.createStorageService(account)
                
                // List objects with delimiter to simulate directory listing
                val listRequest = storage.objects().list(bucketName)
                    .setDelimiter(delimiter)
                    .setPrefix(prefix)
                    .setMaxResults(MAX_RESULTS.toLong())
                
                // Execute the request
                val objects = listRequest.execute()
                
                // Get the items (files)
                val files = objects.items ?: emptyList()
                
                Log.d(TAG, "Found ${files.size} files in bucket at prefix: $prefix")
                
                // Return the list of files
                files
                
            } catch (e: Exception) {
                Log.e(TAG, "Error listing files: ${e.message}", e)
                throw e
            }
        }
    }
    
    /**
     * Gets the raw content of a file in Google Cloud Storage
     * @param account The Google Sign-In account to use for authentication
     * @param bucketName The name of the GCS bucket
     * @param objectPath The path to the object within the bucket
     * @param maxSize Maximum size in bytes to download (prevents OOM for large files)
     * @return Raw content as a byte array
     * Note: For large files, consider using getStreamingUrl or downloadFileToStream instead
     */
    suspend fun getFileContent(
        account: GoogleSignInAccount, 
        bucketName: String, 
        objectPath: String,
        maxSize: Long = 50 * 1024 * 1024 // Default 50MB limit for photos
    ): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Getting content for $bucketName/$objectPath")
                
                // Create a storage service client
                val storage = authManager.createStorageService(account)
                
                // First check the file size
                val metadata = storage.objects().get(bucketName, objectPath).execute()
                val fileSize = metadata.size
                
                if (fileSize > maxSize) {
                    Log.w(TAG, "File size ($fileSize bytes) exceeds maxSize ($maxSize bytes), consider using streaming methods")
                    throw IllegalArgumentException("File too large to download directly: $fileSize bytes. Use streaming methods for files larger than $maxSize bytes.")
                }
                
                // Get the object with media download
                val getRequest = storage.objects().get(bucketName, objectPath)
                getRequest.alt = "media" // Important! This tells the API to return the actual content
                
                // Use chunked download for larger files to avoid OOM
                if (fileSize > 8 * 1024 * 1024) { // For files larger than 8MB
                    Log.d(TAG, "Using chunked download for large file: $fileSize bytes")
                    val downloader = getRequest.mediaHttpDownloader
                    downloader.isDirectDownloadEnabled = true
                    downloader.chunkSize = 4 * 1024 * 1024 // 4MB chunks
                }
                
                // Pre-size the output stream when we know the size to avoid resizing
                val bufferSize = Math.min(fileSize.toInt(), 4 * 1024 * 1024) // Cap initial buffer at 4MB
                val outputStream = java.io.ByteArrayOutputStream(bufferSize)
                getRequest.executeMediaAndDownloadTo(outputStream)
                
                Log.d(TAG, "File content downloaded successfully, size: ${outputStream.size()}")
                outputStream.toByteArray()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error getting file content: ${e.message}", e)
                throw e
            }
        }
    }
    
    /**
     * Gets a direct streaming URL for a file in Google Cloud Storage
     * This is preferred for large files like videos to avoid memory issues
     * @param account The Google Sign-In account to use for authentication
     * @param bucketName The name of the GCS bucket
     * @param objectPath The path to the object within the bucket
     * @return A direct HTTP URL that can be used for streaming
     */
    suspend fun getStreamingUrl(
        account: GoogleSignInAccount,
        bucketName: String,
        objectPath: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                // For videos and other large files, we prefer to use a direct URL
                // rather than downloading the entire file
                val url = authManager.getAuthenticatedFileUrl(account, bucketName, objectPath)
                Log.d(TAG, "Generated streaming URL for $bucketName/$objectPath")
                url
            } catch (e: Exception) {
                Log.e(TAG, "Error getting streaming URL: ${e.message}", e)
                throw e
            }
        }
    }
    
    /**
     * Downloads a file to an output stream, using chunked transfer to avoid memory issues
     * @param account The Google Sign-In account to use for authentication
     * @param bucketName The name of the GCS bucket
     * @param objectPath The path to the object within the bucket
     * @param outputStream The output stream to write the file content to
     * @param chunkSize The size of chunks to download at once (default 4MB)
     */
    suspend fun downloadFileToStream(
        account: GoogleSignInAccount,
        bucketName: String,
        objectPath: String,
        outputStream: java.io.OutputStream,
        chunkSize: Int = 4 * 1024 * 1024 // 4MB chunks by default
    ) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Downloading $bucketName/$objectPath in chunks")
                
                // Create a storage service client
                val storage = authManager.createStorageService(account)
                
                // Get object metadata to get file size
                val metadata = storage.objects().get(bucketName, objectPath).execute()
                val fileSize = metadata.size ?: 0L
                
                // Use MediaHttpDownloader with custom settings
                val getRequest = storage.objects().get(bucketName, objectPath)
                getRequest.alt = "media"
                
                // Configure the downloader to use direct media download with chunking
                val downloader = getRequest.mediaHttpDownloader
                downloader.isDirectDownloadEnabled = true
                downloader.chunkSize = chunkSize
                
                // Execute the download
                getRequest.executeMediaAndDownloadTo(outputStream)
                
                Log.d(TAG, "File downloaded successfully using chunked download")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading file: ${e.message}", e)
                throw e
            }
        }
    }
    
    /**
     * Gets an authenticated URL for a file in Google Cloud Storage
     * This method delegates to the authentication manager's implementation
     */
    suspend fun getAuthenticatedFileUrl(
        account: GoogleSignInAccount,
        bucketName: String,
        objectPath: String
    ): String {
        return authManager.getAuthenticatedFileUrl(account, bucketName, objectPath)
    }
    
    /**
     * Gets metadata about a file in Google Cloud Storage
     * @param account The Google Sign-In account to use for authentication
     * @param bucketName The name of the GCS bucket
     * @param objectPath The path to the object within the bucket
     * @return StorageObject containing metadata about the file
     */
    suspend fun getFileMetadata(
        account: GoogleSignInAccount,
        bucketName: String,
        objectPath: String
    ): StorageObject = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Getting metadata for $bucketName/$objectPath")
            
            // Create a storage service client
            val storage = authManager.createStorageService(account)
            
            // Get the object metadata
            val metadata = storage.objects().get(bucketName, objectPath).execute()
            
            Log.d(TAG, "File size for $objectPath: ${metadata.size} bytes")
            return@withContext metadata
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file metadata: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Checks if a file exceeds a specified size threshold
     * @param account The Google Sign-In account to use for authentication
     * @param bucketName The name of the GCS bucket
     * @param objectPath The path to the object within the bucket
     * @param thresholdBytes Size threshold in bytes
     * @return True if the file is larger than the threshold, false otherwise
     */
    suspend fun isFileLargerThan(
        account: GoogleSignInAccount,
        bucketName: String,
        objectPath: String,
        thresholdBytes: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Get file metadata
            val metadata = getFileMetadata(account, bucketName, objectPath)
            val fileSize = metadata.size
            
            Log.d(TAG, "File size check for $objectPath: $fileSize bytes vs threshold $thresholdBytes bytes")
            return@withContext fileSize > thresholdBytes
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking file size: ${e.message}", e)
            // If we can't determine the size, assume it's not large
            return@withContext false
        }
    }
}