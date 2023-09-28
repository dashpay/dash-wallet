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
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.WalletFactory
import de.schildbach.wallet.service.WalletService
import de.schildbach.wallet.ui.util.SingleLiveEvent
import de.schildbach.wallet_test.R
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.slf4j.LoggerFactory
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class RestoreWalletFromFileViewModel @Inject constructor(
    val walletApplication: WalletApplication,
    private val walletFactory: WalletFactory,
    private val configuration: Configuration
) : ViewModel() {

    private val log = LoggerFactory.getLogger(RestoreWalletFromFileViewModel::class.java)

    internal val showRestoreWalletFailureAction = SingleLiveEvent<MnemonicException>()
    internal val showUpgradeWalletAction = SingleLiveEvent<Wallet>()
    internal val showUpgradeDisclaimerAction = SingleLiveEvent<Wallet>()
    internal val startActivityAction = SingleLiveEvent<Intent>()

    val backupUri = MutableLiveData<Uri>()
    val displayName = MutableLiveData<String>()
    val showFailureDialog = SingleLiveEvent<String>()
    val restoreWallet = SingleLiveEvent<Wallet>()
    val retryRequest = SingleLiveEvent<Void>()

    @Throws(IOException::class)
    fun restoreWalletFromUri(backupUri: Uri, password: String) : Wallet {
        val (wallet, fromKeys) = walletFactory.restoreFromFile(Constants.NETWORK_PARAMETERS, backupUri, password)
        if (fromKeys) {
            // when loading a keys file, a new recovery phrase is created and is different each time
            // The user will need to backup their passphrase
            configuration.armBackupReminder()
            configuration.armBackupSeedReminder()
        }
        return wallet
    }

    fun restoreWallet(wallet: Wallet, password: String?) {
        if (!wallet.hasKeyChain(Constants.BIP44_PATH) && wallet.isEncrypted) {
            showUpgradeWalletAction.call(wallet)
        } else {
            walletApplication.setWallet(wallet)
            log.info("successfully restored wallet from file")
            walletApplication.resetBlockchainState()
            startActivityAction.call(
                SetPinActivity.createIntent(
                    walletApplication,
                    R.string.set_pin_restore_wallet,
                    false,
                    password
                )
            )
        }
    }
}
