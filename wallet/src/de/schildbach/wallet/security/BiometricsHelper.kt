package de.schildbach.wallet.security

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.CryptoObject
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.slf4j.LoggerFactory

object Companion {
    const val AUTH_TYPES = BIOMETRIC_STRONG
    val log = LoggerFactory.getLogger(FingerprintHelper::class.java)
}

fun BiometricManager.isBiometricAvailable(context: Context): Boolean {
    return when (canAuthenticate(Companion.AUTH_TYPES)) {
        BiometricManager.BIOMETRIC_SUCCESS -> true
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
            Companion.log.info("Biometric hardware not detected")
            false
        }
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
            Companion.log.info("Biometric features are not available")
            false
        }
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
            Companion.log.info("Biometric hardware requires security patch")
            false
        }
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
            // Prompts the user to create credentials that your app accepts.
//            val enrollIntent = Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
//                putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, Companion.AUTH_TYPES)
//            }
//            context.startActivity(enrollIntent) // ,9999) TODO startActivityForResult here, check/change
            false // TODO: recall isAvailable
        }
        else -> false
    }
}

fun BiometricManager.authenticate(
    activity: FragmentActivity,
    cryptoObject: CryptoObject,
    callback: BiometricPrompt.AuthenticationCallback
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(activity, executor, callback)

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Biometric Authentication")
        .setDescription("Please authenticate in order to verify your identity")
        .setNegativeButtonText("Cancel")
        .setAllowedAuthenticators(Companion.AUTH_TYPES)
        .build()

    prompt.authenticate(promptInfo, cryptoObject)
//    prompt.cancelAuthentication() TODO
}