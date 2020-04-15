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

package de.schildbach.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.CheckPinLiveData
import de.schildbach.wallet.livedata.EncryptWalletLiveData
import org.slf4j.LoggerFactory

class SetPinViewModel(application: Application) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(SetPinViewModel::class.java)

    private val walletApplication = application as WalletApplication

    val pin = arrayListOf<Int>()
    var oldPinCache: String? = null

    internal val startNextActivity = SingleLiveEvent<Boolean>()
    internal val encryptWalletLiveData = EncryptWalletLiveData(application)
    internal val checkPinLiveData = CheckPinLiveData(application)

    fun setPin(pin: ArrayList<Int>) {
        this.pin.clear()
        this.pin.addAll(pin)
    }

    fun getPinAsString(): String {
        return pin.joinToString("")
    }

    fun savePinAndEncrypt() {
        val pin = getPinAsString()
        savePinAndEncrypt(pin, true)
    }

    fun savePinAndEncrypt(pin: String, initialize: Boolean) {
        encryptWalletLiveData.savePin(pin)
        encryptWallet(initialize)
    }

    private fun encryptWallet(initialize: Boolean) {
        if (!walletApplication.wallet.isEncrypted) {
            encryptWalletLiveData.encrypt(walletApplication.scryptIterationsTarget(), initialize)
        } else {
            log.warn("Trying to encrypt already encrypted wallet")
        }
    }

    fun decryptKeys() {
        decryptKeys(getPinAsString())
    }

    fun decryptKeys(password: String?) {
        if (walletApplication.wallet.isEncrypted) {
            encryptWalletLiveData.decrypt(password)
        } else {
            log.warn("Trying to decrypt unencrypted wallet")
        }
    }

    fun initWallet() {
        startNextActivity.call(walletApplication.configuration.getRemindBackupSeed())
    }

    fun checkPin() {
        val password = getPinAsString()
        checkPinLiveData.checkPin(password)
    }

    fun changePin() {
        val newPassword = getPinAsString()
        encryptWalletLiveData.changePassword(oldPinCache!!, newPassword)
    }
}
