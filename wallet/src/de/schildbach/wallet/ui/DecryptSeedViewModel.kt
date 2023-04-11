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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.PinRetryController
import de.schildbach.wallet.security.SecurityGuard
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

/**
 * @author:  Eric Britten
 */
@HiltViewModel
class DecryptSeedViewModel @Inject constructor(
    walletData: WalletDataProvider,
    pinRetryController: PinRetryController,
    configuration: Configuration,
    biometricHelper: BiometricHelper,
    analytics: AnalyticsService,
    private val securityFunctions: SecurityFunctions
) : CheckPinViewModel(walletData, configuration, pinRetryController, biometricHelper, analytics) {

    private val securityGuard = SecurityGuard()

    private val _seed: MutableLiveData<Array<String>> = MutableLiveData()
    val seed: LiveData<Array<String>>
        get() = _seed

    fun init(seed: Array<String>) {
        _seed.postValue(seed)
    }

    suspend fun init(pin: String) {
        _seed.postValue(decryptSeed(pin))
    }

    suspend fun decryptSeed(pin: String): Array<String> {
        if (!securityGuard.checkPin(pin)) {
            throw IllegalArgumentException("wrong pin")
        }

        val password = securityGuard.retrievePassword()
        val decryptedSeed = securityFunctions.decryptSeed(password)

        return decryptedSeed.mnemonicCode!!.toTypedArray()
    }

    fun onBackedUp() {
        configuration.disarmBackupSeedReminder()
        configuration.setLastBackupSeedTime()
    }
}
