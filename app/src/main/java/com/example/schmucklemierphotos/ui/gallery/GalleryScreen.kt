package com.example.schmucklemierphotos.ui.gallery

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.schmucklemierphotos.model.GalleryItem
import com.example.schmucklemierphotos.model.GalleryRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

private const val TAG = "GalleryScreen"

/**
 * Main gallery screen that displays a grid of gallery items
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    galleryRepository: GalleryRepository,
    galleryViewModel: GalleryViewModel,
    account: GoogleSignInAccount,
    bucketName: String,
    imageLoader: ImageLoader,
    onNavigateToImage: (GalleryItem.ImageFile) -> Unit,
    onNavigateToVideo: (GalleryItem.VideoFile) -> Unit,
    onNavigateToDocument: (GalleryItem.DocumentFile) -> Unit,
    onLogout: () -> Unit
) {
    val items by galleryRepository.galleryItems.collectAsState()
    val currentPath by galleryRepository.currentPath.collectAsState()
    val isLoading by galleryRepository.isLoading.collectAsState()
    val error by galleryRepository.error.collectAsState()
    val navState by galleryViewModel.currentNavState.collectAsState()
    
    // Remember grid state to restore scroll position
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = navState?.firstVisibleItemIndex ?: 0,
        initialFirstVisibleItemScrollOffset = navState?.firstVisibleItemOffset ?: 0
    )
    
    // Menu state
    var showMenu by remember { mutableStateOf(false) }
    
    // Title based on current path
    val title = remember(currentPath) {
        if (currentPath.isEmpty()) {
            "Gallery" // Root directory
        } else {
            // Get the last part of the path
            currentPath.trimEnd('/').substringAfterLast('/')
        }
    }
    
    // Handle system back button
    BackHandler(enabled = true) {
        if (!galleryViewModel.navigateBack()) {
            // We're at the root level, can't go back further
            // Could show a confirmation dialog to exit the app
        }
    }
    
    // Update scroll position when items or visible items change
    LaunchedEffect(items, gridState.firstVisibleItemIndex) {
        if (items.isNotEmpty() && !isLoading) {
            galleryViewModel.updateScrollPosition(gridState)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = {
                        // Navigate back using history, will go to root if at first entry
                        galleryViewModel.navigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                actions = {
                    // Refresh button
                    IconButton(onClick = {
                        galleryRepository.refreshCurrentPath()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                    
                    // Menu button with logout option
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu"
                        )
                    }
                    
                    // Dropdown menu
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Logout") },
                            onClick = {
                                showMenu = false
                                onLogout()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Logout,
                                    contentDescription = null
                                )
                            }
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
        ) {
            when {
                // Show loading indicator
                isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading gallery items...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
                
                // Show error message
                error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Error loading gallery items",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = error ?: "Unknown error",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Button(
                            onClick = {
                                galleryRepository.refreshCurrentPath()
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Retry")
                        }
                    }
                }
                
                // Show empty state
                items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No items found in this folder",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                // Show gallery grid
                else -> {
                    Log.d(TAG, "Rendering gallery grid with ${items.size} items - Folders: ${items.count { it is GalleryItem.Folder }}, Files: ${items.count { it !is GalleryItem.Folder }}")
                    GalleryGrid(
                        items = items,
                        gridState = gridState,
                        imageLoader = imageLoader,
                        onFolderClick = { folder ->
                            Log.d(TAG, "Navigating to folder: ${folder.path}")
                            galleryViewModel.navigateToPath(folder.path, gridState)
                        },
                        onImageClick = onNavigateToImage,
                        onVideoClick = onNavigateToVideo,
                        onDocumentClick = onNavigateToDocument
                    )
                }
            }
        }
    }
    
    // Load initial gallery items when composable is first created or path changes
    LaunchedEffect(key1 = currentPath) {
        // Use the ViewModel to load items, which properly wraps the repository's suspend call
        galleryViewModel.loadGalleryItems(account, bucketName, currentPath)
    }
    
    // Initialize navigation history and load data if needed
    LaunchedEffect(Unit) {
        Log.d(TAG, "Initializing gallery with currentPath='$currentPath'")
        if (currentPath.isEmpty() || navState == null) {
            Log.d(TAG, "Initial load of gallery items for path='$currentPath'")
            galleryViewModel.loadGalleryItems(account, bucketName, currentPath)
        }
        if (navState == null && currentPath.isNotEmpty()) {
            galleryViewModel.navigateToPath(currentPath)
        }
    }
    
    // Clean up when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            // Save final scroll state
            galleryViewModel.updateScrollPosition(gridState)
        }
    }
}

/**
 * Grid display of gallery items
 */
@Composable
private fun GalleryGrid(
    items: List<GalleryItem>,
    gridState: LazyGridState,
    imageLoader: ImageLoader,
    onFolderClick: (GalleryItem.Folder) -> Unit,
    onImageClick: (GalleryItem.ImageFile) -> Unit,
    onVideoClick: (GalleryItem.VideoFile) -> Unit,
    onDocumentClick: (GalleryItem.DocumentFile) -> Unit
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 160.dp), // Adaptive layout for different screen sizes
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items) { item ->
            GalleryCard(
                item = item,
                imageLoader = imageLoader,
                onClick = {
                    when (item) {
                        is GalleryItem.Folder -> onFolderClick(item)
                        is GalleryItem.ImageFile -> onImageClick(item)
                        is GalleryItem.VideoFile -> onVideoClick(item)
                        is GalleryItem.DocumentFile -> onDocumentClick(item)
                        is GalleryItem.OtherFile -> {
                            // Other file types not handled directly
                            Log.d(TAG, "Selected file: ${item.path}")
                        }
                    }
                }
            )
        }
    }
}