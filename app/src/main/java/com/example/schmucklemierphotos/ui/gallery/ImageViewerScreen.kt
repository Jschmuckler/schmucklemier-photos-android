package com.example.schmucklemierphotos.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.schmucklemierphotos.model.GalleryItem
import kotlin.math.max
import kotlin.math.min

/**
 * Screen for viewing a single image with zoom controls
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(
    image: GalleryItem.ImageFile,
    imageUrl: String?,
    imageLoader: ImageLoader,
    onClose: () -> Unit
) {
    // State for zooming and panning
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    
    // State for gesture detection
    var minScale by remember { mutableFloatStateOf(1f) }
    var maxScale by remember { mutableFloatStateOf(5f) }
    
    // Whether to show controls
    var showControls by remember { mutableStateOf(true) }
    
    LaunchedEffect(imageUrl) {
        // Reset zoom when image changes
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }
    
    Scaffold(
        topBar = {
            if (showControls) {
                TopAppBar(
                    title = { Text(image.getDisplayName()) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close"
                            )
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures(
                        onGesture = { centroid, pan, gestureZoom, _ ->
                            // Toggle controls on tap (when no zoom)
                            if (gestureZoom == 1f && pan.x == 0f && pan.y == 0f) {
                                showControls = !showControls
                                return@detectTransformGestures
                            }
                            
                            // Apply zoom centered on gesture centroid
                            val oldScale = scale
                            val newScale = (scale * gestureZoom).coerceIn(minScale, maxScale)
                            
                            // Update offset for zoom centered at centroid
                            if (gestureZoom != 1f) {
                                val scaleFactor = newScale / oldScale
                                val centroidX = centroid.x
                                val centroidY = centroid.y
                                
                                offsetX = (offsetX + centroidX) * scaleFactor - centroidX
                                offsetY = (offsetY + centroidY) * scaleFactor - centroidY
                            }
                            
                            // Add pan offset
                            offsetX += pan.x
                            offsetY += pan.y
                            
                            // Apply the new scale
                            scale = newScale
                            
                            // If zoomed out fully, reset position
                            if (scale <= minScale) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                // Constrain to image bounds
                                val maxX = (size.width * (scale - 1)) / 2
                                val maxY = (size.height * (scale - 1)) / 2
                                
                                offsetX = offsetX.coerceIn(-maxX, maxX)
                                offsetY = offsetY.coerceIn(-maxY, maxY)
                            }
                        }
                    )
                },
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
                    onLoading = { isLoading = true },
                    onSuccess = { isLoading = false },
                    onError = { 
                        isLoading = false
                        hasError = true
                    }
                )
            }
        }
    }
}