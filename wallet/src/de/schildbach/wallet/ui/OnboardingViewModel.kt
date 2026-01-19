/*
 * Copyright 2019 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui

import androidx.lifecycle.ViewModel
import android.content.Intent
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.WalletFactory
import de.schildbach.wallet.ui.util.SingleLiveEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.Configuration
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val walletFactory: WalletFactory,
    private val configuration: Configuration,
    val analytics: AnalyticsService,
    val platformRepo: PlatformRepo
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(OnboardingViewModel::class.java)
    }

    internal val showToastAction = SingleLiveEvent<String>()
    internal val finishCreateNewWalletAction = SingleLiveEvent<Unit>()
    internal val finishUnecryptedWalletUpgradeAction = SingleLiveEvent<Unit>()
    internal val startActivityAction = SingleLiveEvent<Intent>()

    fun createNewWallet(seedWordCount: Int) {
        analytics.logEvent(AnalyticsConstants.Onboarding.NEW_WALLET, mapOf())
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                walletApplication.initEnvironmentIfNeeded()
        val wallet = walletFactory.create(Constants.NETWORK_PARAMETERS, seedWordCount)
                log.info("successfully created new wallet")
                walletApplication.setWallet(wallet)
                configuration.armBackupSeedReminder()
            }
            analytics.logEvent(AnalyticsConstants.Invites.NEW_WALLET, mapOf())
            finishCreateNewWalletAction.call(Unit)
        }
    }

    fun upgradeUnencryptedWallet() {
        log.info("upgrading previously created wallet from version 6 or before")
        viewModelScope.launch {
            // Does this wallet use BIP44
            if (!walletApplication.isWalletUpgradedToBIP44) {
                walletApplication.wallet!!.addKeyChain(Constants.BIP44_PATH)
            }
            (walletApplication.wallet as WalletEx).initializeCoinJoin(0)
            configuration.armBackupSeedReminder()

            finishUnecryptedWalletUpgradeAction.call(Unit)
        }
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }
}
