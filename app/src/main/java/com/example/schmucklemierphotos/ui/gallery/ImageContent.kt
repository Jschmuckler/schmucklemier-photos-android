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

/**
 * Image content with externally controlled zoom and pan support
 */
@Composable
fun ImageContent(
    image: GalleryItem.ImageFile,
    imageUrl: String?,
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
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Loading indicator
        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        
        // Error message
        if (hasError) {
            Text(
                text = "Failed to load image",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        
        // Image with zoom and pan
        imageUrl?.let { url ->
            val context = LocalContext.current
            
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
                    ),
                onLoading = { 
                    isLoading = true
                    Log.d(TAG, "Image loading started: ${image.path}")
                },
                onSuccess = { 
                    isLoading = false
                    Log.d(TAG, "Image loaded successfully: ${image.path}")
                },
                onError = { 
                    isLoading = false
                    hasError = true
                    Log.d(TAG, "Image failed to load: ${image.path}")
                }
            )
        }
    }
}