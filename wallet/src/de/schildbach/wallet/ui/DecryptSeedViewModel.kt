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
) : CheckPinViewModel(walletData, configuration, pinRetryController, biometricHelper, analytics, securityFunctions) {

    private val securityGuard = SecurityGuard.getInstance()

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
        // Try primary PIN check (KeyStore-based) with automatic fallback
        val isPinCorrect = try {
            securityGuard.checkPin(pin)
        } catch (primaryException: Exception) {
            logError(primaryException, "Primary PIN check failed during seed decryption")

            // Primary failed - try PIN-based fallback recovery
            try {
                logEvent("seed_decryption_pin_fallback_recovery_attempt")
                val recoveredPassword = securityGuard.recoverPasswordWithPin(pin)

                // PIN-based recovery succeeded! This means:
                // 1. PIN is correct
                // 2. We recovered the wallet password
                // 3. Self-healing has already occurred
                logEvent("seed_decryption_pin_fallback_recovery_success")

                // Ensure PIN fallback is added if it wasn't already
                securityGuard.ensurePinFallback(pin)

                true // PIN is correct
            } catch (fallbackException: Exception) {
                logError(fallbackException, "PIN-based fallback recovery also failed during seed decryption")
                // Both primary and PIN-based fallback failed - PIN is incorrect
                false
            }
        }

        if (!isPinCorrect) {
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
