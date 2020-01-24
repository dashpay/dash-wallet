/*
 * Copyright 2020 Dash Core Group
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

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet.ui.send.DecryptSeedTask
import de.schildbach.wallet.ui.send.DeriveKeyTask
import org.bitcoinj.wallet.DeterministicSeed
import org.bouncycastle.crypto.params.KeyParameter

/**
 * @author:  Eric Britten
 */

class DecryptSeedLiveData(application: Application) : MutableLiveData<Resource<Pair<DeterministicSeed?, String?>>>() {

    val backgroundHandler: Handler

    init {
        val backgroundThread = HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private var decryptSeedTask: DecryptSeedTask? = null
    private var deriveKeyTask: DeriveKeyTask? = null

    private var walletApplication = application as WalletApplication

    private val securityGuard = SecurityGuard()

    fun checkPin(pin: String) {
        if (securityGuard.checkPin(pin)) {
            decryptSeed(pin, securityGuard.retrievePassword())
        } else {
            value = Resource.error("wrong pin", Pair(walletApplication.wallet.keyChainSeed, pin))
        }
    }

    private fun decryptSeed(pin: String, password: String) {
        if (deriveKeyTask == null) {
            deriveKeyTask = object : DeriveKeyTask(backgroundHandler, walletApplication.scryptIterationsTarget()) {

                override fun onSuccess(encryptionKey: KeyParameter, changed: Boolean) {
                    deriveKeyTask = null
                    if (decryptSeedTask == null) {
                        decryptSeedTask = object : DecryptSeedTask(backgroundHandler) {

                            override fun onBadPassphrase() {
                                value = Resource.error("wrong password", Pair(walletApplication.wallet.keyChainSeed, pin))
                                decryptSeedTask = null
                            }

                            override fun onSuccess(seed : DeterministicSeed) {
                                value = Resource.success(Pair(seed, pin))
                                decryptSeedTask = null
                            }
                        }
                        value = Resource.loading(null)
                        decryptSeedTask!!.decryptSeed(walletApplication.wallet.keyChainSeed, walletApplication.wallet.keyCrypter, encryptionKey)
                    }
                }
            }
            value = Resource.loading(null)
            deriveKeyTask!!.deriveKey(walletApplication.wallet, password)
        }
    }
}