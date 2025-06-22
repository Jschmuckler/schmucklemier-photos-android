package com.example.schmucklemierphotos.model

import android.content.Context
import android.util.Log
import com.example.schmucklemierphotos.GCPStorageManager
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.services.storage.Storage
import com.example.schmucklemierphotos.utils.ThumbnailUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Repository class to handle interactions with the GCP Storage
 */
class GalleryRepository(private val storageManager: GCPStorageManager) {

    companion object {
        private const val TAG = "GalleryRepository"
        private val IMAGE_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic", ".heif"
        )
        private val VIDEO_EXTENSIONS = listOf(
            ".mp4", ".mov", ".avi", ".mkv", ".wmv", ".webm", ".ts", ".m2ts", ".mts"
        )
        private val DOCUMENT_EXTENSIONS = listOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt"
        )
    }
    
    // Cache for gallery items by path
    private val itemsCache = mutableMapOf<String, List<GalleryItem>>()

    // State flow to emit gallery items as they're loaded
    private val _galleryItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<GalleryItem>> = _galleryItems.asStateFlow()

    // Current path being browsed
    private val _currentPath = MutableStateFlow<String>("") 
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Loads the contents of a bucket or folder
     * @param account The authenticated Google account
     * @param bucketName The name of the GCS bucket
     * @param prefix The folder prefix to load (empty string for root)
     */
    suspend fun loadGalleryItems(account: GoogleSignInAccount, bucketName: String, prefix: String = "") {
        withContext(Dispatchers.IO) {
            try {
                _isLoading.value = true
                _error.value = null
                _currentPath.value = prefix
                
                // Check if we have cached items for this path
                if (itemsCache.containsKey(prefix)) {
                    Log.d(TAG, "Using cached items for path: $prefix")
                    _galleryItems.value = itemsCache[prefix] ?: emptyList()
                    _isLoading.value = false
                    return@withContext
                }
                
                Log.d(TAG, "Loading items from storage for path: $prefix")
                
                // Get directories and files from storage
                val directories = storageManager.listBucketDirectories(account, bucketName, prefix = prefix)
                val files = storageManager.listBucketFiles(account, bucketName, prefix = prefix)

                // Process directories into folder items and filter out special folders
                val folderItems = directories
                    .filter { !ThumbnailUtils.isHiddenFolder(it) }  // Filter out THUMBS and COMPRESSED folders
                    .map { dirPath ->
                        GalleryItem.Folder(
                            name = dirPath,
                            path = dirPath,
                            lastModified = null // We don't have this info from GCS prefixes
                        )
                    }

                // Process files into file items
                val fileItems = files.mapNotNull { fileObject ->
                    // Skip files that are the prefix itself, empty prefixes, or special files (thumbs/compressed)
                    if (fileObject.name == prefix || 
                        fileObject.name.endsWith("/") || 
                        ThumbnailUtils.isThumbnailFile(fileObject.name) ||
                        ThumbnailUtils.isCompressedFile(fileObject.name)) {
                        return@mapNotNull null
                    }

                    // Create appropriate file item based on extension
                    val extension = fileObject.name.substringAfterLast('.', "").lowercase()
                    val mimeType = fileObject.contentType
                    val lastModified = fileObject.updated?.value?.let { Date(it) }
                    val size = fileObject.size?.toLong() ?: 0L

                    when {
                        IMAGE_EXTENSIONS.any { fileObject.name.lowercase().endsWith(it) } -> {
                            GalleryItem.ImageFile(
                                name = fileObject.name,
                                path = fileObject.name,
                                lastModified = lastModified,
                                size = size,
                                mimeType = mimeType
                            )
                        }
                        VIDEO_EXTENSIONS.any { fileObject.name.lowercase().endsWith(it) } -> {
                            GalleryItem.VideoFile(
                                name = fileObject.name,
                                path = fileObject.name,
                                lastModified = lastModified,
                                size = size,
                                mimeType = mimeType
                            )
                        }
                        DOCUMENT_EXTENSIONS.any { fileObject.name.lowercase().endsWith(it) } -> {
                            GalleryItem.DocumentFile(
                                name = fileObject.name,
                                path = fileObject.name,
                                lastModified = lastModified,
                                size = size,
                                mimeType = mimeType
                            )
                        }
                        else -> {
                            GalleryItem.OtherFile(
                                name = fileObject.name,
                                path = fileObject.name,
                                lastModified = lastModified,
                                size = size,
                                mimeType = mimeType
                            )
                        }
                    }
                }.filter { !it.shouldIgnore() } // Filter out ignored files

                // Combine and emit all items
                val allItems = folderItems + fileItems
                Log.d(TAG, "Setting gallery items - Folders: ${folderItems.size}, Files: ${fileItems.size}, Total: ${allItems.size}")
                for (folder in folderItems) {
                    Log.d(TAG, "Folder item: name=${folder.name}, path=${folder.path}, displayName=${folder.getDisplayName()}")
                }
                
                // Cache the items for this path
                itemsCache[prefix] = allItems
                
                // Update the UI with the items
                _galleryItems.value = allItems

            } catch (e: Exception) {
                Log.e(TAG, "Error loading gallery items: ${e.message}", e)
                _error.value = "Failed to load items: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Gets an authenticated URL for a file
     * @param account The authenticated Google account
     * @param bucketName The name of the GCS bucket
     * @param filePath The path to the file within the bucket
     * @return Authenticated URL for the file
     */
    suspend fun getImageUrl(account: GoogleSignInAccount, bucketName: String, filePath: String): String {
        return storageManager.getAuthenticatedFileUrl(account, bucketName, filePath)
    }
    
    /**
     * Gets the raw content of a file from GCP Storage
     * @param account The authenticated Google account
     * @param bucketName The name of the GCS bucket
     * @param filePath The path to the file within the bucket
     * @return Raw file content as byte array
     * @throws IllegalArgumentException if file is too large for direct download
     */
    suspend fun getFileContent(account: GoogleSignInAccount, bucketName: String, filePath: String): ByteArray {
        // For videos, we don't want to load the entire file - it will cause OOM
        if (isVideoFile(filePath)) {
            throw IllegalArgumentException("Videos should use streaming URL instead of direct download")
        }
        
        return storageManager.getFileContent(account, bucketName, filePath)
    }
    
    /**
     * Gets a direct streaming URL for a video or large file
     * @param account The authenticated Google account
     * @param bucketName The name of the GCS bucket
     * @param filePath The path to the file within the bucket
     * @return A streaming URL for the file
     */
    suspend fun getStreamingUrl(account: GoogleSignInAccount, bucketName: String, filePath: String): String {
        return storageManager.getStreamingUrl(account, bucketName, filePath)
    }
    
    /**
     * Checks if a file is a video based on its extension
     * @param filePath The path to check
     * @return true if the file is a video, false otherwise
     */
    fun isVideoFile(filePath: String): Boolean {
        return VIDEO_EXTENSIONS.any { filePath.lowercase().endsWith(it) }
    }

    /**
     * Navigates up one level in the folder hierarchy
     * @return The new parent path
     */
    fun navigateUp(): String {
        val current = _currentPath.value
        
        // Special handling for empty path (already at root)
        if (current.isEmpty()) {
            return ""
        }
        
        // Handle paths with trailing slash
        val normalizedPath = current.trimEnd('/')
        
        // Get parent path
        val parent = if (normalizedPath.contains('/')) {
            val parentPath = normalizedPath.substringBeforeLast('/')
            if (parentPath.isEmpty()) "" else "$parentPath/"
        } else {
            "" // Root level
        }
        
        Log.d(TAG, "Navigating up from '$current' to '$parent'")
        _currentPath.value = parent
        return parent
    }
    
    /**
     * Refreshes the current path by re-setting it
     * This triggers observers to reload the data
     */
    fun refreshCurrentPath() {
        val currentPath = _currentPath.value
        // Re-assign the same value to trigger state flow
        _currentPath.value = currentPath
    }
    
    /**
     * Navigates to a specific path
     * @param path The path to navigate to
     */
    fun navigateToPath(path: String) {
        _currentPath.value = path
    }
    
    /**
     * Gets the storage manager for direct access
     * @return The GCPStorageManager instance
     */
    fun getStorageManager(): GCPStorageManager {
        return storageManager
    }
    
    /**
     * Gets the current bucket name (used for file size calculations)
     * @return The current bucket name or empty string if not available
     */
    fun getCurrentBucketName(): String {
        return "schmucklemier-long-term"
    }
}
