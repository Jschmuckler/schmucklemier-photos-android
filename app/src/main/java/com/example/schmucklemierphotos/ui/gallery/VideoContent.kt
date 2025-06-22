package com.example.schmucklemierphotos.ui.gallery

import android.net.Uri
import android.util.Log
import android.os.Handler
import android.os.Looper
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import com.example.schmucklemierphotos.model.GalleryItem
import com.example.schmucklemierphotos.utils.ThumbnailUtils
import kotlinx.coroutines.delay

/**
 * Video content with ExoPlayer using streaming to prevent OOM errors
 * Modified to support swipe gestures from parent containers and handle various video formats
 */

/**
 * Helper function to determine video format information based on file extension and MIME type
 * @param videoUrl The URL of the video
 * @param existingMimeType Any existing MIME type information from the file metadata
 * @return Pair of (explicit MIME type to set, preferred MIME type for track selection)
 */
private fun getVideoFormatInfo(videoUrl: String?, existingMimeType: String?): Pair<String?, String?> {
    // First check if we have a valid URL
    if (videoUrl == null) return Pair(null, null)
    
    // Determine format from URL extension and existing MIME type
    return when {
        // MPEG-TS formats
        videoUrl.endsWith(".ts", ignoreCase = true) || 
        videoUrl.contains(".ts?", ignoreCase = true) ||
        videoUrl.endsWith(".m2ts", ignoreCase = true) ||
        videoUrl.endsWith(".mts", ignoreCase = true) ||
        existingMimeType?.contains("video/mp2t", ignoreCase = true) == true -> {
            Pair("video/mp2t", "video/mp2t")
        }
        
        // WebP video (animated WebP)
        videoUrl.endsWith(".webp", ignoreCase = true) ||
        existingMimeType?.contains("image/webp", ignoreCase = true) == true -> {
            Pair("image/webp", null) // No preferred track type for WebP
        }
        
        // MP4 formats
        videoUrl.endsWith(".mp4", ignoreCase = true) ||
        existingMimeType?.contains("video/mp4", ignoreCase = true) == true -> {
            Pair(null, "video/avc") // Prefer H.264/AVC for MP4
        }
        
        // WebM formats
        videoUrl.endsWith(".webm", ignoreCase = true) ||
        existingMimeType?.contains("video/webm", ignoreCase = true) == true -> {
            Pair(null, "video/vp9") // Prefer VP9 for WebM
        }
        
        // MOV formats
        videoUrl.endsWith(".mov", ignoreCase = true) ||
        existingMimeType?.contains("video/quicktime", ignoreCase = true) == true -> {
            Pair("video/quicktime", "video/avc") // Prefer H.264 for QuickTime
        }
        
        // For all other formats, use ThumbnailUtils to determine MIME type or fallback to null
        else -> {
            val mimeType = ThumbnailUtils.getMimeTypeFromPath(videoUrl)
            Pair(mimeType, null)
        }
    }
}
@OptIn(UnstableApi::class)
@Composable
fun VideoContent(
    video: GalleryItem.VideoFile,
    videoUrl: String?,
    onToggleControls: () -> Unit = {}
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
    
    // Use a simpler box without any gesture handling to let parent handle gestures
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
                // Determine video format for player configuration
                val (mimeType, _) = getVideoFormatInfo(videoUrl, video.mimeType)
                val isMpegTs = mimeType == "video/mp2t"
                
                // Configure the ExoPlayer with format-specific settings
                ExoPlayer.Builder(context)
                    // Configure for specialized format support
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(context).apply {
                            // Use streaming-optimized settings for MPEG-TS
                            if (isMpegTs) {
                                setLiveTargetOffsetMs(5000)
                            }
                        }
                    )
                    .setLoadControl(
                        androidx.media3.exoplayer.DefaultLoadControl.Builder()
                            // Adjust buffer sizes based on format
                            .setBufferDurationsMs(
                                if (isMpegTs) 5000 else 10000,   // Min buffer ms (smaller for TS)
                                if (isMpegTs) 15000 else 30000,  // Max buffer ms (smaller for TS)
                                1000,    // Buffer for playback ms
                                2000     // Buffer for rebuffering ms
                            )
                            // Prioritize memory efficiency for all formats
                            .setPrioritizeTimeOverSizeThresholds(true)
                            // Set smaller back buffer for MPEG-TS to reduce memory usage
                            .setBackBuffer(if (isMpegTs) 5000 else 30000, false)
                            .build()
                    )
                    .build().apply {
                        // Determine video format and set appropriate MIME type
                        val (mimeType, preferredMimeType) = getVideoFormatInfo(videoUrl, video.mimeType)
                        
                        // Create the appropriate media item with explicit format info when needed
                        val mediaItem = if (mimeType != null) {
                            // For specialized formats, use explicit configuration
                            MediaItem.Builder()
                                .setUri(Uri.parse(videoUrl))
                                .setMimeType(mimeType)
                                .build()
                        } else {
                            // For standard formats, use default configuration
                            MediaItem.fromUri(Uri.parse(videoUrl))
                        }
                        
                        // Set preferred video format for track selection if available
                        if (preferredMimeType != null) {
                            setTrackSelectionParameters(
                                trackSelectionParameters.buildUpon()
                                    .setPreferredVideoMimeType(preferredMimeType)
                                    .build()
                            )
                        }
                        
                        // Set the configured media item
                        setMediaItem(mediaItem)
                        
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
            
            // Standard cleanup for all video formats
            DisposableEffect(videoUrl) {
                onDispose {
                    try {
                        // Consistent cleanup process for all video formats
                        player.stop()
                        player.clearVideoSurface()
                        player.clearMediaItems()
                        player.release()
                    } catch (e: Exception) {
                        Log.e("VideoContent", "Error releasing player: ${e.message}")
                    }
                }
            }
            
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        
                        // Make sure we don't intercept swipe gestures
                        controllerHideOnTouch = true
                        
                        // Set up tap detection to toggle controls
                        setOnClickListener {
                             onToggleControls()
                        }
                        
                        // CRITICAL: Don't intercept swipe events
                        // This allows parent gesture detectors to handle horizontal swipes
                        // while still allowing the player to handle tap events
                        setOnTouchListener { _, event -> 
                            // Only handle click events, let swipes pass through
                            // Return false to allow parent swipe handlers to work
                            false
                        }
                        
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