package com.example.schmucklemierphotos

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.storage.StorageScopes
import com.example.schmucklemierphotos.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : FragmentActivity() {

    companion object {
        private const val BUCKET_NAME = "schmucklemier-long-term"
        private const val IMAGE_PATH = "2024/J&C_Wedding/Professional_Cora/Group_Shots/EveryoneBeforeHike.jpg"
    }

    private lateinit var gcpAuthManager: GCPAuthenticationManager
    private lateinit var biometricManager: BiometricLoginManager

    private val statusMessage = mutableStateOf("Press the button to authenticate")
    private val imageUrl = mutableStateOf<String?>(null)

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
                
                statusMessage.value = "Google authentication successful. Fetching image..."
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize the managers
        gcpAuthManager = GCPAuthenticationManager(this)
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

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
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

    /**
     * Start the authentication flow
     * First try biometric authentication, fallback to direct Google Sign-In if not available
     */
    private fun startAuthentication() {
        statusMessage.value = "Starting authentication..."
        
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