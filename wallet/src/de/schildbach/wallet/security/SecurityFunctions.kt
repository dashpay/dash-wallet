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

import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.ui.CheckPinDialog
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet_test.R
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bitcoinj.core.Address
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SecurityFunctions @Inject constructor(
    private val walletApplication: WalletApplication,
    private val biometricHelper: BiometricHelper,
    private val pinRetryController: PinRetryController
): AuthenticationManager {
    private val log = LoggerFactory.getLogger(SendCoinsTaskRunner::class.java)

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

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun signMessage(address: Address, message: String): String {
        val securityGuard = SecurityGuard()
        val password = securityGuard.retrievePassword()
        val keyParameter = deriveKey(walletApplication.wallet!!, password, walletApplication.scryptIterationsTarget())
        val key = walletApplication.wallet?.findKeyFromAddress(address)
        return key?.signMessage(message, keyParameter) ?: ""
    }

    @Throws(KeyCrypterException::class)
    fun deriveKey(
        wallet: Wallet,
        password: String,
        scryptIterationsTarget: Int
    ): KeyParameter {
        require(wallet.isEncrypted)
        val keyCrypter = wallet.keyCrypter!!

        // Key derivation takes time.
        var key = keyCrypter.deriveKey(password)

        // If the key isn't derived using the desired parameters, derive a new key.
        if (keyCrypter is KeyCrypterScrypt) {
            val scryptIterations = keyCrypter.scryptParameters.n

            if (scryptIterations != scryptIterationsTarget.toLong()) {
                log.info("upgrading scrypt iterations from {} to {}; re-encrypting wallet",
                    scryptIterations, scryptIterationsTarget)
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