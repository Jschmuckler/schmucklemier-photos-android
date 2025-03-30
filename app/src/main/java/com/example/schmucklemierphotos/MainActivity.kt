package com.example.schmucklemierphotos

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.schmucklemierphotos.model.GalleryItem
import com.example.schmucklemierphotos.model.GalleryRepository
import com.example.schmucklemierphotos.ui.gallery.GalleryScreen
import com.example.schmucklemierphotos.ui.gallery.GalleryViewModel
import com.example.schmucklemierphotos.ui.gallery.ImageViewerScreen
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.storage.StorageScopes
import com.example.schmucklemierphotos.BuildConfig
import com.example.schmucklemierphotos.ui.gallery.MediaViewerScreen
import com.example.schmucklemierphotos.ui.gallery.VideoPlayerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : FragmentActivity() {
    
    // Log tag for debugging
    private companion object {
        private const val TAG = "MainActivity"
        private const val BUCKET_NAME = "schmucklemier-long-term"
        private const val IMAGE_PATH = "2024/J&C_Wedding/Professional_Cora/Group_Shots/EveryoneBeforeHike.jpg"
    }

    private lateinit var gcpAuthManager: GCPAuthenticationManager
    private lateinit var gcpStorageManager: GCPStorageManager
    private lateinit var biometricManager: BiometricLoginManager
    private lateinit var galleryRepository: GalleryRepository
    
    // Use the ViewModel for gallery operations
    private val galleryViewModel: GalleryViewModel by viewModels { 
        GalleryViewModel.Factory(this, lazy { galleryRepository })
    }

    // UI state - using ViewModel pattern for configuration change survival
    private val viewState = MainActivityViewState()
    
    // Accessor properties for state
    private val statusMessage get() = viewState.statusMessage
    private val imageUrl get() = viewState.imageUrl
    private val bucketFolders get() = viewState.bucketFolders
    private val isAuthenticated get() = viewState.isAuthenticated
    private val showGallery get() = viewState.showGallery
    private val authenticatedAccount get() = viewState.authenticatedAccount
    
    // View state class to survive configuration changes
    class MainActivityViewState {
        val statusMessage = mutableStateOf("Press the button to authenticate")
        val imageUrl = mutableStateOf<String?>(null)
        val bucketFolders = mutableStateOf<List<String>>(emptyList())
        val isAuthenticated = mutableStateOf(false)
        val showGallery = mutableStateOf(false)
        val authenticatedAccount = mutableStateOf<GoogleSignInAccount?>(null)
    }

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        println("DEBUG-AUTH: Google Sign-In result received, resultCode: ${result.resultCode}")
        try {
            println("DEBUG-AUTH: Processing sign-in result...")
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            println("DEBUG-AUTH: Got sign-in task, extracting account...")
            
            val account = task.getResult(ApiException::class.java)
            println("DEBUG-AUTH: Successfully extracted account from result")

            if (account != null) {
                println("DEBUG-AUTH: Account is not null, email: ${account.email}")
                println("DEBUG-AUTH: Account has ID token: ${account.idToken != null}")
                println("DEBUG-AUTH: Account has server auth code: ${account.serverAuthCode != null}")
                println("DEBUG-AUTH: Account has granted scopes: ${account.grantedScopes}")
                
                statusMessage.value = "Google authentication successful. Fetching data..."
                authenticatedAccount.value = account
                isAuthenticated.value = true
                showGallery.value = true
                getGcpAuthTokenAndLoadImage(account)
            } else {
                println("DEBUG-AUTH: Account is null despite no ApiException being thrown")
                statusMessage.value = "Google authentication failed: account is null"
                Toast.makeText(this, "Google Sign-In failed: null account", Toast.LENGTH_SHORT).show()
            }
        } catch (e: ApiException) {
            println("DEBUG-AUTH: ApiException caught during sign-in: code=${e.statusCode}, message=${e.message}")
            println("DEBUG-AUTH: Status details: ${e.status}")
            
            statusMessage.value = "Google authentication failed: ${e.statusCode}"
            Toast.makeText(this, "Google Sign-In failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            println("DEBUG-AUTH: Unexpected exception during sign-in: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            
            statusMessage.value = "Google authentication failed with unexpected error"
            Toast.makeText(this, "Google Sign-In failed unexpectedly: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Create a custom image loader with extended timeouts
    private val imageLoader: ImageLoader by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
            
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()
    }
    
    /**
     * Called when the activity is first created or recreated after a configuration change
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        println("$TAG: onCreate called, savedInstanceState is ${if (savedInstanceState == null) "null" else "not null"}")
        
        // Initialize the managers if this is the first creation (not a config change)
        if (!this::gcpAuthManager.isInitialized) {
            println("$TAG: Initializing managers for the first time")
            gcpAuthManager = GCPAuthenticationManager(this)
            gcpStorageManager = GCPStorageManager(this, gcpAuthManager)
            galleryRepository = GalleryRepository(gcpStorageManager)
            biometricManager = BiometricLoginManager(this)
            
            // Setup biometric authentication
            biometricManager.setupBiometricAuth(object : BiometricLoginManager.BiometricAuthListener {
                override fun onAuthenticationSucceeded() {
                    statusMessage.value = "Biometric authentication successful. Starting Google Sign-In..."
                    startGoogleSignIn()
                }
    
                override fun onAuthenticationFailed() {
                    statusMessage.value = "Authentication failed"
                    Toast.makeText(this@MainActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
                }
    
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    statusMessage.value = "Authentication error: $errString"
                    Toast.makeText(this@MainActivity, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                }
            })
        } else {
            println("$TAG: Activity recreated but managers are already initialized")
        }

        setContent {
            MaterialTheme {
                // Keep system window decorations to show app bar
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val account = authenticatedAccount.value
                    val selectedImage by galleryViewModel.selectedImage.collectAsState()
                    val selectedVideo by galleryViewModel.selectedVideo.collectAsState()
                    val mediaUrls by galleryViewModel.mediaUrls.collectAsState()
                    
                    if (showGallery.value && isAuthenticated.value && account != null) {
                        // Show gallery when authenticated
                        when {
                            // Show media viewer when an image or video is selected
                            selectedImage != null || selectedVideo != null -> {
                                // Get the latest previewable items for swiping
                                galleryViewModel.updatePreviewableItems()
                                val previewableItems = galleryViewModel.previewableItems.value
                                val currentIndex = galleryViewModel.currentPreviewPosition.value
                                
                                // Back handler to return to gallery
                                BackHandler {
                                    galleryViewModel.clearSelectedImage()
                                    galleryViewModel.clearSelectedVideo()
                                }
                                
                                // Ensure URLs are loaded for current item
                                val currentItem = if (selectedImage != null) selectedImage else selectedVideo
                                if (currentItem != null && mediaUrls[currentItem.path] == null) {
                                    galleryViewModel.getMediaUrl(account, BUCKET_NAME, currentItem.path)
                                }
                                
                                // Pre-cache adjacent items
                                galleryViewModel.preCacheAdjacentItems(account, BUCKET_NAME)
                                
                                MediaViewerScreen(
                                    previewableItems = previewableItems,
                                    initialIndex = currentIndex,
                                    mediaUrls = mediaUrls,
                                    imageLoader = imageLoader,
                                    account = account,
                                    bucketName = BUCKET_NAME,
                                    onNavigateToPrevious = { acc, bucket ->
                                        galleryViewModel.navigateToPreviousPreviewable(acc, bucket)
                                    },
                                    onNavigateToNext = { acc, bucket ->
                                        galleryViewModel.navigateToNextPreviewable(acc, bucket)
                                    },
                                    onPreCacheAdjacent = { acc, bucket ->
                                        galleryViewModel.preCacheAdjacentItems(acc, bucket)
                                    },
                                    onClose = { 
                                        galleryViewModel.clearSelectedImage()
                                        galleryViewModel.clearSelectedVideo()
                                    }
                                )
                            }
                            
                            // Show gallery screen
                            else -> {
                                GalleryScreen(
                                    galleryRepository = galleryRepository,
                                    galleryViewModel = galleryViewModel,
                                    account = account,
                                    bucketName = BUCKET_NAME,
                                    imageLoader = imageLoader,
                                    onNavigateToImage = { image ->
                                        galleryViewModel.selectImage(image)
                                        // Load the image URL
                                        galleryViewModel.getMediaUrl(account, BUCKET_NAME, image.path)
                                    },
                                    onNavigateToVideo = { video ->
                                        galleryViewModel.selectVideo(video)
                                        // Load the video URL
                                        galleryViewModel.getMediaUrl(account, BUCKET_NAME, video.path)
                                    },
                                    onNavigateToDocument = { /* Handle document files */ },
                                    onLogout = {
                                        // Clear authentication state and reset view
                                        authenticatedAccount.value = null
                                        isAuthenticated.value = false
                                        showGallery.value = false
                                        galleryViewModel.clearNavigationHistory()
                                        // Sign out from Google
                                        val googleSignInClient = GoogleSignIn.getClient(
                                            this, 
                                            GoogleSignInOptions.DEFAULT_SIGN_IN
                                        )
                                        googleSignInClient.signOut()
                                        statusMessage.value = "Logged out. Press the button to authenticate."
                                    }
                                )
                            }
                        }
                    } else {
                        // Show authentication screen
                        PhotoViewerScreen(
                            statusMessage = statusMessage.value,
                            imageUrl = imageUrl.value,
                            imageLoader = imageLoader,
                            onAuthButtonClick = { startAuthentication() }
                        )
                    }
                }
            }
        }
    }

    /**
     * Start the authentication flow
     * First try biometric authentication, fallback to direct Google Sign-In if not available
     */
    private fun startAuthentication() {
        statusMessage.value = "Starting authentication..."
        
        // Check if we're already authenticated (e.g., after a configuration change)
        if (isAuthenticated.value && authenticatedAccount.value != null) {
            println("$TAG: Already authenticated, skipping authentication flow")
            return
        }
        
        // Check if we can use biometrics, otherwise go straight to Google Sign-In
        biometricManager.checkBiometricCapabilityAndAuthenticate(
            onUnavailable = {
                statusMessage.value = "Biometric auth unavailable. Starting Google Sign-In..."
                startGoogleSignIn()
            },
            promptTitle = "Authenticate",
            promptSubtitle = "Use your fingerprint to access the image",
            negativeButtonText = "Cancel"
        )
    }
    
    /**
     * Save state during configuration changes
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        println("$TAG: onSaveInstanceState called, saving authentication state")
    }
    
    /**
     * Handle configuration changes
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        println("$TAG: onConfigurationChanged called, orientation: ${newConfig.orientation}")
    }

    private fun startGoogleSignIn() {
        statusMessage.value = "Starting Google authentication..."
        println("DEBUG-AUTH: Starting Google Sign-In process")
        
        val clientId = BuildConfig.GCP_CLIENT_ID
        println("DEBUG-AUTH: Using client ID from BuildConfig$clientId")
        
        println("DEBUG-AUTH: Requesting scope: ${StorageScopes.DEVSTORAGE_READ_ONLY}")
        val gsoScope = Scope(StorageScopes.DEVSTORAGE_READ_ONLY)
        
        println("DEBUG-AUTH: Building GoogleSignInOptions")
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(gsoScope)
            .build()
        
        // Check if we have existing account already
        val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastSignedInAccount != null) {
            println("DEBUG-AUTH: Found existing signed-in account: ${lastSignedInAccount.email}")
            println("DEBUG-AUTH: Has ID token: ${lastSignedInAccount.idToken != null}")
            println("DEBUG-AUTH: Has storage scope: ${GoogleSignIn.hasPermissions(lastSignedInAccount, gsoScope)}")
        } else {
            println("DEBUG-AUTH: No existing signed-in account found")
        }
        
        println("DEBUG-AUTH: Creating sign-in client and launching sign-in intent")
        val googleSignInClient = GoogleSignIn.getClient(this, gso)
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun getGcpAuthTokenAndLoadImage(account: GoogleSignInAccount) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                println("DEBUG-AUTH: Starting GCP authentication with account: ${account.email}")
                
                if (account.account == null) {
                    throw Exception("Google account object is null, cannot authenticate with GCP")
                }

                // Use our GCPAuthenticationManager to get an authenticated URL
                val authenticatedUrl = gcpAuthManager.getAuthenticatedFileUrl(
                    account,
                    BUCKET_NAME,
                    IMAGE_PATH
                )
                
                println("DEBUG-AUTH: Got authenticated URL: ${authenticatedUrl.take(75)}...")

                withContext(Dispatchers.Main) {
                    statusMessage.value = "Image loaded successfully!"
                    imageUrl.value = authenticatedUrl
                    println("DEBUG-AUTH: Set image URL to use with Coil")
                    
                    // After successful authentication, list the folders in the bucket
                    listBucketFolders(account)
                }
            } catch (e: Exception) {
                println("DEBUG-AUTH: Exception caught: ${e.javaClass.simpleName}: ${e.message}")
                println("DEBUG-AUTH: Stack trace:")
                e.printStackTrace()

                withContext(Dispatchers.Main) {
                    statusMessage.value = "Error loading image: ${e.message}"
                    Toast.makeText(this@MainActivity, "Failed to fetch image: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Lists all folders in the GCP bucket
     * @param account The authenticated Google account to use
     */
    private fun listBucketFolders(account: GoogleSignInAccount) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                println("DEBUG-STORAGE: Listing folders in bucket: $BUCKET_NAME")
                
                val folders = gcpStorageManager.listBucketDirectories(
                    account = account,
                    bucketName = BUCKET_NAME
                )
                
                withContext(Dispatchers.Main) {
                    bucketFolders.value = folders
                    
                    // Print the folders to the console for debugging
                    println("DEBUG-STORAGE: Found ${folders.size} folders in bucket $BUCKET_NAME:")
                    folders.forEachIndexed { index, folder ->
                        println("DEBUG-STORAGE: Folder ${index + 1}: $folder")
                    }
                    
                    // Update status message to show folder count
                    statusMessage.value = "Image loaded successfully! Found ${folders.size} folders."
                }
                
            } catch (e: Exception) {
                println("DEBUG-STORAGE: Error listing folders: ${e.message}")
                e.printStackTrace()
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to list bucket folders: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}

@Composable
fun PhotoViewerScreen(
    statusMessage: String,
    imageUrl: String?,
    imageLoader: ImageLoader,
    onAuthButtonClick: () -> Unit
) {
    var currentImageUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(imageUrl) {
        currentImageUrl = imageUrl
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        Button(
            onClick = onAuthButtonClick,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text("Authenticate & Load Image")
        }

        Spacer(modifier = Modifier.height(16.dp))

        currentImageUrl?.let { url ->
            println("DEBUG-AUTH: Displaying image from URL: ${url.take(75)}...")
            
            // Create a custom request with our authenticated URL
            val request = ImageRequest.Builder(context)
                .data(url)
                .crossfade(true)
                .build()
            
            // Display the image using our configured loader
            AsyncImage(
                model = request,
                contentDescription = "GCP Bucket Image",
                imageLoader = imageLoader,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentScale = ContentScale.FillWidth,
                onSuccess = { 
                    println("DEBUG-AUTH: Image loaded successfully")
                },
                onError = { 
                    println("DEBUG-AUTH: Failed to load image: ${it.result.throwable}")
                    it.result.throwable.printStackTrace()
                }
            )
        }
    }
}