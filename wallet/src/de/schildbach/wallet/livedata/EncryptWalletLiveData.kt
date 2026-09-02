/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.livedata

import android.annotation.SuppressLint
import android.os.AsyncTask
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.security.SecurityGuard
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.BuildConfig
import org.slf4j.LoggerFactory

class EncryptWalletLiveData(
    private val walletApplication: WalletApplication,
    private val biometricHelper: BiometricHelper
) : MutableLiveData<Resource<Wallet>>() {

    companion object {
        private val log = LoggerFactory.getLogger(EncryptWalletLiveData::class.java)

        /**
         * Encrypts [wallet] with a key derived from [password], then persists the password
         * via [savePassword]. If persisting fails, the wallet is left encrypted with a
         * password that was never stored — it could not be spent and a retry with the same
         * PIN could not re-encrypt — so the encryption is rolled back before rethrowing,
         * returning the wallet to the state it had before the call.
         */
        @JvmStatic
        internal fun encryptAndSavePassword(
            wallet: Wallet,
            keyCrypter: KeyCrypterScrypt,
            password: String,
            savePassword: (String) -> Unit
        ) {
            val newKey = keyCrypter.deriveKey(password)
            wallet.encrypt(keyCrypter, newKey)
            try {
                savePassword(password)
            } catch (x: Exception) {
                try {
                    wallet.decrypt(newKey)
                    log.warn("rolled back wallet encryption after failing to save the spending password")
                } catch (rollbackError: Exception) {
                    log.error("could not roll back wallet encryption after savePassword failure", rollbackError)
                }
                throw x
            }
        }
    }

    private var encryptWalletTask: EncryptWalletTask? = null
    private var decryptWalletTask: DecryptWalletTask? = null

    private var scryptIterationsTarget: Int = Constants.SCRYPT_ITERATIONS_TARGET
    private val securityGuard = SecurityGuard.getInstance()

    val isEncrypting: Boolean
        get() = encryptWalletTask != null

    /**
     * will save the PIN and also will save the fallbacks
     * assumes the wallet is not encrypted.
     */
    fun savePin(pin: String) {
        val wallet = walletApplication.wallet!!
        if (wallet.isEncrypted) {
            // a repeated invocation can race the encrypt task (UI re-entry); if the
            // wallet was already encrypted using this same PIN, the first invocation
            // did all the work and this one is a harmless no-op
            val alreadySavedSamePin = try {
                securityGuard.checkPin(pin)
            } catch (x: Exception) {
                // checkPin returns false only for a real PIN mismatch; a throw means the
                // check itself failed (keystore/IO), so don't misreport it as a mismatch
                throw IllegalStateException(
                    "wallet is already encrypted and the saved PIN could not be verified", x
                )
            }
            if (alreadySavedSamePin) {
                log.warn("savePin called again after the wallet was encrypted with the same PIN, ignoring")
                return
            }
            error("the wallet should not be encrypted")
        }
        securityGuard.removeKeys()
        securityGuard.savePin(pin)
        securityGuard.ensurePinFallback(pin)
        val words = wallet.keyChainSeed.mnemonicCode
        securityGuard.ensureMnemonicFallbacks(words)
    }

    fun encrypt(scryptIterationsTarget: Int, initialize: Boolean = true) {
        if (encryptWalletTask == null) {
            this.scryptIterationsTarget = scryptIterationsTarget
            encryptWalletTask = EncryptWalletTask()
            encryptWalletTask!!.execute(initialize)
        }
    }

    fun decrypt(password: String?) {
        if (decryptWalletTask == null) {
            val pass = password ?: securityGuard.retrievePassword()
            decryptWalletTask = DecryptWalletTask()
            decryptWalletTask!!.execute(pass)
        }
    }

    fun changePassword(oldPin: String, newPin: String) {
        // Try primary PIN check (KeyStore-based) with automatic fallback
        val isPinCorrect = try {
            securityGuard.checkPin(oldPin)
        } catch (primaryException: Exception) {
            log.warn("Primary PIN check failed during password change: ${primaryException.message}")

            // Primary failed - try PIN-based fallback recovery
            try {
                log.info("Attempting PIN-based fallback recovery for password change")
                val recoveredPassword = securityGuard.recoverPasswordWithPin(oldPin)

                // PIN-based recovery succeeded! This means old PIN is correct
                // Self-healing has already occurred
                log.info("PIN-based fallback recovery succeeded during password change")

                // Ensure PIN fallback is added if it wasn't already
                securityGuard.ensurePinFallback(oldPin)

                true // Old PIN is correct
            } catch (fallbackException: Exception) {
                log.error("PIN-based fallback recovery also failed during password change: ${fallbackException.message}")
                // Both primary and PIN-based fallback failed - old PIN is incorrect
                false
            }
        }

        value = if (isPinCorrect) {
            securityGuard.savePin(newPin)
            // Existing fallbacks are tied to the old PIN; remove them so the
            // ensure* calls below recreate them instead of returning early
            securityGuard.removeFallbacks()
            securityGuard.ensurePinFallback(newPin)
            val wallet = walletApplication.wallet!!
            val key = wallet.keyCrypter!!.deriveKey(securityGuard.retrievePassword())
            val words = walletApplication.wallet!!.keyChainSeed.decrypt(wallet.keyCrypter, "", key).mnemonicCode
            securityGuard.ensureMnemonicFallbacks(words)
            biometricHelper.clearBiometricInfo()
            Resource.success(walletApplication.wallet)
        } else {
            Resource.error("", null)
        }
    }

    @SuppressLint("StaticFieldLeak")
    internal inner class EncryptWalletTask : AsyncTask<Any, Void, Resource<Wallet>>() {

        @Deprecated("Deprecated in Java")
        override fun onPreExecute() {
            value = Resource.loading(null)
        }

        @Deprecated("Deprecated in Java")
        override fun doInBackground(vararg args: Any): Resource<Wallet> {
            val wallet = walletApplication.wallet as WalletEx
            val password = securityGuard.generateRandomPassword()
            return try {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                // For the new key, we create a new key crypter according to the desired parameters.
                val keyCrypter = KeyCrypterScrypt(scryptIterationsTarget)
                encryptAndSavePassword(wallet, keyCrypter, password) { securityGuard.savePassword(it) }

                log.info("wallet successfully encrypted, using key derived by new spending password (${keyCrypter.scryptParameters.n} scrypt iterations)")

                Resource.success(wallet)
            } catch (x: KeyCrypterException) {
                log.error("There was a problem encrypting the wallet", x)
                Resource.error(x.message ?: "Unknown encryption error")
            } catch (x: Exception) {
                log.error("There was a problem creating the wallet", x)
                Resource.error(
                    x.message ?: "Unknown error when encrypting wallet during onboarding"
                )
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onPostExecute(result: Resource<Wallet>) {
            value = result
            encryptWalletTask = null
        }
    }

    @SuppressLint("StaticFieldLeak")
    internal inner class DecryptWalletTask : AsyncTask<String, Void, Resource<Wallet>>() {

        @Deprecated("Deprecated in Java")
        override fun onPreExecute() {
            value = Resource.loading(null)
        }

        @Deprecated("Deprecated in Java")
        override fun doInBackground(vararg args: String): Resource<Wallet> {
            val password = args[0]
            val wallet = walletApplication.wallet!!
            return try {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                val key = wallet.keyCrypter!!.deriveKey(password)
                wallet.decrypt(key)
                Resource.success(wallet)
            } catch (x: KeyCrypterException) {
                Resource.error(x.message!!, null)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onPostExecute(result: Resource<Wallet>) {
            value = result
            decryptWalletTask = null
        }
    }
}