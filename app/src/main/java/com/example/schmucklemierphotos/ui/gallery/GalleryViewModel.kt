package com.example.schmucklemierphotos.ui.gallery

import android.content.Context
import android.util.Log
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.schmucklemierphotos.GCPAuthenticationManager
import com.example.schmucklemierphotos.GCPStorageManager
import com.example.schmucklemierphotos.cache.FileCache
import com.example.schmucklemierphotos.model.GalleryItem
import com.example.schmucklemierphotos.model.GalleryRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import com.example.schmucklemierphotos.utils.ThumbnailUtils

/**
 * ViewModel for managing the gallery state and operations
 */
class GalleryViewModel(
    private val context: Context,
    private val repository: GalleryRepository
) : ViewModel() {

    // Settings manager for app settings
    private val settingsManager = com.example.schmucklemierphotos.ui.settings.SettingsManager.getInstance(context)
    
    // File cache for caching files locally
    private val fileCache = FileCache(context)
    
    // Track files that are currently being downloaded to prevent duplicate downloads
    private val downloadsInProgress = Collections.synchronizedSet(mutableSetOf<String>())

    // Selected image for viewing
    private val _selectedImage = MutableStateFlow<GalleryItem.ImageFile?>(null)
    val selectedImage: StateFlow<GalleryItem.ImageFile?> = _selectedImage.asStateFlow()

    // Selected video for playing
    private val _selectedVideo = MutableStateFlow<GalleryItem.VideoFile?>(null)
    val selectedVideo: StateFlow<GalleryItem.VideoFile?> = _selectedVideo.asStateFlow()
    
    // All previewable items in current folder for paging
    private val _previewableItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val previewableItems: StateFlow<List<GalleryItem>> = _previewableItems.asStateFlow()
    
    // Current position in previewable items for paging
    private val _currentPreviewPosition = MutableStateFlow(0)
    val currentPreviewPosition: StateFlow<Int> = _currentPreviewPosition.asStateFlow()

    // Image/Video URLs for authenticated access
    private val _mediaUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val mediaUrls: StateFlow<Map<String, String>> = _mediaUrls.asStateFlow()
    
    // Thumbnail URLs for grid view
    private val _thumbnailUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val thumbnailUrls: StateFlow<Map<String, String>> = _thumbnailUrls.asStateFlow()

    // Track navigation path history with scroll positions
    private val navigationHistory = mutableListOf<NavigationState>()
    private var currentNavIndex = -1

    // Navigation stack for back button support
    private val _currentNavState = MutableStateFlow<NavigationState?>(null)
    val currentNavState: StateFlow<NavigationState?> = _currentNavState.asStateFlow()

    /**
     * Navigation state class to track path and scroll position
     */
    data class NavigationState(
        val path: String,
        val firstVisibleItemIndex: Int = 0,
        val firstVisibleItemOffset: Int = 0
    ) {
        fun toLazyGridState(): LazyGridState {
            return LazyGridState(
                firstVisibleItemIndex = firstVisibleItemIndex,
                firstVisibleItemScrollOffset = firstVisibleItemOffset
            )
        }
    }

    /**
     * Loads gallery items for a specific bucket and path
     * @param account The authenticated Google account
     * @param bucketName The GCS bucket name
     * @param path The path within the bucket
     */
    fun loadGalleryItems(account: GoogleSignInAccount, bucketName: String, path: String = "") {
        viewModelScope.launch {
            repository.loadGalleryItems(account, bucketName, path)
            updatePreviewableItems()
        }
    }
    
    /**
     * Updates the list of previewable items based on current gallery items
     */
    fun updatePreviewableItems() {
        val items = repository.galleryItems.value
        _previewableItems.value = items.filter { item ->
            item is GalleryItem.ImageFile || item is GalleryItem.VideoFile
        }
        
        println("DEBUG: Updated previewable items, found ${_previewableItems.value.size} items")
    }

    /**
     * Gets an authenticated URL for a given media file
     * @param account The authenticated Google account
     * @param bucketName The GCS bucket name
     * @param filePath The path to the file within the bucket
     */
    fun getMediaUrl(account: GoogleSignInAccount, bucketName: String, filePath: String) {
        viewModelScope.launch {
            try {
                // Check if we already have the URL cached
                if (_mediaUrls.value.containsKey(filePath)) {
                    return@launch
                }
                
                // Check if low bandwidth mode is active
                if (settingsManager.settings.value.lowBandwidthMode) {
                    // In low bandwidth mode, use the thumbnail instead of the full image
                    val thumbnailPath = ThumbnailUtils.generateThumbnailPath(filePath)
                    
                    // Check if we have the thumbnail cached locally
                    val cachedThumbnail = fileCache.getFile(thumbnailPath)
                    if (cachedThumbnail != null) {
                        // Use the cached thumbnail URI
                        val fileUri = "file://${cachedThumbnail.absolutePath}"
                        _mediaUrls.value = _mediaUrls.value + (filePath to fileUri)
                        return@launch
                    }
                    
                    try {
                        // Try to get thumbnail URL first
                        val thumbnailUrl = repository.getImageUrl(account, bucketName, thumbnailPath)
                        
                        // Store the thumbnail URL in our map
                        _mediaUrls.value = _mediaUrls.value + (filePath to thumbnailUrl)
                        
                        // Download and cache the thumbnail in the background
                        cacheFileFromUrl(account, bucketName, thumbnailPath)
                        return@launch
                    } catch (e: Exception) {
                        // If thumbnail doesn't exist, fall back to the full image
                        Log.d("GalleryViewModel", "No thumbnail available for $filePath in low bandwidth mode, falling back to full image")
                    }
                }
                
                // Normal path for full-quality images or when thumbnail isn't available
                
                // Check if we have the file cached locally
                val cachedFile = fileCache.getFile(filePath)
                if (cachedFile != null) {
                    // Use the cached file URI
                    val fileUri = "file://${cachedFile.absolutePath}"
                    _mediaUrls.value = _mediaUrls.value + (filePath to fileUri)
                    return@launch
                }
                
                // Get the URL from the repository
                val url = repository.getImageUrl(account, bucketName, filePath)
                
                // Store the URL in our map
                _mediaUrls.value = _mediaUrls.value + (filePath to url)
                
                // Download and cache the file in the background
                cacheFileFromUrl(account, bucketName, filePath)
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Error getting media URL for $filePath: ${e.message}")
            }
        }
    }

    /**
     * Downloads and caches a file from GCP Storage
     */
    private suspend fun cacheFileFromUrl(
        account: GoogleSignInAccount,
        bucketName: String, 
        filePath: String
    ) {
        // Check if this file is already being downloaded
        if (!downloadsInProgress.add(filePath)) {
            Log.d("GalleryViewModel", "Download already in progress for $filePath, skipping")
            return
        }
        
        try {
            Log.d("GalleryViewModel", "Starting download for $filePath")
            
            // Check one more time if the file exists locally
            if (fileCache.contains(filePath)) {
                Log.d("GalleryViewModel", "File $filePath already cached, skipping download")
                return
            }
            
            // Download the file content
            val fileContent = repository.getFileContent(account, bucketName, filePath)
            
            // Get the MIME type based on file extension
            val mimeType = ThumbnailUtils.getMimeTypeFromPath(filePath)
            
            // Cache the file
            fileCache.putFile(filePath, fileContent, mimeType)
            Log.d("GalleryViewModel", "Download completed for $filePath")
        } catch (e: Exception) {
            // Log but don't fail the app if caching fails
            Log.e("GalleryViewModel", "Failed to download $filePath: ${e.message}")
        } finally {
            // Always remove from in-progress set when finished, even if there was an error
            downloadsInProgress.remove(filePath)
        }
    }
    
    /**
     * Gets an authenticated URL for a thumbnail
     * @param account The authenticated Google account
     * @param bucketName The GCS bucket name
     * @param originalPath The path to the original file
     * @param forceLoad Force loading even in extreme low bandwidth mode
     */
    fun getThumbnailUrl(
        account: GoogleSignInAccount, 
        bucketName: String, 
        originalPath: String,
        forceLoad: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                // Check if extreme low bandwidth mode is active and we're not forcing load
                if (settingsManager.settings.value.extremeLowBandwidthMode && !forceLoad) {
                    // Skip loading thumbnails while scrolling in extreme low bandwidth mode
                    return@launch
                }
                
                // Generate the thumbnail path
                val thumbnailPath = ThumbnailUtils.generateThumbnailPath(originalPath)
                
                // Check if we already have the URL cached
                if (_thumbnailUrls.value.containsKey(originalPath)) {
                    return@launch
                }
                
                // Check if we have the thumbnail file cached locally
                val cachedFile = fileCache.getFile(thumbnailPath)
                if (cachedFile != null) {
                    // Use the cached file URI
                    val fileUri = "file://${cachedFile.absolutePath}"
                    _thumbnailUrls.value = _thumbnailUrls.value + (originalPath to fileUri)
                    return@launch
                }
                
                try {
                    // Get the thumbnail URL from the repository
                    val url = repository.getImageUrl(account, bucketName, thumbnailPath)
                    
                    // Store the URL in our thumbnail map, keyed by the original path for easy lookup
                    _thumbnailUrls.value = _thumbnailUrls.value + (originalPath to url)
                    
                    // Download and cache the thumbnail in the background
                    cacheFileFromUrl(account, bucketName, thumbnailPath)
                } catch (e: Exception) {
                    // If thumbnail doesn't exist, just don't add any URL
                    // The UI will show a fallback icon
                    Log.w("GalleryViewModel", "No thumbnail available for $originalPath: ${e.message}")
                }
            } catch (e: Exception) {
                // Handle error silently for thumbnails
                Log.e("GalleryViewModel", "Error getting thumbnail URL for $originalPath: ${e.message}")
            }
        }
    }

    /**
     * Sets the selected image and updates position for paging
     */
    fun selectImage(image: GalleryItem.ImageFile) {
        _selectedImage.value = image
        updateCurrentPreviewPosition(image)
    }

    /**
     * Clears the selected image
     */
    fun clearSelectedImage() {
        _selectedImage.value = null
    }

    /**
     * Sets the selected video for playback and updates position for paging
     */
    fun selectVideo(video: GalleryItem.VideoFile) {
        _selectedVideo.value = video
        updateCurrentPreviewPosition(video)
    }

    /**
     * Clears the selected video
     */
    fun clearSelectedVideo() {
        _selectedVideo.value = null
    }
    
    /**
     * Updates current position in the previewable items list
     */
    private fun updateCurrentPreviewPosition(item: GalleryItem) {
        val index = _previewableItems.value.indexOfFirst { it.path == item.path }
        if (index != -1) {
            _currentPreviewPosition.value = index
            println("DEBUG: Updated current preview position to $index for ${item.path}")
        } else {
            println("DEBUG: Could not find item ${item.path} in previewable items list")
        }
    }
    
    /**
     * Pre-caches adjacent media items (full-size images)
     */
    fun preCacheAdjacentItems(account: GoogleSignInAccount, bucketName: String) {
        viewModelScope.launch {
            val currentPos = _currentPreviewPosition.value
            val items = _previewableItems.value
            
            if (items.isEmpty() || currentPos < 0 || currentPos >= items.size) return@launch
            
            // Get indices of adjacent items, wrapping around if needed
            val prevIndex = if (currentPos > 0) currentPos - 1 else items.size - 1
            val nextIndex = if (currentPos < items.size - 1) currentPos + 1 else 0
            
            Log.d("GalleryViewModel", "Pre-caching full-size images for positions $prevIndex and $nextIndex")
            
            // Pre-cache both images and videos
            listOf(prevIndex, nextIndex).forEach { index ->
                val item = items[index]
                
                // Always get thumbnails first for faster display
                getThumbnailUrl(account, bucketName, item.path)
                
                // Then get full-size media if not already cached
                if (!_mediaUrls.value.containsKey(item.path)) {
                    getMediaUrl(account, bucketName, item.path)
                }
            }
        }
    }
    
    /**
     * Pre-caches thumbnails for items in the specified range around the current position
     * @param account The Google Sign-In account
     * @param bucketName The GCS bucket name
     * @param range The number of items to pre-cache in each direction (default: 2)
     */
    fun preCacheThumbnails(account: GoogleSignInAccount, bucketName: String, range: Int = 2) {
        viewModelScope.launch {
            val currentPos = _currentPreviewPosition.value
            val items = _previewableItems.value
            
            if (items.isEmpty() || currentPos < 0 || currentPos >= items.size) return@launch

            // Calculate indices for items to pre-cache, handling wrapping
            val indices = mutableSetOf<Int>()
            for (offset in -range..range) {
                if (offset == 0) continue // Skip current position as it should already be loaded
                
                val index = (currentPos + offset).mod(items.size)
                indices.add(index)
            }
            
            // Pre-cache thumbnails for the determined indices
            indices.forEach { index ->
                val item = items[index]
                // Get thumbnails for both images and videos
                if (!_thumbnailUrls.value.containsKey(item.path)) {
                    getThumbnailUrl(account, bucketName, item.path)
                }
            }
        }
    }
    
    /**
     * Navigates to previous item in the previewable items list
     */
    fun navigateToPreviousPreviewable(account: GoogleSignInAccount, bucketName: String) {
        viewModelScope.launch {
            val items = _previewableItems.value
            if (items.isEmpty()) return@launch
            
            val currentPos = _currentPreviewPosition.value
            val newPos = if (currentPos > 0) currentPos - 1 else items.size - 1
            _currentPreviewPosition.value = newPos
            
            val item = items[newPos]
            when (item) {
                is GalleryItem.ImageFile -> {
                    _selectedVideo.value = null
                    _selectedImage.value = item
                    
                    // IMPORTANT: First get thumbnail URL with high priority
                    // Use a separate coroutine to ensure it's started immediately
                    viewModelScope.launch(Dispatchers.IO) {
                        getThumbnailUrl(account, bucketName, item.path)
                        
                        // Pre-cache thumbnails first
                        preCacheThumbnails(account, bucketName, 2)
                    }
                    
                    // Then load full image and pre-cache in a separate coroutine with lower priority
                    viewModelScope.launch(Dispatchers.IO) {
                        // Small delay to ensure thumbnails get priority
                        delay(50)
                        getMediaUrl(account, bucketName, item.path)
                        
                        // Then pre-cache full-size images
                        delay(50)
                        preCacheAdjacentItems(account, bucketName)
                    }
                }
                is GalleryItem.VideoFile -> {
                    _selectedImage.value = null
                    _selectedVideo.value = item
                    
                    // Prioritize thumbnail loading in a separate coroutine
                    viewModelScope.launch(Dispatchers.IO) { 
                        getThumbnailUrl(account, bucketName, item.path)
                    }
                    
                    // Then load the full video in a separate coroutine
                    viewModelScope.launch(Dispatchers.IO) {
                        delay(50) // Small delay to prioritize thumbnail
                        getMediaUrl(account, bucketName, item.path)
                    }
                }
                else -> { /* Not previewable */ }
            }
        }
    }
    
    /**
     * Navigates to next item in the previewable items list
     */
    fun navigateToNextPreviewable(account: GoogleSignInAccount, bucketName: String) {
        viewModelScope.launch {
            val items = _previewableItems.value
            if (items.isEmpty()) return@launch
            
            val currentPos = _currentPreviewPosition.value
            val newPos = if (currentPos < items.size - 1) currentPos + 1 else 0
            _currentPreviewPosition.value = newPos
            
            val item = items[newPos]
            when (item) {
                is GalleryItem.ImageFile -> {
                    _selectedVideo.value = null
                    _selectedImage.value = item
                    
                    // IMPORTANT: First get thumbnail URL with high priority
                    // Use a separate coroutine to ensure it's started immediately
                    viewModelScope.launch(Dispatchers.IO) {
                        getThumbnailUrl(account, bucketName, item.path)
                        
                        // Pre-cache thumbnails first
                        preCacheThumbnails(account, bucketName, 2)
                    }
                    
                    // Then load full image and pre-cache in a separate coroutine with lower priority
                    viewModelScope.launch(Dispatchers.IO) {
                        // Small delay to ensure thumbnails get priority
                        delay(50)
                        getMediaUrl(account, bucketName, item.path)
                        
                        // Then pre-cache full-size images
                        delay(50)
                        preCacheAdjacentItems(account, bucketName)
                    }
                }
                is GalleryItem.VideoFile -> {
                    _selectedImage.value = null
                    _selectedVideo.value = item
                    
                    // Prioritize thumbnail loading in a separate coroutine
                    viewModelScope.launch(Dispatchers.IO) { 
                        getThumbnailUrl(account, bucketName, item.path)
                    }
                    
                    // Then load the full video in a separate coroutine
                    viewModelScope.launch(Dispatchers.IO) {
                        delay(50) // Small delay to prioritize thumbnail
                        getMediaUrl(account, bucketName, item.path)
                    }
                }
                else -> { /* Not previewable */ }
            }
        }
    }

    /**
     * Navigates to a path and pushes it onto the navigation stack
     */
    fun navigateToPath(path: String, gridState: LazyGridState? = null) {
        // Save current scroll position
        val currentPath = repository.currentPath.value
        val currentItem = gridState?.firstVisibleItemIndex ?: 0
        val currentOffset = gridState?.firstVisibleItemScrollOffset ?: 0
        
        // If we're not at the end of the history, truncate it
        if (currentNavIndex < navigationHistory.size - 1) {
            navigationHistory.subList(currentNavIndex + 1, navigationHistory.size).clear()
        }
        
        // Update current position in history if not empty
        if (currentNavIndex >= 0 && currentNavIndex < navigationHistory.size) {
            navigationHistory[currentNavIndex] = NavigationState(
                path = currentPath,
                firstVisibleItemIndex = currentItem,
                firstVisibleItemOffset = currentOffset
            )
        }
        
        // Add new path to history
        navigationHistory.add(NavigationState(path))
        currentNavIndex = navigationHistory.size - 1
        
        // Update current nav state
        _currentNavState.value = navigationHistory[currentNavIndex]
        
        // Update repository path
        repository.navigateToPath(path)
    }

    /**
     * Navigates back in the history stack
     * @return true if we can navigate back, false if we're at the root
     */
    fun navigateBack(): Boolean {
        if (currentNavIndex > 0) {
            currentNavIndex--
            val navState = navigationHistory[currentNavIndex]
            _currentNavState.value = navState
            repository.navigateToPath(navState.path)
            return true
        } else if (repository.currentPath.value.isNotEmpty()) {
            // We're at the first history entry but not at the root, go to root
            val rootNavState = NavigationState("")
            navigationHistory.clear()
            navigationHistory.add(rootNavState)
            currentNavIndex = 0
            _currentNavState.value = rootNavState
            repository.navigateToPath("")
            return true
        }
        return false
    }

    /**
     * Updates the scroll position for the current navigation state
     */
    fun updateScrollPosition(gridState: LazyGridState) {
        if (currentNavIndex >= 0 && currentNavIndex < navigationHistory.size) {
            navigationHistory[currentNavIndex] = NavigationState(
                path = navigationHistory[currentNavIndex].path,
                firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                firstVisibleItemOffset = gridState.firstVisibleItemScrollOffset
            )
            _currentNavState.value = navigationHistory[currentNavIndex]
        }
    }

    /**
     * Clears the navigation history
     */
    fun clearNavigationHistory() {
        navigationHistory.clear()
        currentNavIndex = -1
        _currentNavState.value = null
    }

    /**
     * Factory for creating GalleryViewModel with the proper dependencies
     */
    class Factory(
        private val context: Context,
        private val repositoryLazy: Lazy<GalleryRepository>? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
                val repository = if (repositoryLazy != null) {
                    repositoryLazy.value
                } else {
                    // Create a new repository if one wasn't provided
                    val authManager = GCPAuthenticationManager(context)
                    val storageManager = GCPStorageManager(context, authManager)
                    GalleryRepository(storageManager)
                }
                return GalleryViewModel(context, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}