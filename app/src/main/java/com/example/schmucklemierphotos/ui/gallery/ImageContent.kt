package com.example.schmucklemierphotos.ui.gallery

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.schmucklemierphotos.model.GalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Image content with externally controlled zoom and pan support
 */
@Composable
fun ImageContent(
    image: GalleryItem.ImageFile,
    imageUrl: String?,
    thumbnailUrl: String? = null,
    imageLoader: ImageLoader,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    onToggleControls: () -> Unit
) {
    // Debug constants
    val TAG = "ImageContent"
    
    // Log when the ImageContent is created
    DisposableEffect(image.path) {
        Log.d(TAG, "ImageContent created for ${image.path}")
        onDispose {
            Log.d(TAG, "ImageContent disposed for ${image.path}")
        }
    }
    
    // Local state for loading and errors
    var isFullImageLoading by remember { mutableStateOf(true) }
    var isThumbnailLoading by remember(thumbnailUrl) { mutableStateOf(thumbnailUrl != null) }
    var hasError by remember { mutableStateOf(false) }
    var thumbnailError by remember { mutableStateOf(false) }
    
    // Track if the full-size image is ready to be displayed
    var fullImageReady by remember { mutableStateOf(false) }
    
    // Track if the thumbnail is visible and ready
    var thumbnailVisible by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleControls() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val context = LocalContext.current
        
        // Error message - only show if we have no thumbnail and full image failed
        if (hasError && (thumbnailUrl == null || thumbnailError)) {
            Text(
                text = "Failed to load image",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        
        // ALWAYS show thumbnail first (even if we have the full image cached)
        // This provides immediate visual feedback while the full image loads
        if (thumbnailUrl != null) {
            Log.d(TAG, "Setting up thumbnail for: ${image.path}")
            
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "${image.getDisplayName()} (thumbnail)",
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                onLoading = {
                    isThumbnailLoading = true
                    Log.d(TAG, "Thumbnail loading started: ${image.path}")
                },
                onSuccess = {
                    isThumbnailLoading = false
                    thumbnailVisible = true
                    thumbnailError = false
                    Log.d(TAG, "Thumbnail loaded successfully: ${image.path}")
                },
                onError = { 
                    isThumbnailLoading = false
                    thumbnailError = true
                    Log.d(TAG, "Thumbnail failed to load: ${image.path}")
                },
                alpha = if (fullImageReady) 0f else 1f // Hide thumbnail when full image is ready
            )
        }
        
        // Show loading indicator when both thumbnail and full image are still loading
        val shouldShowLoading = isFullImageLoading && 
            (thumbnailUrl == null || thumbnailError || (isThumbnailLoading && !thumbnailVisible))
            
        if (shouldShowLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        
        // Full size image with zoom and pan - only show once loaded
        // This way the thumbnail remains until the full-size image is ready
        imageUrl?.let { url ->
            // Only show the full-size image once it's fully loaded
            // This ensures we always have something visible and transition smoothly
            if (fullImageReady) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(url)
                        .crossfade(true)
                        .build(),
                    contentDescription = image.getDisplayName(),
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
            
            // Always start loading the full image immediately, but don't show it yet
            LaunchedEffect(url) {
                Log.d(TAG, "Preloading full image: ${image.path}")
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .build()
                
                // Load the image in the background
                withContext(Dispatchers.IO) {
                    isFullImageLoading = true
                    try {
                        // Use Coil to preload the image
                        val disposable = imageLoader.enqueue(request)
                        disposable.job.await()
                        
                        // If thumbnail is visible and was successfully loaded, add a small
                        // delay before showing full image to ensure smooth transition
                        if (thumbnailVisible && !thumbnailError) {
                            // Short delay to ensure UI is responsive and thumbnail is fully rendered
                            kotlinx.coroutines.delay(200)
                        }
                        
                        // Mark full image as ready to be displayed
                        isFullImageLoading = false
                        fullImageReady = true
                        Log.d(TAG, "Full image loaded successfully: ${image.path}")
                    } catch (e: Exception) {
                        isFullImageLoading = false
                        
                        // Only set error if we don't have a working thumbnail
                        if (thumbnailUrl == null || thumbnailError) {
                            hasError = true
                        }
                        
                        Log.d(TAG, "Full image failed to load: ${image.path}, error: ${e.message}")
                    }
                }
            }
        }
    }
}