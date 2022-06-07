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

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.CheckPinLiveData
import de.schildbach.wallet.livedata.EncryptWalletLiveData
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class SetPinViewModel @Inject constructor(
    private val walletApplication: WalletApplication
): ViewModel() {

    private val log = LoggerFactory.getLogger(SetPinViewModel::class.java)

    val pin = arrayListOf<Int>()
    var oldPinCache: String? = null
    val isWalletEncrypted
        get() = walletApplication.wallet!!.isEncrypted

    internal val startNextActivity = SingleLiveEvent<Boolean>()
    internal val encryptWalletLiveData = EncryptWalletLiveData(walletApplication)
    internal val checkPinLiveData = CheckPinLiveData(walletApplication.wallet!!)

    fun setPin(pin: ArrayList<Int>) {
        this.pin.clear()
        this.pin.addAll(pin)
    }

    fun getPinAsString(): String {
        return pin.joinToString("")
    }

    fun savePinAndEncrypt(initialize: Boolean) {
        val pin = getPinAsString()
        savePinAndEncrypt(pin, initialize)
    }

    fun savePinAndEncrypt(pin: String, initialize: Boolean) {
        encryptWalletLiveData.savePin(pin)
        encryptWallet(initialize)
    }

    private fun encryptWallet(initialize: Boolean) {
        if (!walletApplication.wallet!!.isEncrypted) {
            encryptWalletLiveData.encrypt(walletApplication.scryptIterationsTarget(), initialize)
        } else {
            log.warn("Trying to encrypt already encrypted wallet")
        }
    }

    fun decryptKeys() {
        decryptKeys(getPinAsString())
    }

    fun decryptKeys(password: String?) {
        if (walletApplication.wallet!!.isEncrypted) {
            encryptWalletLiveData.decrypt(password)
        } else {
            log.warn("Trying to decrypt unencrypted wallet")
        }
    }

    fun initWallet() {
        startNextActivity.call(walletApplication.configuration.remindBackupSeed)
    }

    fun checkPin() {
        val password = getPinAsString()
        checkPinLiveData.checkPin(password)
    }

    fun changePin() {
        val newPassword = getPinAsString()
        encryptWalletLiveData.changePassword(oldPinCache!!, newPassword)
        walletApplication.configuration.updateLastEncryptKeysTime()
    }
}
