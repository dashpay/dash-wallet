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

import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.EncryptWalletLiveData
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.PinRetryController
import de.schildbach.wallet.ui.util.SingleLiveEvent
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class SetPinViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    walletData: WalletDataProvider,
    configuration: Configuration,
    pinRetryController: PinRetryController,
    biometricHelper: BiometricHelper,
    analytics: AnalyticsService,
    private val securityFunctions: SecurityFunctions
): CheckPinViewModel(walletData, configuration, pinRetryController, biometricHelper, analytics) {

    private val log = LoggerFactory.getLogger(SetPinViewModel::class.java)

    val pinArray = arrayListOf<Int>()
    var oldPinCache: String? = null
    val isWalletEncrypted
        get() = walletData.wallet!!.isEncrypted

    internal val startNextActivity = SingleLiveEvent<Boolean>()
    internal val encryptWalletLiveData = EncryptWalletLiveData(walletApplication, biometricHelper)

    fun setPin(pin: ArrayList<Int>) {
        this.pinArray.clear()
        this.pinArray.addAll(pin)
    }

    fun getPinAsString(): String {
        return pinArray.joinToString("")
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
        if (!walletData.wallet!!.isEncrypted) {
            encryptWalletLiveData.encrypt(securityFunctions.scryptIterationsTarget, initialize)
        } else {
            log.warn("Trying to encrypt already encrypted wallet")
        }
    }

    fun decryptKeys() {
        decryptKeys(getPinAsString())
    }

    fun decryptKeys(password: String?) {
        if (walletData.wallet!!.isEncrypted) {
            encryptWalletLiveData.decrypt(password)
        } else {
            log.warn("Trying to decrypt unencrypted wallet")
        }
    }

    fun initWallet() {
        walletApplication.saveWalletAndFinalizeInitialization()
        startNextActivity.call(configuration.remindBackupSeed)
    }

    fun checkPin() {
        val password = getPinAsString()
        checkPinLiveData.checkPin(password)
    }

    fun changePin() {
        val newPassword = getPinAsString()
        encryptWalletLiveData.changePassword(oldPinCache!!, newPassword)
        configuration.updateLastEncryptKeysTime()
    }

    fun startAutoLogout() {
        walletApplication.autoLogout.apply {
            maybeStartAutoLogoutTimer()
            keepLockedUntilPinEntered = false
        }
    }
}
