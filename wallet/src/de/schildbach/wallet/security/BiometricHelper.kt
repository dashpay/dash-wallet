package de.schildbach.wallet.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.CryptoObject
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import java.io.IOException
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class BiometricException(val errorCode: Int, message: String): Exception(message)
class BiometricLockoutException(val isPermanent: Boolean, errorCode: Int, message: String) : BiometricException(errorCode, message)

class BiometricHelper(context: Context, private val configuration: Configuration) {
    companion object {
        private const val AUTH_TYPES = BIOMETRIC_STRONG
        private val log = LoggerFactory.getLogger(BiometricHelper::class.java)
    }

    private val fingerprintStorage = FingerprintStorage(context)
    private val biometricManager = BiometricManager.from(context)
    private var currentPrompt: BiometricPrompt? = null

    val isAvailable: Boolean
        get() {
            if (!isBiometricAvailable()) {
                return false
            }

            return fingerprintStorage.isAvailable
        }

    val isEnabled: Boolean
        get() = isAvailable && fingerprintStorage.hasEncryptedPassword()

    val requiresEnabling: Boolean
        get() = isAvailable && !fingerprintStorage.hasEncryptedPassword() && configuration.remindEnableFingerprint

    suspend fun savePassword(activity: FragmentActivity, password: String): Boolean {
        return suspendCancellableCoroutine { coroutine ->
            savePassword(activity, password) { result, exception ->
                if (coroutine.isActive) {
                    if (exception != null) {
                        coroutine.resumeWithException(exception)
                    } else {
                        coroutine.resume(result)
                    }
                }
            }
        }
    }

    fun savePassword(activity: FragmentActivity, password: String, callback: (Boolean, Exception?) -> Unit) {
        val authCallback = object: BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher

                if (cipher == null) {
                    val message = "Could not create cipher"
                    log.info(message)
                    callback.invoke(false, NullPointerException(message))
                    return
                }

                try {
                    val isEncrypted = fingerprintStorage.encryptPassword(cipher, password)
                    callback.invoke(isEncrypted, null)
                } catch (ex: IOException) {
                    log.error("failed to encrypt password")
                    callback.invoke(false, ex)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                log.error("Authentication error [$errorCode] $errString")
                val message = errString.toString()
                val exception = getException(errorCode, message)
                callback.invoke(false, exception)
            }
        }

        try {
            this.authenticate(activity, Cipher.ENCRYPT_MODE, false, authCallback)
        } catch (ex: Exception) {
            callback.invoke(false, ex)
        }
    }

    suspend fun getPassword(activity: FragmentActivity, forUnlock: Boolean): String? {
        return suspendCancellableCoroutine { coroutine ->
            getPassword(activity, forUnlock) { pass, exception ->
                if (coroutine.isActive) {
                    if (exception != null) {
                        coroutine.resumeWithException(exception)
                    } else {
                        coroutine.resume(pass)
                    }
                }
            }
        }
    }

    fun getPassword(activity: FragmentActivity, forUnlock: Boolean, callback: (String?, Exception?) -> Unit) {
        val authCallback = object: BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher

                if (cipher == null) {
                    val message = "Cipher is empty"
                    log.error(message)
                    callback.invoke(null, NullPointerException(message))
                    return
                }

                try {
                    val password = fingerprintStorage.decipherPassword(cipher)
                    callback.invoke(password, null)
                } catch (ex: IOException) {
                    log.error("failed to decipher password")
                    callback.invoke(null, ex)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                log.error("Authentication error [$errorCode] $errString")
                val message = errString.toString()
                val exception = getException(errorCode, message)
                callback.invoke(null, exception)
            }
        }

        try {
            this.authenticate(activity, Cipher.DECRYPT_MODE, forUnlock, authCallback)
        } catch (ex: Exception) {
            callback.invoke(null, ex)
        }
    }

    fun clearBiometricInfo() {
        if (isEnabled) {
            configuration.remindEnableFingerprint = true
        }
        fingerprintStorage.clear()
    }

    fun cancelPending() {
        currentPrompt?.cancelAuthentication()
        currentPrompt = null
    }

    private val biometricScope = CoroutineScope(Dispatchers.Main)

    // For Java. Use setupBiometric from Kotlin
    fun runEnableBiometricReminder(activity: FragmentActivity, pass: String) {
        biometricScope.launch(Dispatchers.Main) {
            enableBiometricReminder(activity, pass)
        }
    }

    suspend fun enableBiometricReminder(activity: FragmentActivity, pass: String): Boolean {
        val enable = AdaptiveDialog.create(
            R.drawable.ic_fingerprint,
            activity.getString(R.string.enable_fingerprint),
            activity.getString(R.string.touch_fingerprint_to_enable),
            activity.getString(R.string.notification_inactivity_action_dismiss_forever),
            activity.getString(R.string.enable_fingerprint)
        ).showAsync(activity)

        if (enable == true) {
            try {
                return savePassword(activity, pass)
            } catch (ex: Exception) {
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    activity.getString(R.string.error),
                    ex.localizedMessage ?: "",
                    activity.getString(R.string.button_dismiss)
                ).show(activity)
            }
        } else if (enable == false) {
            configuration.remindEnableFingerprint = false
        }

        return false
    }

    private fun isBiometricAvailable(): Boolean {
        return when (val result = biometricManager.canAuthenticate(AUTH_TYPES)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                log.info("Biometric hardware not detected")
                false
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                log.info("Biometric features are not available")
                false
            }
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                log.info("Biometric hardware requires security patch")
                false
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                log.info("No biometric credentials")
                false
            }
            else -> {
                log.info("Cannot authenticate: $result")
                false
            }
        }
    }

    private fun authenticate(
        activity: FragmentActivity,
        mode: Int,
        forUnlock: Boolean,
        authListener: BiometricPrompt.AuthenticationCallback
    ) {
        try {
            val cipher = fingerprintStorage.createCipher(mode)

            if (cipher != null) {
                val crypto = CryptoObject(cipher)
                showPrompt(activity, crypto, forUnlock, authListener)
            } else {
               throw NullPointerException("cipher is empty")
            }
        } catch (t: Throwable) {
            if (t is KeyPermanentlyInvalidatedException) {
                // reset fingerprint
                clearBiometricInfo()
            }

            log.warn("An error occurred", t)
            throw t
        }
    }

    private fun showPrompt(
        activity: FragmentActivity,
        cryptoObject: CryptoObject,
        forUnlock: Boolean,
        callback: BiometricPrompt.AuthenticationCallback
    ) {
        currentPrompt?.cancelAuthentication()
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(if (forUnlock) R.string.touch_fingerprint_sensor else R.string.authenticate_fingerprint_title))
            .setDescription(activity.getString(if (forUnlock) R.string.unlock_with_fingerprint else R.string.authenticate_fingerprint_message))
            .setNegativeButtonText(activity.getString(android.R.string.cancel))
            .setAllowedAuthenticators(AUTH_TYPES)
            .build()

        prompt.authenticate(promptInfo, cryptoObject)
        currentPrompt = prompt
    }

    private fun getException(errorCode: Int, message: String): Exception? {
        return when (errorCode) {
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_USER_CANCELED -> null
            BiometricPrompt.ERROR_LOCKOUT -> BiometricLockoutException(false, errorCode, message)
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricLockoutException(true, errorCode, message)
            else -> BiometricException(errorCode, message)
        }
    }
}