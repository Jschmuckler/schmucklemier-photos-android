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
import java.io.IOException

/**
 * ViewModel for managing the gallery state and operations
 */
class GalleryViewModel(
    private val context: Context,
    private val repository: GalleryRepository,
    private val storageManager: GCPStorageManager? = null // Optional direct reference to storage manager
) : ViewModel() {

    // Settings manager for app settings
    private val settingsManager = com.example.schmucklemierphotos.ui.settings.SettingsManager.getInstance(context)
    
    // File cache for caching files locally
    private val fileCache = FileCache(context)
    
    // Track files that are currently being downloaded to prevent duplicate downloads
    private val downloadsInProgress = Collections.synchronizedSet(mutableSetOf<String>())
    
    // Multi-selection state
    private val _isInSelectionMode = MutableStateFlow(false)
    val isInSelectionMode: StateFlow<Boolean> = _isInSelectionMode.asStateFlow()
    
    // Selected items for multi-selection
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems.asStateFlow()
    
    // Total selected items size
    private val _selectedItemsSize = MutableStateFlow(0L)
    val selectedItemsSize: StateFlow<Long> = _selectedItemsSize.asStateFlow()

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
                
                // Check if this is a video file
                if (repository.isVideoFile(filePath)) {
                    // For videos, look for compressed version first, then fall back to streaming URL
                    val compressedPaths = ThumbnailUtils.generateCompressedVideoPaths(filePath)
                    
                    // Try each compressed path in priority order (ts -> mp4 -> webm)
                    for (compressedPath in compressedPaths) {
                        try {
                            // Check if this compressed version exists
                            val compressedUrl = repository.getImageUrl(account, bucketName, compressedPath)
                            if (compressedUrl.isNotEmpty()) {
                                // Found a compressed version - use it
                                _mediaUrls.value = _mediaUrls.value + (filePath to compressedUrl)
                                Log.d("GalleryViewModel", "Using compressed video: $compressedPath for $filePath")
                                return@launch
                            }
                        } catch (e: Exception) {
                            // This compressed version doesn't exist, try next one
                            Log.d("GalleryViewModel", "Compressed version not found: $compressedPath")
                            // Continue to next format
                        }
                    }
                    
                    // No compressed version found, use streaming URL for original
                    try {
                        val streamingUrl = repository.getStreamingUrl(account, bucketName, filePath)
                        _mediaUrls.value = _mediaUrls.value + (filePath to streamingUrl)
                        Log.d("GalleryViewModel", "Using streaming URL for original video: $filePath")
                        return@launch
                    } catch (e: Exception) {
                        Log.e("GalleryViewModel", "Error getting streaming URL for video: ${e.message}")
                        // Continue to standard path as fallback
                    }
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
                
                // For non-video files, download and cache in the background
                if (!repository.isVideoFile(filePath)) {
                    cacheFileFromUrl(account, bucketName, filePath)
                }
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Error getting media URL for $filePath: ${e.message}")
            }
        }
    }

    /**
     * Downloads and caches a file from GCP Storage with error handling and memory constraints
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
            
            // Skip downloading videos - they should use streaming instead
            if (repository.isVideoFile(filePath)) {
                Log.d("GalleryViewModel", "Skipping cache download for video file: $filePath")
                return
            }
            
            try {
                // Download the file content with potential size limit error handling
                val fileContent = repository.getFileContent(account, bucketName, filePath)
                
                // Get the MIME type based on file extension
                val mimeType = ThumbnailUtils.getMimeTypeFromPath(filePath)
                
                // Try to cache the file - may throw exception if too large
                try {
                    fileCache.putFile(filePath, fileContent, mimeType)
                    Log.d("GalleryViewModel", "Download completed and cached for $filePath")
                } catch (e: IOException) {
                    // If caching fails due to size, still keep the URL but don't try to cache
                    Log.w("GalleryViewModel", "File too large for cache: $filePath: ${e.message}")
                    // We don't rethrow - the URL is still valid for viewing
                }
            } catch (e: IllegalArgumentException) {
                // This is thrown when a file is too large for direct download
                Log.w("GalleryViewModel", "File too large for direct download: $filePath: ${e.message}")
                
                // For these files, use streaming URL instead
                val streamingUrl = repository.getStreamingUrl(account, bucketName, filePath)
                _mediaUrls.value = _mediaUrls.value + (filePath to streamingUrl)
                Log.d("GalleryViewModel", "Using streaming URL instead for large file: $filePath")
            }
        } catch (e: Exception) {
            // Log but don't fail the app if downloading fails
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
                    
                    // For videos, use streaming URL instead of direct download to prevent OOM
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            // Use streaming URL for videos
                            val streamingUrl = repository.getStreamingUrl(account, bucketName, item.path)
                            _mediaUrls.value = _mediaUrls.value + (item.path to streamingUrl)
                            Log.d("GalleryViewModel", "Using streaming URL for video ${item.path}")
                        } catch (e: Exception) {
                            Log.e("GalleryViewModel", "Error getting streaming URL for video: ${e.message}")
                        }
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
                    
                    // For videos, use streaming URL instead of direct download to prevent OOM
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            // Use streaming URL for videos
                            val streamingUrl = repository.getStreamingUrl(account, bucketName, item.path)
                            _mediaUrls.value = _mediaUrls.value + (item.path to streamingUrl)
                            Log.d("GalleryViewModel", "Using streaming URL for video ${item.path}")
                        } catch (e: Exception) {
                            Log.e("GalleryViewModel", "Error getting streaming URL for video: ${e.message}")
                        }
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
     * Toggles selection mode on/off
     */
    fun toggleSelectionMode(initialItem: GalleryItem? = null, account: GoogleSignInAccount? = null) {
        if (!_isInSelectionMode.value) {
            // Entering selection mode
            _isInSelectionMode.value = true
            
            // If initial item is provided, select it
            if (initialItem != null && initialItem !is GalleryItem.Folder) {
                toggleItemSelection(initialItem, account)
            }
        } else {
            // Exiting selection mode
            _isInSelectionMode.value = false
            clearSelection()
        }
    }
    
    /**
     * Toggles selection of a specific item
     */
    fun toggleItemSelection(item: GalleryItem, account: GoogleSignInAccount?) {
        // Only allow selecting files, not folders
        if (item is GalleryItem.Folder) return
        
        // Toggle selection
        val currentSelection = _selectedItems.value.toMutableSet()
        if (currentSelection.contains(item.path)) {
            currentSelection.remove(item.path)
        } else {
            currentSelection.add(item.path)
        }
        _selectedItems.value = currentSelection
        
        // Recalculate total size if needed
        if (currentSelection.isNotEmpty()) {
            updateSelectedItemsSize(account)
        } else {
            _selectedItemsSize.value = 0
            
            // If no items are selected, exit selection mode
            if (currentSelection.isEmpty()) {
                _isInSelectionMode.value = false
            }
        }
    }
    
    /**
     * Clears all selected items
     */
    fun clearSelection() {
        _selectedItems.value = emptySet()
        _selectedItemsSize.value = 0
    }
    
    /**
     * Updates the total size of all selected items
     * @param account The GoogleSignInAccount to use for API calls, or null to skip size calculation
     */
    fun updateSelectedItemsSize(account: GoogleSignInAccount?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (account == null) {
                    Log.d("GalleryViewModel", "No account provided, skipping size calculation")
                    return@launch
                }
                
                // Get direct storage manager access - without this we can't proceed
                val gcpStorageManager = storageManager ?: return@launch
                
                var totalSize = 0L
                val bucketName = repository.getCurrentBucketName()
                
                Log.d("GalleryViewModel", "Calculating total size for ${_selectedItems.value.size} selected items")
                
                // Process each selected item to get its size
                for (path in _selectedItems.value) {
                    try {
                        // Use the same approach as when downloading files
                        val size = gcpStorageManager.getFileSize(account, bucketName, path)
                        
                        if (size > 0) {
                            totalSize += size
                            Log.d("GalleryViewModel", "Size for $path: $size bytes")
                        } else {
                            Log.w("GalleryViewModel", "Got zero or negative size for $path")
                        }
                    } catch (e: Exception) {
                        // Log error but continue with other files
                        Log.e("GalleryViewModel", "Error getting size for $path: ${e.message}")
                    }
                }
                
                Log.d("GalleryViewModel", "Total size of all selected items: $totalSize bytes")
                _selectedItemsSize.value = totalSize
                
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Error calculating selected items size: ${e.message}")
                // Don't set a default size - just keep the current value
            }
        }
    }
    
    /**
     * Formats the total selected size as a human-readable string
     */
    fun getFormattedSelectedSize(): String {
        val sizeBytes = _selectedItemsSize.value
        
        // If size is not available, show a placeholder
        if (sizeBytes <= 0) {
            return "Multiple files"
        }
        
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> String.format("%.1f KB", sizeBytes / 1024f)
            sizeBytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", sizeBytes / (1024 * 1024f))
            else -> String.format("%.1f GB", sizeBytes / (1024 * 1024 * 1024f))
        }
    }
    
    /**
     * Gets a media URL for downloading a file
     * This is a suspending function that returns the URL directly
     * @param account The GoogleSignInAccount to use for authentication
     * @param bucketName The bucket name
     * @param filePath The path to the file
     * @return The authenticated URL for downloading the file, or null if it couldn't be obtained
     */
    suspend fun getMediaUrlForDownload(
        account: GoogleSignInAccount,
        bucketName: String,
        filePath: String
    ): String? {
        return try {
            // Check if it's a video file
            if (repository.isVideoFile(filePath)) {
                // For videos, use streaming URL
                repository.getStreamingUrl(account, bucketName, filePath)
            } else {
                // For other files, use regular image URL
                repository.getImageUrl(account, bucketName, filePath)
            }
        } catch (e: Exception) {
            Log.e("GalleryViewModel", "Error getting download URL for $filePath: ${e.message}")
            null
        }
    }

    /**
     * Factory for creating GalleryViewModel with the proper dependencies
     */
    class Factory(
        private val context: Context,
        private val repositoryLazy: Lazy<GalleryRepository>? = null,
        private val storageManager: GCPStorageManager? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
                val repository = if (repositoryLazy != null) {
                    repositoryLazy.value
                } else {
                    // Create a new repository if one wasn't provided
                    val authManager = GCPAuthenticationManager(context)
                    val newStorageManager = GCPStorageManager(context, authManager)
                    GalleryRepository(newStorageManager)
                }
                // Use the provided storage manager or create a new one
                val actualStorageManager = storageManager ?: if (repositoryLazy != null) {
                    // Try to extract it from repository, but don't throw an error if it fails
                    try {
                        val getStorageManagerMethod = GalleryRepository::class.java.getMethod("getStorageManager")
                        getStorageManagerMethod.invoke(repository) as? GCPStorageManager
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                return GalleryViewModel(context, repository, actualStorageManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}