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
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(OnboardingViewModel::class.java)

    private val walletApplication = application as WalletApplication

    internal val showToastAction = SingleLiveEvent<String>()
    internal val showRestoreWalletFailureAction = SingleLiveEvent<MnemonicException>()
    internal val finishCreateNewWalletAction = SingleLiveEvent<Unit>()
    internal val finishUnecryptedWalletUpgradeAction = SingleLiveEvent<Unit>()

    fun createNewWallet() {
        walletApplication.initEnvironmentIfNeeded()
        val wallet = Wallet(Constants.NETWORK_PARAMETERS)
        log.info("successfully created new wallet")
        walletApplication.wallet = wallet
        walletApplication.configuration.armBackupSeedReminder()
        finishCreateNewWalletAction.call(Unit)
    }

    fun upgradeUnencryptedWallet() {
        log.info("upgrading previously created wallet from version 6 or before")
        viewModelScope.launch {
            // Does this wallet use BIP44
            if (!walletApplication.isWalletUpgradedtoBIP44) {
                walletApplication.wallet.addKeyChain(Constants.BIP44_PATH)
            }
            walletApplication.configuration.armBackupSeedReminder()

            finishUnecryptedWalletUpgradeAction.call(Unit)
        }
    }
}
