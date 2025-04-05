package com.example.schmucklemierphotos.ui.gallery

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.schmucklemierphotos.model.GalleryItem

/**
 * Utility function to generate a thumbnail path from an original file path
 * Thumbnail will be in the same subfolder under a THUMBS directory with same name but webp extension
 */
private fun generateThumbnailPath(originalPath: String): String {
    val path = originalPath.trim() 
    if (path.isEmpty()) return ""
    
    return try {
        val lastSlashIndex = path.lastIndexOf('/')
        if (lastSlashIndex == -1) {
            // No slashes, it's in the root folder
            "THUMBS/${path.substringBeforeLast('.', path)}.webp"
        } else {
            // Extract directory path and filename
            val dir = path.substring(0, lastSlashIndex)
            val filename = path.substring(lastSlashIndex + 1)
            "$dir/THUMBS/${filename.substringBeforeLast('.', filename)}.webp"
        }
    } catch (e: Exception) {
        // Fallback if there's an error in the path processing
        "$originalPath.thumb.webp"
    }
}

/**
 * Base gallery card component that displays an item in the gallery
 * @param item The gallery item to display
 * @param onClick Callback for when the card is clicked
 */
@Composable
fun GalleryCard(
    item: GalleryItem,
    imageLoader: ImageLoader? = null,
    thumbnailUrl: String? = null,
    onClick: () -> Unit
) {
    when (item) {
        is GalleryItem.Folder -> FolderCard(folder = item, onClick = onClick)
        is GalleryItem.ImageFile -> ImageCard(
            image = item, 
            imageLoader = imageLoader, 
            thumbnailUrl = thumbnailUrl,
            onClick = onClick
        )
        is GalleryItem.VideoFile -> FileCard(
            file = item,
            icon = Icons.Default.Movie,
            iconTint = Color(0xFF5C6BC0),
            imageLoader = imageLoader,
            thumbnailUrl = thumbnailUrl,
            onClick = onClick
        )
        is GalleryItem.DocumentFile -> FileCard(
            file = item,
            icon = Icons.Default.PictureAsPdf,
            iconTint = Color(0xFFEF5350),
            onClick = onClick
        )
        is GalleryItem.OtherFile -> FileCard(
            file = item,
            icon = Icons.Default.InsertDriveFile,
            iconTint = Color(0xFF78909C),
            onClick = onClick
        )
    }
}

/**
 * Card component for displaying a folder
 */
@Composable
fun FolderCard(folder: GalleryItem.Folder, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Folder",
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            Text(
                text = folder.getDisplayName(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            )
        }
    }
}

/**
 * Card component for displaying an image
 */
@Composable
fun ImageCard(
    image: GalleryItem.ImageFile, 
    imageLoader: ImageLoader?, 
    thumbnailUrl: String? = null,
    onClick: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
                
                // Image will only be loaded when scrolled into view
                if (imageLoader != null) {
                    val context = LocalContext.current
                    
                    // Use the thumbnail URL from the ViewModel if available, otherwise we'll show the fallback icon
                    // This URL is either a direct GCP Storage authenticated URL or a local file:// URI if cached
                    
                    // If no thumbnail URL or failed to load, show icon
                    if (thumbnailUrl == null || hasError) {
                        Icon(
                            imageVector = Icons.Default.InsertDriveFile,
                            contentDescription = "Image file",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        // Otherwise try to load the thumbnail
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(thumbnailUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Image",
                            imageLoader = imageLoader,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            onLoading = { isLoading = true },
                            onSuccess = { isLoading = false },
                            onError = { 
                                // If thumbnail loading failed, show the icon
                                isLoading = false
                                hasError = true
                            }
                        )
                    }
                } else {
                    // No imageLoader, show icon
                    Icon(
                        imageVector = Icons.Default.InsertDriveFile,
                        contentDescription = "Image file",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Text(
                text = image.getDisplayName(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }
}

/**
 * Card component for displaying a generic file
 */
@Composable
fun FileCard(
    file: GalleryItem.File,
    icon: ImageVector,
    iconTint: Color,
    imageLoader: ImageLoader? = null,
    thumbnailUrl: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                // For videos, try to load thumbnail if available
                if (file is GalleryItem.VideoFile && imageLoader != null) {
                    var isLoading by remember { mutableStateOf(true) }
                    var hasError by remember { mutableStateOf(false) }
                    val context = LocalContext.current
                    
                    // Use the thumbnail URL from the ViewModel if available
                    // This is much more reliable than trying to generate the path ourselves
                    
                    // If no thumbnail URL or failed to load, show icon
                    if (thumbnailUrl == null || hasError) {
                        Icon(
                            imageVector = icon,
                            contentDescription = "File",
                            modifier = Modifier.size(64.dp),
                            tint = iconTint
                        )
                    } else {
                        // Track if thumbnail loaded successfully
                        var thumbnailLoaded by remember { mutableStateOf(false) }
                        
                        // Box contains the image and play button overlay
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(thumbnailUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Video Thumbnail",
                                imageLoader = imageLoader,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                onLoading = { isLoading = true },
                                onSuccess = { 
                                    isLoading = false
                                    thumbnailLoaded = true
                                },
                                onError = { 
                                    isLoading = false
                                    hasError = true 
                                }
                            )
                            
                            // Show play button overlay when thumbnail loads successfully
                            if (thumbnailLoaded) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = "Play Video",
                                    modifier = Modifier
                                        .size(48.dp)
                                        .align(Alignment.Center),
                                    tint = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                } else {
                    // Regular icon for other file types
                    Icon(
                        imageVector = icon,
                        contentDescription = "File",
                        modifier = Modifier.size(64.dp),
                        tint = iconTint
                    )
                }
            }
            
            Text(
                text = file.getDisplayName(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }
}