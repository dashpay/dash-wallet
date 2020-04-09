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
import android.app.Application
import android.os.AsyncTask
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet.util.FingerprintHelper
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory

class EncryptWalletLiveData(application: Application) : MutableLiveData<Resource<Wallet>>() {

    private val log = LoggerFactory.getLogger(EncryptWalletLiveData::class.java)

    private var encryptWalletTask: EncryptWalletTask? = null
    private var decryptWalletTask: DecryptWalletTask? = null

    private var scryptIterationsTarget: Int = Constants.SCRYPT_ITERATIONS_TARGET
    private var walletApplication = application as WalletApplication
    private var fingerprintHelper = FingerprintHelper(application)

    private val securityGuard = SecurityGuard()

    fun savePin(pin: String) {
        securityGuard.savePin(pin)
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
        value = if (securityGuard.checkPin(oldPin)) {
            securityGuard.savePin(newPin)
            fingerprintHelper.clear()
            Resource.success(walletApplication.wallet)
        } else {
            Resource.error("", null)
        }
    }

    @SuppressLint("StaticFieldLeak")
    internal inner class EncryptWalletTask : AsyncTask<Any, Void, Resource<Wallet>>() {

        override fun onPreExecute() {
            value = Resource.loading(null)
        }

        override fun doInBackground(vararg args: Any): Resource<Wallet> {
            val initialize = args[0] as Boolean
            val wallet = walletApplication.wallet

            val password = securityGuard.generateRandomPassword()

            return try {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                // For the new key, we create a new key crypter according to the desired parameters.
                val keyCrypter = KeyCrypterScrypt(scryptIterationsTarget)
                val newKey = keyCrypter.deriveKey(password)
                wallet.encrypt(keyCrypter, newKey)

                if(initialize) {
                    walletApplication.saveWalletAndFinalizeInitialization()
                }

                securityGuard.savePassword(password)

                log.info("wallet successfully encrypted, using key derived by new spending password (${keyCrypter.scryptParameters.n} scrypt iterations)")

                Resource.success(wallet)
            } catch (x: KeyCrypterException) {
                Resource.error(x.message!!, null)
            }
        }

        override fun onPostExecute(result: Resource<Wallet>) {
            value = result
            encryptWalletTask = null
        }
    }

    @SuppressLint("StaticFieldLeak")
    internal inner class DecryptWalletTask : AsyncTask<String, Void, Resource<Wallet>>() {

        override fun onPreExecute() {
            value = Resource.loading(null)
        }

        override fun doInBackground(vararg args: String): Resource<Wallet> {
            val password = args[0]
            val wallet = walletApplication.wallet
            return try {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                val key = wallet.keyCrypter!!.deriveKey(password)
                wallet.decrypt(key)
                Resource.success(wallet)
            } catch (x: KeyCrypterException) {
                Resource.error(x.message!!, null)
            }
        }

        override fun onPostExecute(result: Resource<Wallet>) {
            value = result
            decryptWalletTask = null
        }
    }
}