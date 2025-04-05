package com.example.schmucklemierphotos.ui.gallery

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.schmucklemierphotos.model.GalleryItem
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screen for viewing media files with horizontal swiping support
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaViewerScreen(
    previewableItems: List<GalleryItem>,
    initialIndex: Int,
    mediaUrls: Map<String, String>,
    thumbnailUrls: Map<String, String>,
    imageLoader: ImageLoader,
    account: GoogleSignInAccount,
    bucketName: String,
    onNavigateToPrevious: (GoogleSignInAccount, String) -> Unit,
    onNavigateToNext: (GoogleSignInAccount, String) -> Unit,
    onPreCacheAdjacent: (GoogleSignInAccount, String) -> Unit,
    onPreCacheThumbnails: (GoogleSignInAccount, String, Int) -> Unit,
    onClose: () -> Unit
) {
    // Whether to show controls
    var showControls by remember { mutableStateOf(true) }
    
    // Coroutine scope for launching effects
    val coroutineScope = rememberCoroutineScope()
    
    // Set up logging constants
    val TAG = "MediaViewerScreen"
    
    // Set up pager state
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { previewableItems.size }
    )
    
    // Setup DisposableEffect for logging component lifecycle - only logs once
    DisposableEffect(Unit) {
        Log.d(TAG, "MediaViewerScreen composed with ${previewableItems.size} items, initialIndex=$initialIndex")
        Log.d(TAG, "PagerState initialized - currentPage: ${pagerState.currentPage}, " +
                "pageCount: ${pagerState.pageCount}")
        onDispose {
            Log.d(TAG, "MediaViewerScreen disposed")
        }
    }
    
    // Track current page for manual navigation
    var currentPageIndex by remember { mutableStateOf(initialIndex) }
    
    // Zoom and pan state
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val minScale = 1f
    val maxScale = 10f  // Increased to allow more zoom
    val zoomThreshold = 1.2f  // Allow swiping until zoomed beyond this threshold
    
    // Swipe handling state
    var swipeTotalX by remember { mutableStateOf(0f) }
    var hasSwiped by remember { mutableStateOf(false) }
    
    // Track gesture state with more detailed transition tracking
    var isGestureInProgress by remember { mutableStateOf(false) }
    var activePointerCount by remember { mutableStateOf(0) }
    
    // Create separate inputs to ensure gestures don't conflict
    val dragGestureKey = remember { Any() }
    val transformGestureKey = remember { Any() }
    
    // Reset zoom when navigating to a different image
    LaunchedEffect(currentPageIndex) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        
        // Pre-cache thumbnails for surrounding items (pre-cache 2 in each direction)
        onPreCacheThumbnails(account, bucketName, 2)
    }
    
    DisposableEffect(Unit) {
        Log.d(TAG, "Setting up gesture handler in MediaViewerScreen")
        onDispose {
            Log.d(TAG, "Disposing gesture handler in MediaViewerScreen")
        }
    }
    
    // Calculate current item for title display
    val currentItem = previewableItems.getOrNull(currentPageIndex) ?: return
    
    Scaffold(
        topBar = {
            if (showControls) {
                TopAppBar(
                    title = { Text(currentItem.getDisplayName()) },
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
                // Simple gesture handling with just two essential features:
                // 1. Two-finger pinch to zoom with pan
                // 2. Single-finger swipe for navigation (only if not zoomed)
                .pointerInput(Unit) {
                    // Handle zooming with two fingers
                    detectTransformGestures(
                        onGesture = { centroid, dragAmount, gestureZoom, gestureRotation ->
                            // Handle two-finger zoom with pan
                            if (gestureZoom != 1f) {
                                Log.d(TAG, "Zoom gesture: zoom=$gestureZoom, drag=$dragAmount")
                                
                                // Apply zoom centered on gesture centroid
                                val oldScale = scale
                                val newScale = (scale * gestureZoom).coerceIn(minScale, maxScale)
                                
                                // Calculate zoom focus point
                                val containerWidth = size.width
                                val containerHeight = size.height
                                val focusX = (centroid.x / containerWidth) * 2f - 1f
                                val focusY = (centroid.y / containerHeight) * 2f - 1f
                                
                                // Apply zoom with proper centering
                                if (newScale != oldScale) {
                                    val scaleFactor = newScale / oldScale
                                    offsetX = offsetX * scaleFactor + focusX * containerWidth * (1 - scaleFactor) / 2
                                    offsetY = offsetY * scaleFactor + focusY * containerHeight * (1 - scaleFactor) / 2
                                    scale = newScale
                                }
                                
                                // Apply pan during zoom
                                offsetX += dragAmount.x
                                offsetY += dragAmount.y
                                
                                // Constrain to image bounds
                                if (scale <= minScale) {
                                    offsetX = 0f
                                    offsetY = 0f
                                    scale = minScale
                                } else {
                                    val maxX = (size.width * (scale - 1)) / 2
                                    val maxY = (size.height * (scale - 1)) / 2
                                    offsetX = offsetX.coerceIn(-maxX, maxX)
                                    offsetY = offsetY.coerceIn(-maxY, maxY)
                                }
                            } else if (dragAmount == Offset.Zero) {
                                // Handle tap to toggle controls
                                showControls = !showControls
                            }
                        }
                    )
                }
                // Separate handler just for horizontal swipes
                .pointerInput(Unit) {
                    var totalDragX = 0f
                    
                    detectHorizontalDragGestures(
                        onDragStart = { 
                            totalDragX = 0f
                            Log.d(TAG, "Horizontal drag started")
                        },
                        onDragEnd = {
                            Log.d(TAG, "Horizontal drag ended, distance: $totalDragX")
                            
                            // Only navigate if not zoomed in
                            if (scale <= zoomThreshold) {
                                // Require minimum distance to trigger navigation
                                val threshold = 80f
                                
                                if (Math.abs(totalDragX) > threshold) {
                                    if (totalDragX > 0) {
                                        // RIGHT swipe - previous
                                        Log.d(TAG, "Navigating to previous item")
                                        if (currentPageIndex > 0) {
                                            currentPageIndex--
                                        } else {
                                            currentPageIndex = previewableItems.size - 1
                                        }
                                        onNavigateToPrevious(account, bucketName)
                                    } else {
                                        // LEFT swipe - next
                                        Log.d(TAG, "Navigating to next item")
                                        if (currentPageIndex < previewableItems.size - 1) {
                                            currentPageIndex++
                                        } else {
                                            currentPageIndex = 0
                                        }
                                        onNavigateToNext(account, bucketName)
                                    }
                                    
                                    // Priority pre-caching: load thumbnails first in a separate coroutine
                                    coroutineScope.launch(Dispatchers.IO) {
                                        // First pre-cache thumbnails as they load faster
                                        onPreCacheThumbnails(account, bucketName, 2)
                                    }
                                    
                                    // Then launch another coroutine for full-size images (lower priority)
                                    coroutineScope.launch(Dispatchers.IO) {
                                        // Small delay to ensure thumbnails get priority
                                        delay(100)
                                        // Then pre-cache the full-size images
                                        onPreCacheAdjacent(account, bucketName)
                                    }
                                }
                            }
                            
                            totalDragX = 0f
                        },
                        onDragCancel = {
                            totalDragX = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            
                            // Only accumulate drag if not zoomed in
                            if (scale <= zoomThreshold) {
                                totalDragX += dragAmount
                                Log.d(TAG, "Horizontal drag amount: $dragAmount, total: $totalDragX")
                            }
                        }
                    )
                }
        ) {
            Log.d(TAG, "Rendering Box content with ${previewableItems.size} items")
            
            // Get the current item using our local page index
            val currentMediaItem = previewableItems.getOrNull(currentPageIndex) ?: previewableItems.first()
            val mediaUrl = mediaUrls[currentMediaItem.path]
            
            // Just render the current item directly - we handle swipes with gesture detection
            when (currentMediaItem) {
                is GalleryItem.ImageFile -> {
                    Log.d(TAG, "Rendering ImageContent for ${currentMediaItem.path}")
                    ImageContent(
                        image = currentMediaItem,
                        imageUrl = mediaUrl,
                        thumbnailUrl = thumbnailUrls[currentMediaItem.path],
                        imageLoader = imageLoader,
                        scale = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        onToggleControls = { showControls = !showControls }
                    )
                }
                is GalleryItem.VideoFile -> {
                    Log.d(TAG, "Rendering VideoContent for ${currentMediaItem.path}")
                    VideoContent(
                        video = currentMediaItem,
                        videoUrl = mediaUrl
                    )
                }
                else -> {
                    Log.d(TAG, "Item not previewable: ${currentMediaItem.path}")
                }
            }
        }
    }
}