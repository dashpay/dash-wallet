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
import de.schildbach.wallet.util.FingerprintHelper
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory

class EncryptWalletLiveData(application: Application) : MutableLiveData<Resource<Wallet>>() {

    private val log = LoggerFactory.getLogger(EncryptWalletLiveData::class.java)

    private var encryptWalletTask: EncryptWalletTask? = null
    private var decryptWalletTask: DecryptWalletTask? = null
    private var changePinWalletTask: ChangePinWalletTask? = null

    private var scryptIterationsTarget: Int = Constants.SCRYPT_ITERATIONS_TARGET
    private var walletApplication = application as WalletApplication
    private var fingerprintHelper = FingerprintHelper(application)

    fun encrypt(password: String, scryptIterationsTarget: Int) {
        if (encryptWalletTask == null) {
            this.scryptIterationsTarget = scryptIterationsTarget
            encryptWalletTask = EncryptWalletTask()
            encryptWalletTask!!.execute(password)
        }
    }

    fun decrypt(password: String) {
        if (decryptWalletTask == null) {
            decryptWalletTask = DecryptWalletTask()
            decryptWalletTask!!.execute(password)
        }
    }

    fun changePassword(oldPassword: String, newPassword: String) {
        if (changePinWalletTask == null) {
            changePinWalletTask = ChangePinWalletTask()
            changePinWalletTask!!.execute(oldPassword, newPassword)
        }
    }

    @SuppressLint("StaticFieldLeak")
    internal inner class EncryptWalletTask : AsyncTask<Any, Void, Resource<Wallet>>() {

        override fun onPreExecute() {
            value = Resource.loading(null)
        }

        override fun doInBackground(vararg args: Any): Resource<Wallet> {
            val password = args[0] as String
            val wallet = walletApplication.wallet
            return try {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                // For the new key, we create a new key crypter according to the desired parameters.
                val keyCrypter = KeyCrypterScrypt(scryptIterationsTarget)
                val newKey = keyCrypter.deriveKey(password)
                wallet.encrypt(keyCrypter, newKey)

                walletApplication.saveWalletAndFinalizeInitialization()

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

    @SuppressLint("StaticFieldLeak")
    internal inner class ChangePinWalletTask : AsyncTask<String, Void, Resource<Wallet>>() {

        override fun onPreExecute() {
            value = Resource.loading(null)
        }

        override fun doInBackground(vararg args: String): Resource<Wallet> {
            val oldPassword = args[0]
            val newPassword = args[1]
            val wallet = walletApplication.wallet

            org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
            try {
                log.info("changing wallet spending password")

                val oldKey = wallet.keyCrypter!!.deriveKey(oldPassword)
                wallet.decrypt(oldKey)

                log.info("wallet successfully decrypted")

                val keyCrypter = KeyCrypterScrypt(scryptIterationsTarget)
                val newKey = keyCrypter.deriveKey(newPassword)
                wallet.encrypt(keyCrypter, newKey)

                //Clear fingerprint data
                fingerprintHelper.clear()   

                log.info("wallet successfully encrypted, using key derived by new spending password (${keyCrypter.scryptParameters.n} scrypt iterations)")

            } catch (x: KeyCrypterException) {
                Resource.error(x.message!!, null)
            }

            return Resource.success(wallet)
        }

        override fun onPostExecute(result: Resource<Wallet>) {
            value = result
            changePinWalletTask = null
        }
    }
}