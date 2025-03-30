package com.example.schmucklemierphotos

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.services.storage.Storage
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
     * @return Raw content as a byte array
     * Note: For large files, this could cause memory issues
     */
    suspend fun getFileContent(
        account: GoogleSignInAccount, 
        bucketName: String, 
        objectPath: String
    ): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Getting content for $bucketName/$objectPath")
                
                // Create a storage service client
                val storage = authManager.createStorageService(account)
                
                // Get the object with media download
                val getRequest = storage.objects().get(bucketName, objectPath)
                getRequest.alt = "media" // Important! This tells the API to return the actual content
                
                // Execute the request and get the content
                val outputStream = java.io.ByteArrayOutputStream()
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
}