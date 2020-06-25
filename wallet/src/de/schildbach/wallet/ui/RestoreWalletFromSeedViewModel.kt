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
import de.schildbach.wallet.util.MnemonicCodeExt
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import org.bitcoinj.crypto.MnemonicException
import org.slf4j.LoggerFactory

class RestoreWalletFromSeedViewModel(application: Application) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(RestoreWalletFromSeedViewModel::class.java)

    private val walletApplication = application as WalletApplication

    internal val showRestoreWalletFailureAction = SingleLiveEvent<MnemonicException>()
    internal val startActivityAction = SingleLiveEvent<Intent>()

    fun restoreWalletFromSeed(words: MutableList<String>) {
        try {
            MnemonicCodeExt.getInstance().check(walletApplication, words)
        } catch (x: MnemonicException) {
            log.info("problem restoring wallet from seed: ", x)
            showRestoreWalletFailureAction.call(x)
            return
        }
        val wallet = WalletUtils.restoreWalletFromSeed(words, Constants.NETWORK_PARAMETERS)
        walletApplication.wallet = wallet
        log.info("successfully restored wallet from seed")
        walletApplication.configuration.disarmBackupSeedReminder()
        walletApplication.configuration.isRestoringBackup = true
        walletApplication.resetBlockchainState()
        startActivityAction.call(SetPinActivity.createIntent(getApplication(), R.string.set_pin_restore_wallet))
    }
}
