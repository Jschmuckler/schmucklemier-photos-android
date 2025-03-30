package com.example.schmucklemierphotos.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
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
 * Base gallery card component that displays an item in the gallery
 * @param item The gallery item to display
 * @param onClick Callback for when the card is clicked
 */
@Composable
fun GalleryCard(
    item: GalleryItem,
    imageLoader: ImageLoader? = null,
    onClick: () -> Unit
) {
    when (item) {
        is GalleryItem.Folder -> FolderCard(folder = item, onClick = onClick)
        is GalleryItem.ImageFile -> ImageCard(image = item, imageLoader = imageLoader, onClick = onClick)
        is GalleryItem.VideoFile -> FileCard(
            file = item,
            icon = Icons.Default.Movie,
            iconTint = Color(0xFF5C6BC0),
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
fun ImageCard(image: GalleryItem.ImageFile, imageLoader: ImageLoader?, onClick: () -> Unit) {
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
                    val imageUrl = image.thumbnailPath ?: image.path
                    
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUrl)
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
                            isLoading = false
                            hasError = true
                        }
                    )
                    
                    // Fallback icon if error
                    if (hasError) {
                        Icon(
                            imageVector = Icons.Default.InsertDriveFile,
                            contentDescription = "Image file",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
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
                Icon(
                    imageVector = icon,
                    contentDescription = "File",
                    modifier = Modifier.size(64.dp),
                    tint = iconTint
                )
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