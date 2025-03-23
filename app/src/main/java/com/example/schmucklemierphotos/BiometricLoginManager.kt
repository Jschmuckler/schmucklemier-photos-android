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
 * Manager class to handle biometric authentication
 */
class BiometricLoginManager(private val activity: FragmentActivity) {

    companion object {
        private const val TAG = "BiometricLoginManager"
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
                    Log.d(TAG, "Biometric authentication succeeded")
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
     * Checks if biometric authentication is available and shows prompt if it is
     * @return true if biometric auth started, false if not available (fallback should be used)
     */
    fun checkBiometricCapabilityAndAuthenticate(
        onUnavailable: () -> Unit,
        promptTitle: String = "Authenticate",
        promptSubtitle: String = "Use your fingerprint to access",
        negativeButtonText: String = "Cancel"
    ): Boolean {
        val biometricManager = BiometricManager.from(activity)
        
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                Log.d(TAG, "Biometric authentication is available")
                showBiometricPrompt(promptTitle, promptSubtitle, negativeButtonText)
                true
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Log.d(TAG, "No biometric hardware available")
                Toast.makeText(
                    activity,
                    "No biometric hardware available on this device",
                    Toast.LENGTH_SHORT
                ).show()
                onUnavailable()
                false
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                Log.d(TAG, "Biometric hardware currently unavailable")
                Toast.makeText(
                    activity,
                    "Biometric features are currently unavailable",
                    Toast.LENGTH_SHORT
                ).show()
                onUnavailable()
                false
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Log.d(TAG, "No biometric credentials enrolled")
                Toast.makeText(
                    activity,
                    "No biometric credentials are enrolled. Please set up biometrics in settings",
                    Toast.LENGTH_LONG
                ).show()
                onUnavailable()
                false
            }
            else -> {
                Log.d(TAG, "Biometric authentication not available for other reason")
                onUnavailable()
                false
            }
        }
    }

    /**
     * Shows the biometric prompt dialog
     */
    private fun showBiometricPrompt(
        title: String, 
        subtitle: String, 
        negativeButtonText: String
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .build()

        biometricPrompt.authenticate(promptInfo)
        Log.d(TAG, "Biometric prompt displayed")
    }
}