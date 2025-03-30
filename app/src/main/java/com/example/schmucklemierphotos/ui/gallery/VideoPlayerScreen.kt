package com.example.schmucklemierphotos.ui.gallery

import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.schmucklemierphotos.model.GalleryItem

/**
 * Screen for playing a video file
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    video: GalleryItem.VideoFile,
    videoUrl: String?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(video.getDisplayName()) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                        prepare()
                        playWhenReady = true
                        
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                when (state) {
                                    Player.STATE_BUFFERING -> isLoading = true
                                    Player.STATE_READY -> isLoading = false
                                    Player.STATE_ENDED -> { /* Handle playback ended */ }
                                    Player.STATE_IDLE -> { /* Handle idle state */ }
                                }
                            }
                            
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                isLoading = false
                                errorMessage = error.message ?: "Unknown error"
                            }
                        })
                    }
                }
                
                // Clean up resources when leaving the screen
                DisposableEffect(videoUrl) {
                    onDispose {
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
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}