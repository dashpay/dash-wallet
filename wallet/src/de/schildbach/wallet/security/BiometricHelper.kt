package de.schildbach.wallet.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.CryptoObject
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.suspendCancellableCoroutine
import org.dash.wallet.common.Configuration
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


class BiometricHelper(
    private val context: Context,
    private val configuration: Configuration
) {
    companion object {
        private const val AUTH_TYPES = BIOMETRIC_STRONG
        private val log = LoggerFactory.getLogger(BiometricHelper::class.java)
    }

    private val fingerprintStorage = FingerprintStorage(context)
    private val biometricManager = BiometricManager.from(context)

    val isAvailable: Boolean
        get() {
            if (!isBiometricAvailable(context)) {
                return false
            }

            return fingerprintStorage.isAvailable
        }

    val isEnabled: Boolean
        get() = fingerprintStorage.hasEncryptedPassword()

    val requiresEnabling: Boolean
        get() = isAvailable && !isEnabled && configuration.remindEnableFingerprint

    suspend fun savePassword(activity: FragmentActivity, password: String): Boolean {
        return suspendCancellableCoroutine { coroutine ->
            this.authenticate(activity, Cipher.ENCRYPT_MODE, object: BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val cipher = result.cryptoObject?.cipher

                        if (fingerprintStorage.encryptPassword(cipher, password)) {
                            log.info("password encrypted successfully")

                            if (coroutine.isActive) {
                                coroutine.resume(true)
                            }
                        } else {
                            val message = "failed to encrypt password"
                            log.info(message)

                            if (coroutine.isActive) {
                                coroutine.resumeWithException(Exception(message)) // TODO
                            }
                        }
                    } catch (ex: Exception) {
                        val message = "Encryption failed " + ex.message
                        log.info(message)

                        if (coroutine.isActive) {
                            coroutine.resumeWithException(ex) // TODO
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    log.error("Authentication error [$errorCode] $errString")

                    if (coroutine.isActive) {
                        when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> coroutine.resume(false)
                            BiometricPrompt.ERROR_USER_CANCELED -> coroutine.resume(false)
                            BiometricPrompt.ERROR_LOCKOUT -> coroutine.resumeWithException(Exception(errString.toString()))
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> coroutine.resumeWithException(Exception(errString.toString()))
                            else -> {
                                Log.i("FINGERPRINT", "Something else")
                                coroutine.resume(false)
                            }
                        }
                    }
                }
            })
        }
    }

    suspend fun getPassword(activity: FragmentActivity): String? {
        return suspendCancellableCoroutine { coroutine ->
            this.authenticate(activity, Cipher.DECRYPT_MODE, object: BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val cipher = result.cryptoObject?.cipher
                        val password = cipher?.let { fingerprintStorage.decipherPassword(it) }

                        if (password != null) {
                            if (coroutine.isActive) {
                                coroutine.resume(password)
                            }
                        } else {
                            val message = "failed to decrypt password"
                            log.info(message)

                            if (coroutine.isActive) {
                                coroutine.resumeWithException(Exception(message)) // TODO
                            }
                        }
                    } catch (ex: Exception) {
                        val message = "Deciphering failed " + ex.message
                        log.info(message)

                        if (coroutine.isActive) {
                            coroutine.resumeWithException(ex)
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    log.error("Authentication error [$errorCode] $errString")

                    if (coroutine.isActive) {
                        when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> coroutine.resume(null)
                            BiometricPrompt.ERROR_USER_CANCELED -> coroutine.resume(null)
                            BiometricPrompt.ERROR_LOCKOUT -> coroutine.resumeWithException(Exception(errString.toString()))
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> coroutine.resumeWithException(Exception(errString.toString()))
                            else -> {
                                Log.i("FINGERPRINT", "Something else")
                                coroutine.resume(null)
                            }
                        }
                    }
                }
            })
        }
    }

    fun clear() {
        if (isAvailable && isEnabled) {
            configuration.remindEnableFingerprint = true
        }
        fingerprintStorage.clear()
    }

    private fun isBiometricAvailable(context: Context): Boolean {
        return when (biometricManager.canAuthenticate(AUTH_TYPES)) {
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

    @Throws(GeneralSecurityException::class)
    private fun authenticate(
        activity: FragmentActivity,
        mode: Int,
        authListener: BiometricPrompt.AuthenticationCallback
    ) {
        try {
            val cipher = fingerprintStorage.createCipher(mode)

            if (cipher != null) {
                val crypto = CryptoObject(cipher)
                // TODO cancellation
                showPrompt(activity, crypto, authListener)
            } else {
                // TODO
            }
        } catch (t: Throwable) {
            if (t is KeyPermanentlyInvalidatedException) {
                //reset fingerprint
                clear()
                fingerprintStorage.setFingerprintKeyChanged()
            }
            log.warn("An error occurred", t)
            throw t
        }
    }

    private fun showPrompt(
        activity: FragmentActivity,
        cryptoObject: CryptoObject,
        callback: BiometricPrompt.AuthenticationCallback
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.touch_fingerprint_sensor))
            .setDescription(activity.getString(R.string.unlock_with_fingerprint)) // TODO: diff message
            .setNegativeButtonText(activity.getString(android.R.string.cancel)) // TODO: "use pin" message?
            .setAllowedAuthenticators(AUTH_TYPES)
            .build()

        prompt.authenticate(promptInfo, cryptoObject)
//    prompt.cancelAuthentication() TODO
    }
}