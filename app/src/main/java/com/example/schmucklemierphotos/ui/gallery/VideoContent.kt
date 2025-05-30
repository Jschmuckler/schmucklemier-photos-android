package com.example.schmucklemierphotos.ui.gallery

import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.schmucklemierphotos.model.GalleryItem
import kotlinx.coroutines.delay

/**
 * Video content with ExoPlayer using streaming to prevent OOM errors
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoContent(
    video: GalleryItem.VideoFile,
    videoUrl: String?
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Track if we've attempted to recover from errors
    var hasAttemptedRecovery by remember { mutableStateOf(false) }
    
    // Effect to handle error recovery outside the listener
    LaunchedEffect(hasAttemptedRecovery, errorMessage) {
        if (hasAttemptedRecovery && errorMessage != null && videoUrl != null) {
            delay(500)
            errorMessage = "Attempting to recover playback..."
            
            // Get a reference to the current player
            val currentPlayer = (0 until 3).fold(null as ExoPlayer?) { _, _ ->
                try {
                    // Create a new player with more conservative settings
                    val recoveryPlayer = ExoPlayer.Builder(context)
                        .setLoadControl(
                            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                                .setBufferDurationsMs(5000, 15000, 1000, 1000)
                                .setPrioritizeTimeOverSizeThresholds(true)
                                .build()
                        )
                        .build()
                    
                    // Configure with minimal settings
                    recoveryPlayer.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                    
                    // Use track selection to limit quality
                    recoveryPlayer.trackSelectionParameters = recoveryPlayer.trackSelectionParameters
                        .buildUpon()
                        .setMaxVideoSizeSd()
                        .setPreferredVideoMimeType("video/mp4")
                        .build()
                    
                    // Try to load the media
                    recoveryPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                    recoveryPlayer.prepare()
                    recoveryPlayer.playWhenReady = true
                    
                    // If we got here without exception, return the player
                    recoveryPlayer
                } catch (e: Exception) {
                    null
                }
            }
            
            if (currentPlayer != null) {
                errorMessage = null
            } else {
                errorMessage = "Could not recover playback. Try a smaller video."
                hasAttemptedRecovery = false // Reset to allow another attempt
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Show loading indicator if the video is not ready
        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        
        // Show error message if there's an error
        if (errorMessage != null) {
            Text(
                text = "Error: $errorMessage",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        
        // Player view
        if (videoUrl != null) {
            val player = remember {
                ExoPlayer.Builder(context)
                    .setLoadControl(
                        androidx.media3.exoplayer.DefaultLoadControl.Builder()
                            // Use smaller buffer sizes to reduce memory usage
                            .setBufferDurationsMs(
                                10000,   // Min buffer ms
                                30000,   // Max buffer ms (reduced from default)
                                1000,    // Buffer for playback ms
                                2000     // Buffer for rebuffering ms
                            )
                            // Limit the size of memory allocation for video decoding
                            .setPrioritizeTimeOverSizeThresholds(true)
                            .build()
                    )
                    .build().apply {
                        // Set error handling to be more robust
                        setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                        // Set minimal video surface when preparing to reduce memory usage
                        setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                        prepare()
                        playWhenReady = true
                        
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                when (state) {
                                    Player.STATE_BUFFERING -> isLoading = true
                                    Player.STATE_READY -> {
                                        isLoading = false
                                        // Reset recovery flag when successfully playing
                                        hasAttemptedRecovery = false
                                    }
                                    Player.STATE_ENDED -> { /* Handle playback ended */ }
                                    Player.STATE_IDLE -> { /* Handle idle state */ }
                                }
                            }
                            
                            override fun onPlayerError(error: PlaybackException) {
                                isLoading = false
                                errorMessage = "Playback error: ${error.message ?: "Unknown error"}"
                                
                                // Handle recovery in a separate variable update
                                if (!hasAttemptedRecovery) {
                                    hasAttemptedRecovery = true
                                }
                            }
                        })
                    }
            }
            
            // Clean up resources when leaving the screen
            DisposableEffect(videoUrl) {
                onDispose {
                    player.clearVideoSurface() // Clear surface before release
                    player.release()
                }
            }
            
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        
                        // Use more memory-efficient surface type
                        setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
                        
                        // Reduce texture view resolution for very large videos
                        if (video.size > 50 * 1024 * 1024) { // For videos > 50MB
                            player.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}