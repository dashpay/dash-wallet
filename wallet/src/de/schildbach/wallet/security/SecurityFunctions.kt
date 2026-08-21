/*
 * Copyright 2022 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.security

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.Constants
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.service.platform.sdk.PWFFI_ERROR_INVALID_PARAMETER
import de.schildbach.wallet.service.platform.sdk.SdkMessageSigner
import de.schildbach.wallet.ui.CheckPinDialog
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.SecuritySystemStatus
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.MessageSigningException
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SecurityFunctions @Inject constructor(
    private val walletData: WalletData,
    private val context: Context,
    private val biometricHelper: BiometricHelper,
    private val pinRetryController: PinRetryController,
    private val analyticsService: AnalyticsService,
    /**
     * The Kotlin SDK's message-signing surface, behind a seam so
     * [signMessage]'s error mapping is host-JVM testable (the real call
     * needs `libdash_sdk`).
     */
    private val messageSigner: SdkMessageSigner
): AuthenticationManager {
    private val log = LoggerFactory.getLogger(SendCoinsTaskRunner::class.java)
    private val status = MutableStateFlow(SecuritySystemStatus.HEALTHY)
    private var healthListenerInitialized = false

    /**
     * Low memory devices (currently 1GB or less) and 32 bit devices will require
     * fewer scrypt hashes on the PIN+salt (handled by dashj)
     *
     * @return The number of scrypt interations
     */
    val scryptIterationsTarget: Int by lazy {
        val is64bitABI = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val isLowRamDevice = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice

        if (isLowRamDevice || !is64bitABI) {
            Constants.SCRYPT_ITERATIONS_TARGET_LOWRAM
        } else {
            Constants.SCRYPT_ITERATIONS_TARGET
        }
    }

    override fun authenticate(
        activity: FragmentActivity,
        pinOnly: Boolean,
        callback: (String?) -> Unit
    ) {
        if (pinRetryController.isLocked) {
            val message = pinRetryController.getWalletTemporaryLockedMessage(activity.resources)
            AdaptiveDialog.create(
                R.drawable.ic_warning,
                activity.getString(R.string.wallet_lock_wallet_disabled),
                message,
                activity.getString(android.R.string.ok)
            ).show(activity)
            callback.invoke(null)
            return
        }

        if (!pinOnly && biometricHelper.isEnabled) {
            log.info("authenticate with biometric")

            biometricHelper.getPassword(activity, false) { pin, error ->
                if (error != null) {
                    log.info("biometric error: ${error.message}")

                    AdaptiveDialog.create(
                        R.drawable.ic_error,
                        activity.getString(R.string.fingerprint_not_recognized),
                        error.localizedMessage ?: activity.getString(R.string.default_error_msg),
                        activity.getString(R.string.button_dismiss),
                        activity.getString(R.string.authenticate_switch_to_pin)
                    ).show(activity) { usePin ->
                        if (usePin == true) {
                            log.info("authenticate with pin")
                            CheckPinDialog.show(activity) { pin ->
                                callback.invoke(pin)
                            }
                        }
                    }
                } else {
                    callback.invoke(pin)
                }
            }
        } else {
            log.info("authenticate with pin")
            CheckPinDialog.show(activity) { pin ->
                callback.invoke(pin)
            }
        }
    }

    override suspend fun authenticate(activity: FragmentActivity, pinOnly: Boolean): String? {
        return suspendCancellableCoroutine { coroutine ->
            try {
                authenticate(activity, pinOnly) { pin ->
                    if (coroutine.isActive) {
                        coroutine.resume(pin)
                    }
                }
            } catch (ex: Exception) {
                if (coroutine.isActive) {
                    coroutine.resumeWithException(ex)
                }
            }
        }
    }

    @Suppress("UnnecessaryVariable")
    suspend fun decryptSeed(password: String): DeterministicSeed = withContext(Dispatchers.Default) {
        val wallet = walletData.wallet!!
        val encryptionKey = deriveKey(wallet, password)
        val deterministicSeed = wallet.keyChainSeed.decrypt(wallet.keyCrypter, null, encryptionKey) // Takes time

        return@withContext deterministicSeed
    }

    /**
     * Sign [message] with the private key of [address], returning the
     * base64 signature. Backed by the Dash Platform Kotlin SDK
     * (`ManagedPlatformWallet.signMessage` via [SdkMessageSigner]); the
     * dashj key lookup this replaced is gone, with no fallback — the
     * codebase's fail-closed cutover philosophy (cf. `cutoverSendRoute` in
     * [SendCoinsTaskRunner]).
     *
     * No PIN prompt: the SDK's mnemonic resolver reads the seed Rust-side
     * from its own Keystore-backed storage, so unlike the dashj path this
     * neither retrieves the password nor derives the wallet encryption key.
     *
     * ## Failure contract
     *
     * Throws [MessageSigningException] on EVERY failure — in particular it
     * no longer returns `""` when the wallet does not own [address]. That
     * old silent-empty behavior made CrowdNode POST an unsigned request and
     * fail server-side with an opaque message; the caller now gets
     * [MessageSigningException.Reason.SIGNING_KEY_UNAVAILABLE] instead.
     *
     * ## Message contract
     *
     * [message] must be well-formed text: the SDK `require()`s that it
     * contain no unpaired UTF-16 surrogate, since a lone surrogate cannot
     * be encoded to the UTF-8 bytes that get hashed, and silently
     * substituting U+FFFD would sign something other than what the caller
     * passed. The only production callers are CrowdNode's `RegisterEmail`
     * (an email address) and `Withdrawal` (a decimal duffs string), so
     * neither can trip it. A violation surfaces as
     * [MessageSigningException.Reason.UNAVAILABLE].
     */
    override suspend fun signMessage(address: String, message: String): String =
        signMessageViaSdk(messageSigner, address, message)

    override fun getHealth(): SecuritySystemStatus {
        val securityGuard = SecurityGuard.getInstance()
        return when {
            securityGuard.isHealthyWithFallbacks -> SecuritySystemStatus.HEALTHY_WITH_FALLBACKS
            securityGuard.isHealthy -> SecuritySystemStatus.HEALTHY
            securityGuard.hasFallbacks() -> SecuritySystemStatus.FALLBACKS
            else -> SecuritySystemStatus.DEAD
        }
    }

    private val healthListener = SecurityGuard.HealthListener { securitySystemStatus ->
        if (status.value.isHealthy && !securitySystemStatus.isHealthy) {
            analyticsService.logError(Exception("Android Key Store corrupted"))
        }
        status.value = securitySystemStatus
    }

    override fun observeHealth(): Flow<SecuritySystemStatus> {
        if (!healthListenerInitialized) {
            val securityGuard = SecurityGuard.getInstance()
            securityGuard.addHealthListener(healthListener)
            healthListenerInitialized = true
        }
        return status
    }

    @Throws(KeyCrypterException::class)
    fun deriveKey(wallet: Wallet, password: String): KeyParameter {
        require(wallet.isEncrypted)
        val keyCrypter = wallet.keyCrypter!!

        // Key derivation takes time.
        var key = keyCrypter.deriveKey(password)

        // If the key isn't derived using the desired parameters, derive a new key.
        if (keyCrypter is KeyCrypterScrypt) {
            val scryptIterations = keyCrypter.scryptParameters.n

            if (scryptIterations != scryptIterationsTarget.toLong()) {
                log.info(
                    "upgrading scrypt iterations from {} to {}; re-encrypting wallet",
                    scryptIterations,
                    scryptIterationsTarget
                )
                val newKeyCrypter = KeyCrypterScrypt(scryptIterationsTarget)
                val newKey: KeyParameter = newKeyCrypter.deriveKey(password)

                // Re-encrypt wallet with new key.
                try {
                    wallet.changeEncryptionKey(newKeyCrypter, key, newKey)
                    key = newKey
                    log.info("scrypt upgrade succeeded")
                } catch (x: KeyCrypterException) {
                    log.info("scrypt upgrade failed: {}", x.message)
                }
            }
        }

        // Hand back the (possibly changed) encryption key.
        return key
    }
}

// ── Message signing (host-testable) ───────────────────────────────────

private val signingLog = LoggerFactory.getLogger("de.schildbach.wallet.security.signMessage")

/**
 * The whole of [SecurityFunctions.signMessage], lifted out of the class so
 * it is host-JVM testable: [SecurityFunctions] itself cannot be constructed
 * in a unit test, because [PinRetryController]'s static initializer builds
 * an Android-dependent singleton that fails to initialize off-device. Same
 * "pure logic as a top-level `internal fun`" convention as
 * `classifyCoreSendFailure` in
 * [de.schildbach.wallet.service.platform.sdk.SdkL1SendService]; the class
 * method is a one-line delegation.
 *
 * Maps every SDK failure onto [MessageSigningException] — see
 * [SecurityFunctions.signMessage] for the full contract.
 */
internal suspend fun signMessageViaSdk(
    signer: SdkMessageSigner,
    address: String,
    message: String
): String {
    return try {
        signer.signMessage(address, message)
    } catch (ex: CancellationException) {
        // Never wrap cancellation — a cancelled scope must stay cancelled.
        throw ex
    } catch (ex: DashSdkError.PlatformWallet.SigningKeyUnavailable) {
        // FFI code 31: the bound wallet holds no private key for this
        // address. The one case the dashj implementation answered with an
        // empty string.
        //
        // TODO(signing): this may be TRANSIENT after a wallet restore. The
        //  SDK wallet derives its own address set as it syncs, so until that
        //  catches up with dashj's discovery an address dashj already knows
        //  can be genuinely absent SDK-side, and a CrowdNode signature for it
        //  fails until the sync completes. A widen-the-lookahead-and-retry
        //  heal is under consideration on the integration line; whether this
        //  is a real limitation at all is still disputed. Deliberately NOT
        //  worked around here — a retry or fallback added at this layer would
        //  mask the distinction between "not ours yet" and "not ours", which
        //  is exactly the distinction the fail-closed contract depends on.
        signingLog.error("signMessage: no signing key for address", ex)
        throw MessageSigningException(
            MessageSigningException.Reason.SIGNING_KEY_UNAVAILABLE,
            "the wallet cannot sign for this address",
            ex
        )
    } catch (ex: DashSdkError.PlatformWallet.Generic) {
        // Platform-wallet native code 2 (`ErrorInvalidParameter`) has no
        // dedicated Kotlin type — DashSdkError's code mapping falls through
        // to Generic(2, …) — and is how a malformed address is rejected
        // Rust-side. Any other Generic code is an unclassified SDK failure.
        val reason = if (ex.nativeCode == PWFFI_ERROR_INVALID_PARAMETER) {
            MessageSigningException.Reason.INVALID_ADDRESS
        } else {
            MessageSigningException.Reason.UNAVAILABLE
        }
        signingLog.error("signMessage: SDK rejected the request (code ${ex.nativeCode})", ex)
        throw MessageSigningException(reason, "message signing failed", ex)
    } catch (ex: Exception) {
        // SDK not startable, no single bound wallet, an EMPTY address or a
        // message with an unpaired surrogate (both caught by the SDK's own
        // client-side `require`s, which raise IllegalArgumentException
        // rather than an FFI code), or any other signing failure.
        signingLog.error("signMessage: signing unavailable", ex)
        throw MessageSigningException(
            MessageSigningException.Reason.UNAVAILABLE,
            "message signing is unavailable",
            ex
        )
    }
}
