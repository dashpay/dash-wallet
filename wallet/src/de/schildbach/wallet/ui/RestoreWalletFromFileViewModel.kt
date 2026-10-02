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

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.WalletFactory
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.util.SingleLiveEvent
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.slf4j.LoggerFactory
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class RestoreWalletFromFileViewModel @Inject constructor(
    val walletApplication: WalletApplication,
    private val walletFactory: WalletFactory,
    private val configuration: Configuration,
    private val dashPayConfig: DashPayConfig
) : ViewModel() {

    private val log = LoggerFactory.getLogger(RestoreWalletFromFileViewModel::class.java)

    internal val showUpgradeWalletAction = SingleLiveEvent<Wallet>()
    internal val showUpgradeDisclaimerAction = SingleLiveEvent<Wallet>()
    internal val startActivityAction = SingleLiveEvent<Intent>()

    val backupUri = MutableLiveData<Uri>()
    val displayName = MutableLiveData<String>()
    val showFailureDialog = SingleLiveEvent<String>()
    val restoreWallet = SingleLiveEvent<Wallet>()
    val retryRequest = SingleLiveEvent<Void>()

    /** The app refused the restored wallet (see WalletApplication.isWalletReplacementRefused). */
    val walletReplacementRefused = SingleLiveEvent<Unit>()

    /**
     * The wallet last restored from a keys file, whose new recovery phrase
     * must be backed up once the wallet is accepted ([restoreWallet]).
     */
    private var keysFileWallet: Wallet? = null

    @Throws(IOException::class)
    fun restoreWalletFromUri(backupUri: Uri, password: String) : Wallet {
        val (wallet, fromKeys) = walletFactory.restoreFromFile(Constants.NETWORK_PARAMETERS, backupUri, password)
        keysFileWallet = wallet.takeIf { fromKeys }
        return wallet
    }

    fun restoreWallet(wallet: Wallet, password: String?) {
        if (!wallet.hasKeyChain(Constants.BIP44_PATH) && wallet.isEncrypted) {
            showUpgradeWalletAction.call(wallet)
        } else if (!walletApplication.setWallet(wallet)) {
            log.warn("restored wallet refused: a wallet reset is unfinished or unverified")
            walletReplacementRefused.call(Unit)
        } else {
            if (wallet === keysFileWallet) {
                // when loading a keys file, a new recovery phrase is created and is different each time
                // The user will need to backup their passphrase. Only once the wallet is
                // accepted: a refused one must not arm reminders for the existing wallet.
                configuration.armBackupReminder()
                configuration.armBackupSeedReminder()
            }
            keysFileWallet = null
            viewModelScope.launch { dashPayConfig.disableNotifications() }
            log.info("successfully restored wallet from file")
            walletApplication.resetBlockchainState()
            startActivityAction.call(
                SetPinActivity.createIntent(
                    walletApplication,
                    R.string.set_pin_restore_wallet,
                    false,
                    password,
                    onboarding = true,
                    onboardingPath = OnboardingPath.RestoreFile
                )
            )
        }
    }
}
