package com.example.schmucklemierphotos

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

/**
 * Manager class to handle biometric and device authentication
 */
class BiometricLoginManager(private val activity: FragmentActivity) {

    companion object {
        private const val TAG = "BiometricLoginManager"
    }
    
    // Authentication types available on this device
    enum class AuthType {
        BIOMETRIC,  // Fingerprint, face, iris
        DEVICE_CREDENTIAL,  // PIN, pattern, password
        NONE        // No authentication available
    }

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt

    // Callback interfaces for authentication results
    interface BiometricAuthListener {
        fun onAuthenticationSucceeded()
        fun onAuthenticationFailed()
        fun onAuthenticationError(errorCode: Int, errString: CharSequence)
    }

    /**
     * Sets up the biometric authentication system
     */
    fun setupBiometricAuth(listener: BiometricAuthListener) {
        Log.d(TAG, "Setting up biometric authentication")
        executor = ContextCompat.getMainExecutor(activity)

        biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.d(TAG, "Authentication succeeded")
                    listener.onAuthenticationSucceeded()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.d(TAG, "Authentication error: $errString")
                    listener.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.d(TAG, "Authentication failed")
                    listener.onAuthenticationFailed()
                }
            })
    }
    
    /**
     * Determines what authentication methods are available on this device
     * @return The available authentication type
     */
    fun getAvailableAuthType(): AuthType {
        val biometricManager = BiometricManager.from(activity)
        
        // First check if biometric authentication is available
        val canUseBiometric = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == 
            BiometricManager.BIOMETRIC_SUCCESS
            
        if (canUseBiometric) {
            return AuthType.BIOMETRIC
        }
        
        // Then check if device credential is available (PIN, pattern, password)
        val canUseDeviceCredential = biometricManager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 
            BiometricManager.BIOMETRIC_SUCCESS
            
        if (canUseDeviceCredential) {
            return AuthType.DEVICE_CREDENTIAL
        }
        
        // No authentication method available
        return AuthType.NONE
    }

    /**
     * Checks if biometric or device credential authentication is available and shows prompt
     * @return true if authentication prompt shown, false if not available
     */
    fun checkBiometricCapabilityAndAuthenticate(
        onUnavailable: () -> Unit,
        promptTitle: String = "Authenticate",
        promptSubtitle: String = "Use biometrics or device PIN to access",
        negativeButtonText: String = "Cancel"
    ): Boolean {
        val biometricManager = BiometricManager.from(activity)
        val authType = getAvailableAuthType()
        
        when (authType) {
            AuthType.BIOMETRIC -> {
                Log.d(TAG, "Using biometric authentication with PIN/password fallback")
                
                // Try to use combined biometric + credential authentication first
                try {
                    // Check if combined authentication is supported 
                    if (biometricManager.canAuthenticate(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        ) == BiometricManager.BIOMETRIC_SUCCESS
                    ) {
                        // Show prompt with biometric first, with automatic fallback to device credential
                        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                            .setTitle(promptTitle)
                            .setSubtitle("Enter your phone PIN to continue")
                            .setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            )
                            .build()
                        
                        biometricPrompt.authenticate(promptInfo)
                        Log.d(TAG, "Shown combined biometric+credential prompt")
                        return true
                    } else {
                        // Fallback to separate biometric prompt with manual option to use PIN
                        showBiometricPrompt(promptTitle, promptSubtitle, "Use PIN or Password", false)
                        return true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing combined auth prompt, falling back to biometric only: ${e.message}")
                    showBiometricPrompt(promptTitle, promptSubtitle, "Use PIN or Password", false)
                    return true
                }
            }
            
            AuthType.DEVICE_CREDENTIAL -> {
                Log.d(TAG, "Using device credential authentication (PIN/pattern/password)")
                showDeviceCredentialPrompt(promptTitle, "Enter your PIN, pattern, or password")
                return true
            }
            
            AuthType.NONE -> {
                // No security method available
                Log.d(TAG, "No authentication method available")
                Toast.makeText(
                    activity,
                    "No security method is available on this device. Please set up a screen lock in your device settings.",
                    Toast.LENGTH_LONG
                ).show()
                onUnavailable()
                return false
            }
        }
    }

    /**
     * Shows the biometric prompt dialog with device credential fallback via negative button
     */
    private fun showBiometricPrompt(
        title: String, 
        subtitle: String, 
        negativeButtonText: String,
        allowDeviceCredential: Boolean = false
    ) {
        // Create a custom callback to handle negative button (for PIN/password fallback)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.d(TAG, "Biometric authentication succeeded")
                // The main callback gets this already, we don't need to do anything here
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.d(TAG, "Authentication error: $errString (code: $errorCode)")
                
                // If user clicked negative button (Use PIN/Password), show device credential prompt
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Log.d(TAG, "User selected to use PIN/password instead of biometrics")
                    showDeviceCredentialPrompt(title, "Enter your PIN, pattern, or password")
                } 
                // Otherwise let the main callback handle it
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.d(TAG, "Biometric authentication attempt failed")
                // The main callback gets this already
            }
        }
        
        // Set up biometric prompt
        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
        
        // Add negative button for PIN/password fallback
        promptBuilder.setNegativeButtonText(negativeButtonText)
        
        val promptInfo = promptBuilder.build()
        
        // Create a temporary BiometricPrompt with our custom callback
        val biometricPromptWithFallback = BiometricPrompt(activity, executor, callback)
        biometricPromptWithFallback.authenticate(promptInfo)
        
        Log.d(TAG, "Biometric authentication prompt displayed with PIN/password fallback option")
    }
    
    /**
     * Shows device credential authentication prompt (PIN, pattern, password)
     * Public method that can be called directly when biometric authentication fails
     */
    fun showDeviceCredentialPrompt(
        title: String,
        subtitle: String
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
            
        biometricPrompt.authenticate(promptInfo)
        Log.d(TAG, "Device credential prompt displayed")
    }
}