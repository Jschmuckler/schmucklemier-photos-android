package com.example.schmucklemierphotos

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.storage.Storage
import com.google.api.services.storage.StorageScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager class to handle authentication and interaction with Google Cloud Platform services
 */
class GCPAuthenticationManager(private val context: Context) {

    companion object {
        private const val TAG = "GCPAuthManager"
    }

    /**
     * Creates a Storage service client authenticated with the user's credentials
     * @param account The Google Sign-In account to use for authentication
     * @return Authenticated Storage service instance
     */
    suspend fun createStorageService(account: GoogleSignInAccount): Storage {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Creating Storage service for account: ${account.email}")
            
            if (account.account == null) {
                throw IllegalStateException("Google account object is null")
            }
            
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(StorageScopes.DEVSTORAGE_READ_ONLY)
            )
            credential.selectedAccount = account.account
            Log.d(TAG, "Created OAuth2 credential for account: ${account.account?.name}")
            
            val storage = Storage.Builder(
                NetHttpTransport(),
                GsonFactory(),
                credential
            ).setApplicationName("GCP Image Viewer").build()
            
            Log.d(TAG, "Storage service created successfully")
            storage
        }
    }

    /**
     * Gets an authenticated URL for a file in Google Cloud Storage
     * @param account The Google Sign-In account to use for authentication
     * @param bucketName The name of the GCS bucket
     * @param objectPath The path to the object within the bucket
     * @return Authenticated URL that can be used to access the file
     */
    suspend fun getAuthenticatedFileUrl(
        account: GoogleSignInAccount, 
        bucketName: String, 
        objectPath: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Getting authenticated URL for $bucketName/$objectPath")
                
                if (account.account == null) {
                    throw IllegalStateException("Google account object is null")
                }
                
                // Get a fresh OAuth access token for GCP Storage
                val authToken = GoogleAuthUtil.getToken(
                    context,
                    account.account!!,
                    "oauth2:${StorageScopes.DEVSTORAGE_READ_ONLY}"
                )
                Log.d(TAG, "Successfully obtained OAuth token")
                
                // Create a storage service client
                val storage = createStorageService(account)
                
                // Get the object metadata
                Log.d(TAG, "Requesting object metadata from bucket: $bucketName, path: $objectPath")
                val objectRequest = storage.objects().get(bucketName, objectPath)
                val storageObject = objectRequest.execute()
                Log.d(TAG, "Object metadata retrieved successfully")
                
                // Get the mediaLink - this is the URL to the actual content
                val mediaLink = storageObject.mediaLink
                    ?: throw IllegalStateException("Unable to get media link for image")
                
                Log.d(TAG, "Media link obtained: ${mediaLink.take(50)}...")
                
                // Create authenticated URL with the OAuth token
                val finalUrl = if (mediaLink.contains("?")) {
                    "$mediaLink&access_token=$authToken"
                } else {
                    "$mediaLink?access_token=$authToken"
                }
                
                Log.d(TAG, "Created authenticated URL")
                finalUrl
                
            } catch (e: Exception) {
                Log.e(TAG, "Error getting authenticated URL: ${e.message}", e)
                throw e
            }
        }
    }
    
}