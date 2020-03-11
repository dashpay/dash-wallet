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
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory

class RestoreWalletFromFileViewModel(application: Application) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(RestoreWalletFromFileViewModel::class.java)

    private val walletApplication = application as WalletApplication

    internal val showRestoreWalletFailureAction = SingleLiveEvent<MnemonicException>()
    internal val showUpgradeWalletAction = SingleLiveEvent<Wallet>()
    internal val showUpgradeDisclaimerAction = SingleLiveEvent<Wallet>()
    internal val startActivityAction = SingleLiveEvent<Intent>()

    fun restoreWalletFromFile(wallet: Wallet, password: String?) {
        if (!wallet.hasKeyChain(Constants.BIP44_PATH) && wallet.isEncrypted) {
            showUpgradeWalletAction.call(wallet)
        } else {
            walletApplication.wallet = wallet
            log.info("successfully restored wallet from file")
            walletApplication.resetBlockchainState()
            startActivityAction.call(SetPinActivity.createIntent(getApplication(),
                    R.string.set_pin_restore_wallet, false, password))
        }
    }
}
