package com.example.schmucklemierphotos

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
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
import androidx.fragment.app.FragmentTransaction
import com.example.schmucklemierphotos.ui.settings.SettingsFragment
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.content.Context
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.schmucklemierphotos.model.GalleryItem
import com.example.schmucklemierphotos.model.GalleryRepository
import com.example.schmucklemierphotos.ui.gallery.GalleryScreen
import com.example.schmucklemierphotos.ui.gallery.GalleryViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.storage.StorageScopes
import com.example.schmucklemierphotos.BuildConfig
import com.example.schmucklemierphotos.ui.gallery.MediaViewerScreen
import com.example.schmucklemierphotos.ui.settings.SettingsManager
import com.example.schmucklemierphotos.ui.theme.SchmucklemierPhotosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.io.File

class MainActivity : FragmentActivity() {
    
    // Log tag for debugging
    private companion object {
        private const val TAG = "MainActivity"
        private const val BUCKET_NAME = "schmucklemier-long-term"
        private const val STORAGE_PERMISSION_REQUEST_CODE = 100
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
    }

    private lateinit var gcpAuthManager: GCPAuthenticationManager
    private lateinit var gcpStorageManager: GCPStorageManager
    private lateinit var biometricManager: BiometricLoginManager
    private lateinit var galleryRepository: GalleryRepository
    private lateinit var downloadManager: com.example.schmucklemierphotos.download.DownloadManager
    
    // Use the ViewModel for gallery operations
    private val galleryViewModel: GalleryViewModel by viewModels { 
        GalleryViewModel.Factory(this, lazy { galleryRepository })
    }

    // UI state - using ViewModel pattern for configuration change survival
    private val viewState = MainActivityViewState()
    
    // Handler for auto logout
    private val handler = Handler(Looper.getMainLooper())
    private var autoLogoutRunnable: Runnable? = null
    
    // Last authenticated time for remember login feature
    private val PREF_LAST_AUTH_TIME = "last_auth_time"
    
    // Accessor properties for state
    private val statusMessage get() = viewState.statusMessage
    private val bucketFolders get() = viewState.bucketFolders
    private val isAuthenticated get() = viewState.isAuthenticated
    private val showGallery get() = viewState.showGallery
    private val authenticatedAccount get() = viewState.authenticatedAccount
    
    // View state class to survive configuration changes
    class MainActivityViewState {
        val statusMessage = mutableStateOf("Welcome to Photo Gallery")
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
                
                // Save authentication time for remember login feature
                saveLastAuthenticationTime()
                
                // Set up auto-logout timer
                setupAutoLogoutTimer()
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
            downloadManager = com.example.schmucklemierphotos.download.DownloadManager.getInstance(this)
            
            // Request notification permission for Android 13+
            checkNotificationPermission()
            
            // Check if we should skip authentication based on remember login setting
            if (shouldSkipAuthentication()) {
                println("$TAG: Skipping authentication due to remember login setting")
                // Try to get last signed in account
                val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(this)
                if (lastSignedInAccount != null) {
                    // Auto-authenticate with last account
                    statusMessage.value = "Auto-signing in using remembered credentials..."
                    authenticatedAccount.value = lastSignedInAccount
                    isAuthenticated.value = true
                    showGallery.value = true
                    
                    // Setup auto-logout timer
                    setupAutoLogoutTimer()
                }
            }
            
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
                    Log.d(TAG, "Authentication error: $errString (code: $errorCode)")
                    
                    when (errorCode) {
                        // Handle too many failed attempts
                        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            statusMessage.value = "Too many failed attempts"
                            Toast.makeText(this@MainActivity, 
                                "Too many attempts. Use your PIN or password to continue.", 
                                Toast.LENGTH_LONG).show()
                            
                            // Show device credential prompt as fallback
                            biometricManager.showDeviceCredentialPrompt(
                                "Sign in to Photo Gallery",
                                "Too many failed biometric attempts. Please use your PIN or password."
                            )
                        }
                        
                        // Handle other errors
                        else -> {
                            statusMessage.value = "Authentication error: $errString"
                            Toast.makeText(this@MainActivity, 
                                "Authentication error: $errString", 
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        } else {
            println("$TAG: Activity recreated but managers are already initialized")
        }

        // Force dark mode in the system UI
        window.statusBarColor = android.graphics.Color.parseColor("#121212") // DarkBackground
        window.navigationBarColor = android.graphics.Color.parseColor("#121212") // DarkBackground
        
        setContent {
            SchmucklemierPhotosTheme(darkTheme = true, dynamicColor = false) {
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
                    val thumbnailUrls by galleryViewModel.thumbnailUrls.collectAsState()
                    
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
                                
                                // First, ensure thumbnails are loaded for current item
                                if (currentItem != null && thumbnailUrls[currentItem.path] == null) {
                                    galleryViewModel.getThumbnailUrl(account, BUCKET_NAME, currentItem.path)
                                }
                                
                                // Pre-cache thumbnail images first (higher priority)
                                galleryViewModel.preCacheThumbnails(account, BUCKET_NAME, 2)
                                
                                // Then pre-cache full-size images after thumbnails
                                galleryViewModel.preCacheAdjacentItems(account, BUCKET_NAME)
                                
                                MediaViewerScreen(
                                    previewableItems = previewableItems,
                                    initialIndex = currentIndex,
                                    mediaUrls = mediaUrls,
                                    thumbnailUrls = thumbnailUrls,
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
                                    onPreCacheThumbnails = { acc, bucket, range ->
                                        galleryViewModel.preCacheThumbnails(acc, bucket, range)
                                    },
                                    onClose = { 
                                        galleryViewModel.clearSelectedImage()
                                        galleryViewModel.clearSelectedVideo()
                                    },
                                    onShare = { url, item, _ ->
                                        // Check if file is already in cache
                                        val fileCache = com.example.schmucklemierphotos.cache.FileCache(this@MainActivity)
                                        
                                        // Handle file sharing based on size and cache status
                                        CoroutineScope(Dispatchers.Main).launch {
                                            try {
                                                // Check if the file is too large for direct sharing
                                                val isLargeFile = fileCache.isLargeFile(
                                                    item.path,
                                                    gcpStorageManager,
                                                    account,
                                                    BUCKET_NAME
                                                )
                                                
                                                if (isLargeFile) {
                                                    // Show message that file is too large for direct sharing
                                                    Toast.makeText(
                                                        this@MainActivity, 
                                                        "This file is too large to share directly (>40MB). Please download it first.",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                    return@launch
                                                }
                                                
                                                // Check if file is already in cache
                                                val cachedFile = fileCache.getFile(item.path)
                                                
                                                // If file is in cache, share it directly
                                                if (cachedFile != null) {
                                                    // File is cached, create content URI and share
                                                    shareFileFromCache(cachedFile, item)
                                                } else {
                                                    // Show progress indicator
                                                    Toast.makeText(
                                                        this@MainActivity, 
                                                        "Preparing file for sharing...",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    
                                                    // Download file to cache first
                                                    withContext(Dispatchers.IO) {
                                                        try {
                                                            val fileData = gcpStorageManager.getFileContent(
                                                                account, 
                                                                BUCKET_NAME,
                                                                item.path
                                                            )
                                                            
                                                            val mimeType = when(item) {
                                                                is GalleryItem.ImageFile -> item.mimeType ?: "image/*"
                                                                is GalleryItem.VideoFile -> item.mimeType ?: "video/*"
                                                                else -> null
                                                            }
                                                            
                                                            val cachedFile = fileCache.putFile(item.path, fileData, mimeType)
                                                            
                                                            // Now share the cached file
                                                            withContext(Dispatchers.Main) {
                                                                shareFileFromCache(cachedFile, item)
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e(TAG, "Error downloading file for sharing: ${e.message}")
                                                            withContext(Dispatchers.Main) {
                                                                // Fallback to sharing URL
                                                                Toast.makeText(
                                                                    this@MainActivity, 
                                                                    "Unable to share file directly. Sharing URL instead.",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                                
                                                                shareUrl(url)
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error sharing file: ${e.message}")
                                                // Fallback to sharing URL
                                                shareUrl(url)
                                            }
                                        }
                                    },
                                    onDownload = { url, item ->
                                        downloadFileToDevice(url, item)
                                    },
                                    onNavigateToSettings = {
                                        // Navigate to settings fragment
                                        navigateToSettings()
                                    },
                                    onLogout = {
                                        performLogout()
                                        
                                        // Clear remembered login by clearing the last authentication time
                                        val sharedPrefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                                        sharedPrefs.edit().remove(PREF_LAST_AUTH_TIME).apply()
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
                                    onNavigateToSettings = {
                                        // Navigate to settings fragment
                                        navigateToSettings()
                                    },
                                    onLogout = {
                                        performLogout()
                                        
                                        // Clear remembered login by clearing the last authentication time
                                        val sharedPrefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                                        sharedPrefs.edit().remove(PREF_LAST_AUTH_TIME).apply()
                                    }
                                )
                            }
                        }
                    } else {
                        // Show login screen
                        LoginScreen(
                            statusMessage = statusMessage.value,
                            onBiometricLoginClick = { startAuthentication() }
                        )
                    }
                }
            }
        }
    }

    /**
     * Start the authentication flow
     * Tries device authentication (biometric or PIN), and continues to Google Sign-In on success
     */
    private fun startAuthentication() {
        // Check if we're already authenticated (e.g., after a configuration change)
        if (isAuthenticated.value && authenticatedAccount.value != null) {
            println("$TAG: Already authenticated, skipping authentication flow")
            return
        }
        
        // Check what authentication methods are available
        val authType = biometricManager.getAvailableAuthType()
        
        when (authType) {
            BiometricLoginManager.AuthType.BIOMETRIC -> {
                statusMessage.value = "Starting biometric authentication..."
                
                // Use biometric authentication
                biometricManager.checkBiometricCapabilityAndAuthenticate(
                    onUnavailable = {
                        statusMessage.value = "Biometric authentication is not available. Please set up device security."
                        Toast.makeText(this, "Authentication not available on this device", Toast.LENGTH_SHORT).show()
                    },
                    promptTitle = "Sign in to Photo Gallery",
                    promptSubtitle = "Use biometrics to access your photos",
                    negativeButtonText = "Cancel"
                )
            }
            
            BiometricLoginManager.AuthType.DEVICE_CREDENTIAL -> {
                statusMessage.value = "Starting device authentication..."
                
                // Use device PIN/pattern/password authentication
                biometricManager.checkBiometricCapabilityAndAuthenticate(
                    onUnavailable = {
                        statusMessage.value = "Device authentication is not available. Please set up device security."
                        Toast.makeText(this, "Authentication not available on this device", Toast.LENGTH_SHORT).show()
                    },
                    promptTitle = "Sign in to Photo Gallery",
                    promptSubtitle = "Use your PIN or password to access your photos",
                    negativeButtonText = "Cancel"
                )
            }
            
            BiometricLoginManager.AuthType.NONE -> {
                // No device authentication available, go straight to Google Sign-In
                statusMessage.value = "Device security not set up. Starting Google Sign-In..."
                Toast.makeText(this, "No device security found. Using Google authentication.", Toast.LENGTH_SHORT).show()
                startGoogleSignIn()
            }
        }
    }
    
    /**
     * Save state during configuration changes
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        println("$TAG: onSaveInstanceState called, saving authentication state")
    }
    
    /**
     * Handle app closing, check if we need to clear cache
     */
    override fun onDestroy() {
        super.onDestroy()
        println("$TAG: onDestroy called, checking if cache should be cleared")
        
        // Cancel any pending auto-logout
        cancelAutoLogoutTimer()
        
        // Unbind from download service
        if (this::downloadManager.isInitialized) {
            downloadManager.unbindService()
        }
        
        // If authenticated, save the current time for remember login feature
        if (isAuthenticated.value && authenticatedAccount.value != null) {
            saveLastAuthenticationTime()
        }
        
        // Check settings to see if we should clear cache
        val settingsManager = SettingsManager.getInstance(this)
        if (settingsManager.settings.value.autoClearCache) {
            println("$TAG: Auto-clearing cache on app exit")
            // Use a coroutine scope that won't be cancelled when activity is destroyed
            kotlinx.coroutines.MainScope().launch {
                try {
                    // Clear the image cache
                    val fileCache = com.example.schmucklemierphotos.cache.FileCache(this@MainActivity)
                    fileCache.clearCache()
                    println("$TAG: Cache cleared successfully")
                } catch (e: Exception) {
                    println("$TAG: Error clearing cache: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Save the current time for the remember login feature
     */
    private fun saveLastAuthenticationTime() {
        val settingsManager = SettingsManager.getInstance(this)
        val rememberLoginMinutes = settingsManager.settings.value.rememberLoginMinutes
        
        // Only save if remember login is enabled
        if (rememberLoginMinutes > 0) {
            val sharedPrefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().putLong(PREF_LAST_AUTH_TIME, System.currentTimeMillis()).apply()
            println("$TAG: Saved last authentication time for remember login feature")
        }
    }
    
    /**
     * Check if we should skip authentication based on remember login setting
     */
    private fun shouldSkipAuthentication(): Boolean {
        val settingsManager = SettingsManager.getInstance(this)
        val rememberLoginMinutes = settingsManager.settings.value.rememberLoginMinutes
        
        // If remember login is disabled, don't skip authentication
        if (rememberLoginMinutes <= 0) {
            return false
        }
        
        val sharedPrefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val lastAuthTime = sharedPrefs.getLong(PREF_LAST_AUTH_TIME, 0)
        
        // If no previous authentication, don't skip
        if (lastAuthTime == 0L) {
            return false
        }
        
        // Calculate how much time has passed since last authentication
        val currentTime = System.currentTimeMillis()
        val elapsedMinutes = (currentTime - lastAuthTime) / (1000 * 60)
        
        // If within the remember login period, skip authentication
        return elapsedMinutes < rememberLoginMinutes
    }
    
    /**
     * Setup auto-logout timer based on settings
     */
    private fun setupAutoLogoutTimer() {
        // Cancel any existing timer first
        cancelAutoLogoutTimer()
        
        val settingsManager = SettingsManager.getInstance(this)
        val autoLogoutMinutes = settingsManager.settings.value.autoLogoutMinutes
        
        // Only setup timer if auto logout is enabled
        if (autoLogoutMinutes > 0) {
            val autoLogoutMs = autoLogoutMinutes * 60 * 1000L
            
            autoLogoutRunnable = Runnable {
                // Only log out if we're still authenticated
                if (isAuthenticated.value) {
                    println("$TAG: Auto logout triggered after $autoLogoutMinutes minutes of inactivity")
                    performLogout()
                }
            }
            
            // Schedule the auto logout
            handler.postDelayed(autoLogoutRunnable!!, autoLogoutMs)
            println("$TAG: Auto logout scheduled for $autoLogoutMinutes minutes from now")
        }
    }
    
    /**
     * Cancel any pending auto-logout timer
     */
    private fun cancelAutoLogoutTimer() {
        autoLogoutRunnable?.let {
            handler.removeCallbacks(it)
            println("$TAG: Auto logout timer cancelled")
        }
    }
    
    /**
     * Reset the auto-logout timer when there's user activity
     */
    private fun resetAutoLogoutTimer() {
        if (isAuthenticated.value) {
            cancelAutoLogoutTimer()
            setupAutoLogoutTimer()
        }
    }
    
    /**
     * Perform logout actions
     */
    private fun performLogout() {
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
        
        // Update UI
        statusMessage.value = "Logged out. Sign in to access your photos."
    }
    
    /**
     * Track user interaction events to reset auto-logout timer
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetAutoLogoutTimer()
        return super.dispatchTouchEvent(ev)
    }
    
    /**
     * Helper method to share a URL
     */
    private fun shareUrl(url: String) {
        val shareIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, url)
            type = "text/plain"
        }
        startActivity(android.content.Intent.createChooser(shareIntent, "Share media URL"))
    }
    
    /**
     * Helper method to share a file from the cache
     */
    private fun shareFileFromCache(cachedFile: File, item: GalleryItem) {
        try {
            // Create content URI for the file using FileProvider
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                cachedFile
            )
            
            // Determine MIME type
            val mimeType = when(item) {
                is GalleryItem.ImageFile -> item.mimeType ?: "image/*"
                is GalleryItem.VideoFile -> item.mimeType ?: "video/*"
                is GalleryItem.DocumentFile -> item.mimeType ?: "application/octet-stream"
                else -> "application/octet-stream"
            }
            
            // Create share intent
            val shareIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                type = mimeType
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(android.content.Intent.createChooser(shareIntent, "Share via"))
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing file: ${e.message}")
            Toast.makeText(
                this,
                "Error sharing file: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    /**
     * Check and request notification permission for Android 13+
     */
    private fun checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                // Request notification permission
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    /**
     * Helper method to download a file to the device's Downloads folder
     * Uses the DownloadManager to handle downloads in the background
     */
    private fun downloadFileToDevice(url: String, item: GalleryItem) {
        // Check notification permission first for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Store the download request info for when permission is granted
            pendingDownloadUrl = url
            pendingDownloadItem = item
            
            // Request permission
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
            return
        }
        
        // For Android 10+ (Q), we don't need WRITE_EXTERNAL_STORAGE permission
        val needsStoragePermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
        
        if (needsStoragePermission && 
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Store the download request info for when permission is granted
            pendingDownloadUrl = url
            pendingDownloadItem = item
            
            // Request permission
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST_CODE
            )
            return
        }
        
        // Permission is granted or not needed, proceed with download using the DownloadManager
        val account = authenticatedAccount.value
        if (account != null) {
            Toast.makeText(this, "Starting download...", Toast.LENGTH_SHORT).show()
            downloadManager.downloadFile(url, item, account, BUCKET_NAME)
        } else {
            Toast.makeText(this, "Authentication error. Please sign in again.", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Store pending download request for permission callback
    private var pendingDownloadUrl: String? = null
    private var pendingDownloadItem: GalleryItem? = null
    
    /**
     * Handle permission request results
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            STORAGE_PERMISSION_REQUEST_CODE -> {
                // Check if permission was granted
                if (grantResults.isNotEmpty() && 
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    
                    // Permission granted, proceed with pending download if any
                    val url = pendingDownloadUrl
                    val item = pendingDownloadItem
                    
                    if (url != null && item != null) {
                        // Check if we also need notification permission
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                this,
                                android.Manifest.permission.POST_NOTIFICATIONS
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            // Request notification permission
                            androidx.core.app.ActivityCompat.requestPermissions(
                                this,
                                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                                NOTIFICATION_PERMISSION_REQUEST_CODE
                            )
                        } else {
                            // All permissions granted, proceed with download
                            startDownload(url, item)
                        }
                    }
                } else {
                    // Permission denied
                    Toast.makeText(
                        this,
                        "Storage permission is required to download files",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                // Check if permission was granted
                if (grantResults.isNotEmpty() && 
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    
                    // Permission granted, proceed with pending download if any
                    val url = pendingDownloadUrl
                    val item = pendingDownloadItem
                    
                    if (url != null && item != null) {
                        // Check if we also need storage permission
                        val needsStoragePermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
                        
                        if (needsStoragePermission && 
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                this,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            // Request storage permission
                            androidx.core.app.ActivityCompat.requestPermissions(
                                this,
                                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                                STORAGE_PERMISSION_REQUEST_CODE
                            )
                        } else {
                            // All permissions granted, proceed with download
                            startDownload(url, item)
                        }
                    }
                } else {
                    // Permission denied
                    Toast.makeText(
                        this,
                        "Notification permission is required to show download progress",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Clear pending download request
                    pendingDownloadUrl = null
                    pendingDownloadItem = null
                }
            }
        }
    }
    
    /**
     * Helper to start a download after permissions are granted
     */
    private fun startDownload(url: String, item: GalleryItem) {
        // Use DownloadManager to start the download
        val account = authenticatedAccount.value
        if (account != null) {
            Toast.makeText(this, "Starting download...", Toast.LENGTH_SHORT).show()
            downloadManager.downloadFile(url, item, account, BUCKET_NAME)
            
            // Clear pending request
            pendingDownloadUrl = null
            pendingDownloadItem = null
        } else {
            Toast.makeText(this, "Authentication error. Please sign in again.", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * This method is no longer used - downloads are now handled by the DownloadService
     * Kept for reference
     */
    private fun startFileDownload(url: String, item: GalleryItem) {
        // This functionality has been replaced by the DownloadManager
        // and DownloadService implementation for background downloads
        Log.d(TAG, "Legacy download method called - using DownloadManager instead")
        downloadFileToDevice(url, item)
    }
    
    /**
     * Notify that download is complete
     */
    private fun notifyDownloadComplete(file: File) {
        Toast.makeText(
            this,
            "Download complete: ${file.name}",
            Toast.LENGTH_LONG
        ).show()
        
        // Make file visible in Downloads app
        val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        val contentUri = android.net.Uri.fromFile(file)
        mediaScanIntent.data = contentUri
        sendBroadcast(mediaScanIntent)
    }
    
    /**
     * Handle configuration changes
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        println("$TAG: onConfigurationChanged called, orientation: ${newConfig.orientation}")
    }
    
    /**
     * Navigate to the settings screen
     */
    private fun navigateToSettings() {
        val settingsFragment = SettingsFragment()
        supportFragmentManager.beginTransaction().apply {
            // Hide the current UI
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            // Add the fragment and add to back stack so we can return
            add(android.R.id.content, settingsFragment)
            addToBackStack("settings")
            commit()
        }
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

                withContext(Dispatchers.Main) {
                    statusMessage.value = "Authentication successful!"
                    
                    // After successful authentication, list the folders in the bucket
                    listBucketFolders(account)
                }
            } catch (e: Exception) {
                println("DEBUG-AUTH: Exception caught: ${e.javaClass.simpleName}: ${e.message}")
                println("DEBUG-AUTH: Stack trace:")
                e.printStackTrace()

                withContext(Dispatchers.Main) {
                    statusMessage.value = "Error during authentication: ${e.message}"
                    Toast.makeText(this@MainActivity, "Authentication failed: ${e.message}",
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
                    statusMessage.value = "Authentication successful! Loading gallery..."
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
fun LoginScreen(
    statusMessage: String,
    onBiometricLoginClick: () -> Unit
) {
    // Remember authentication availability state for UI display
    val context = LocalContext.current
    val authManager = remember { BiometricLoginManager(context as FragmentActivity) }
    val authType = remember { authManager.getAvailableAuthType() }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App title
            Text(
                text = "Photo Gallery",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 48.dp)
            )
            
            // Status message
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            // Sign in button - text changes based on what authentication method is available
            Button(
                onClick = onBiometricLoginClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                val buttonText = when (authType) {
                    BiometricLoginManager.AuthType.BIOMETRIC -> "Sign in with Biometrics"
                    BiometricLoginManager.AuthType.DEVICE_CREDENTIAL -> "Sign in with PIN/Password"
                    BiometricLoginManager.AuthType.NONE -> "Sign in with Google"
                }
                Text(buttonText)
            }
        }
    }
}