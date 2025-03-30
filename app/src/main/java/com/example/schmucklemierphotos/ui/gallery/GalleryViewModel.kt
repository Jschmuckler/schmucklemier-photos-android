package com.example.schmucklemierphotos.ui.gallery

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for managing the gallery state and operations
 */
class GalleryViewModel(
    private val context: Context,
    private val repository: GalleryRepository
) : ViewModel() {

    // File cache for caching files locally
    private val fileCache = FileCache(context)

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
                // Handle error
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
        try {
            // Download the file content
            val fileContent = repository.getFileContent(account, bucketName, filePath)
            
            // Get the MIME type based on file extension
            val mimeType = when {
                filePath.endsWith(".jpg", ignoreCase = true) 
                    || filePath.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                filePath.endsWith(".png", ignoreCase = true) -> "image/png"
                filePath.endsWith(".gif", ignoreCase = true) -> "image/gif"
                filePath.endsWith(".webp", ignoreCase = true) -> "image/webp"
                filePath.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
                filePath.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
                filePath.endsWith(".webm", ignoreCase = true) -> "video/webm"
                else -> null
            }
            
            // Cache the file
            fileCache.putFile(filePath, fileContent, mimeType)
        } catch (e: Exception) {
            // Log but don't fail the app if caching fails
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
     * Pre-caches adjacent media items
     */
    fun preCacheAdjacentItems(account: GoogleSignInAccount, bucketName: String) {
        viewModelScope.launch {
            val currentPos = _currentPreviewPosition.value
            val items = _previewableItems.value
            
            if (items.isEmpty() || currentPos < 0 || currentPos >= items.size) return@launch
            
            // Get indices of adjacent items, wrapping around if needed
            val prevIndex = if (currentPos > 0) currentPos - 1 else items.size - 1
            val nextIndex = if (currentPos < items.size - 1) currentPos + 1 else 0
            
            // Only pre-cache images, not videos, as specified in the requirements
            listOf(prevIndex, nextIndex).forEach { index ->
                val item = items[index]
                if (item is GalleryItem.ImageFile && !_mediaUrls.value.containsKey(item.path)) {
                    getMediaUrl(account, bucketName, item.path)
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
                    getMediaUrl(account, bucketName, item.path)
                    preCacheAdjacentItems(account, bucketName)
                }
                is GalleryItem.VideoFile -> {
                    _selectedImage.value = null
                    _selectedVideo.value = item
                    getMediaUrl(account, bucketName, item.path)
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
                    getMediaUrl(account, bucketName, item.path)
                    preCacheAdjacentItems(account, bucketName)
                }
                is GalleryItem.VideoFile -> {
                    _selectedImage.value = null
                    _selectedVideo.value = item
                    getMediaUrl(account, bucketName, item.path)
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