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

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.livedata.CheckPinLiveData
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.security.PinRetryController
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
open class CheckPinViewModel @Inject constructor(
    val walletData: WalletDataProvider,
    val configuration: Configuration,
    private val pinRetryController: PinRetryController,
    val biometricHelper: BiometricHelper,
    private val analytics: AnalyticsService
) : ViewModel() {

    val pin = StringBuilder()
    internal val checkPinLiveData = CheckPinLiveData(walletData.wallet!!)

    val isWalletLocked: Boolean
        get() = pinRetryController.isLocked

    var pinLength: Int
        get() = configuration.pinLength
        set(value) {
            configuration.pinLength = value
        }

    val isFingerprintEnabled: Boolean
        get() = biometricHelper.isEnabled

    open fun checkPin(pin: CharSequence) {
        checkPinLiveData.checkPin(pin.toString())
    }

    fun isLockedAfterAttempt(pin: String): Boolean {
        return pinRetryController.failedAttempt(pin)
    }

    fun getLockedMessage(resources: Resources): String {
        return pinRetryController.getWalletTemporaryLockedMessage(resources)
    }

    fun getRemainingAttemptsMessage(resources: Resources): String {
        return pinRetryController.getRemainingAttemptsMessage(resources)
    }

    fun resetFailedPinAttempts() {
        pinRetryController.clearPinFailPrefs()
    }

    fun getRemainingAttempts(): Int {
        return pinRetryController.remainingAttempts
    }

    fun getFailCount(): Int {
        return pinRetryController.failCount()
    }

    fun logError(error: Throwable, message: String? = null) {
        analytics.logError(error, message)
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }
}
