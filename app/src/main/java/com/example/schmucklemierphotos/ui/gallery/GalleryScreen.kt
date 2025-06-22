package com.example.schmucklemierphotos.ui.gallery

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.schmucklemierphotos.model.GalleryItem
import com.example.schmucklemierphotos.model.GalleryRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

// These will be initialized after all imports are loaded
val LocalGalleryViewModel = compositionLocalOf<GalleryViewModel> { error("No GalleryViewModel provided") }
val LocalGoogleAccount = compositionLocalOf<Any> { error("No GoogleSignInAccount provided") } // Will be cast properly
val LocalBucketName = compositionLocalOf<String> { error("No bucketName provided") }

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
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    onDownload: (String, GalleryItem) -> Unit
) {
    // Provide CompositionLocal values for child components
    CompositionLocalProvider(
        LocalGalleryViewModel provides galleryViewModel,
        LocalGoogleAccount provides account as Any, // Cast to Any to match LocalGoogleAccount type
        LocalBucketName provides bucketName
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
    
    // Coroutine scope for launching async tasks
    val coroutineScope = rememberCoroutineScope()
    
    // Title based on current path
    val title = remember(currentPath) {
        if (currentPath.isEmpty()) {
            "Gallery" // Root directory
        } else {
            // Get the last part of the path
            currentPath.trimEnd('/').substringAfterLast('/')
        }
    }
    
    // Check if we're in selection mode
    val isInSelectionMode by galleryViewModel.isInSelectionMode.collectAsState()
    val selectedItems by galleryViewModel.selectedItems.collectAsState()
    val selectedItemsSize by galleryViewModel.selectedItemsSize.collectAsState()
    
    // Update selected items size when selection changes
    LaunchedEffect(selectedItems) {
        if (selectedItems.isNotEmpty()) {
            try {
                // Now that we've fixed the size calculation, we can enable it again
                galleryViewModel.updateSelectedItemsSize(account)
                Log.d(TAG, "Calculating size for ${selectedItems.size} selected items")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating selected items size: ${e.message}", e)
                // Continue gracefully without updating the size
            }
        }
    }
    
    // Handle system back button
    BackHandler(enabled = true) {
        if (isInSelectionMode) {
            // If in selection mode, exit selection mode first
            galleryViewModel.toggleSelectionMode()
        } else if (!galleryViewModel.navigateBack()) {
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
                title = { 
                    if (isInSelectionMode) {
                        // Show selection info with count and size
                        val sizeFormatted = if (selectedItemsSize > 0) {
                            " (${galleryViewModel.getFormattedSelectedSize()})"
                        } else {
                            ""
                        }
                        
                        if (selectedItems.size == 1) {
                            Text("1 item selected$sizeFormatted")
                        } else {
                            Text("${selectedItems.size} items selected$sizeFormatted")
                        }
                    } else {
                        // Normal title
                        Text(title)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isInSelectionMode) {
                            // Exit selection mode
                            galleryViewModel.toggleSelectionMode()
                        } else {
                            // Navigate back using history, will go to root if at first entry
                            galleryViewModel.navigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = if (isInSelectionMode) "Exit selection mode" else "Navigate back"
                        )
                    }
                },
                actions = {
                    if (isInSelectionMode) {
                        // Selection mode actions
                        // Download button
                        IconButton(onClick = {
                            // Process each selected file for download
                            coroutineScope.launch {
                                try {
                                    // For each selected item, get its URL and download it
                                    for (path in selectedItems) {
                                        // Find the GalleryItem corresponding to this path
                                        val item = items.find { it.path == path }
                                        if (item != null) {
                                            // Get authenticated URL for the item
                                            val url = galleryViewModel.getMediaUrlForDownload(account, bucketName, path)
                                            if (url != null) {
                                                // Download the item using the provided callback
                                                onDownload(url, item)
                                            }
                                        }
                                    }
                                    // Exit selection mode after starting all downloads
                                    galleryViewModel.toggleSelectionMode()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error downloading files: ${e.message}", e)
                                }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download selected"
                            )
                        }
                    } else {
                        // Normal mode actions
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
                            // Settings option
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToSettings()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null
                                    )
                                }
                            )
                            
                            // Logout option
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
                        isInSelectionMode = isInSelectionMode,
                        selectedItems = selectedItems,
                        onItemClick = { item ->
                            if (isInSelectionMode) {
                                if (item !is GalleryItem.Folder) {
                                    // Toggle selection when in selection mode
                                    galleryViewModel.toggleItemSelection(item, account)
                                } else {
                                    // Still navigate to folders in selection mode
                                    galleryViewModel.navigateToPath(item.path, gridState)
                                }
                            } else {
                                // Normal click behavior
                                when (item) {
                                    is GalleryItem.ImageFile -> onNavigateToImage(item)
                                    is GalleryItem.VideoFile -> onNavigateToVideo(item)
                                    is GalleryItem.DocumentFile -> onNavigateToDocument(item)
                                    is GalleryItem.Folder -> galleryViewModel.navigateToPath(item.path, gridState)
                                    else -> { /* Other files not handled */ }
                                }
                            }
                        },
                        onItemLongClick = { item ->
                            // Start selection mode on long press (if not already in it)
                            if (!isInSelectionMode) {
                                galleryViewModel.toggleSelectionMode(item, account)
                            } else if (item !is GalleryItem.Folder) {
                                // Toggle selection if already in selection mode
                                galleryViewModel.toggleItemSelection(item , account)
                            }
                        }
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
  } // Close CompositionLocalProvider
}

/**
 * Grid display of gallery items
 */
@Composable
private fun GalleryGrid(
    items: List<GalleryItem>,
    gridState: LazyGridState,
    imageLoader: ImageLoader,
    isInSelectionMode: Boolean = false,
    selectedItems: Set<String> = emptySet(),
    onItemClick: (GalleryItem) -> Unit,
    onItemLongClick: (GalleryItem) -> Unit
) {
    // Get view model and account info from parent components
    val galleryViewModel = LocalGalleryViewModel.current
    val account = LocalGoogleAccount.current as GoogleSignInAccount // Cast back to proper type
    val bucketName = LocalBucketName.current
    val thumbnailUrls by galleryViewModel.thumbnailUrls.collectAsState()
    
    // Get settings
    val settingsManager = com.example.schmucklemierphotos.ui.settings.SettingsManager.getInstance(
        LocalContext.current
    )
    val settings by settingsManager.settings.collectAsState()
    
    // Determine card size based on settings
    val cardSize = when (settings.galleryCardSize) {
        "small" -> 120.dp
        "medium" -> 160.dp
        "large" -> 200.dp
        else -> 160.dp // Default to medium
    }
    
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = cardSize), // Adaptive layout based on settings
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items) { item ->
            // Request thumbnail URL for media items based on settings
            LaunchedEffect(item, settings.extremeLowBandwidthMode) {
                when (item) {
                    is GalleryItem.ImageFile, is GalleryItem.VideoFile -> {
                        // Only request thumbnail if not in extreme low bandwidth mode
                        // Force load means we'll load it anyway when user stops scrolling
                        val forceLoad = false
                        galleryViewModel.getThumbnailUrl(account, bucketName, item.path, forceLoad)
                    }
                    else -> { /* No thumbnail needed */ }
                }
            }
            
            // Get the thumbnail URL for this item if available
            val thumbnailUrl = thumbnailUrls[item.path]
            
            // Check if this item is selected
            val isItemSelected = selectedItems.contains(item.path)
            
            GalleryCard(
                item = item,
                imageLoader = imageLoader,
                thumbnailUrl = thumbnailUrl,
                showFilenames = settings.showFilenames,
                isSelected = isItemSelected,
                isInSelectionMode = isInSelectionMode,
                onClick = { onItemClick(item) },
                onLongClick = { onItemLongClick(item) }
            )
        }
    }
}