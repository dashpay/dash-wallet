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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.util.SingleLiveEvent
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class RestoreWalletFromFileViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val dashPayConfig: DashPayConfig
) : ViewModel() {

    private val log = LoggerFactory.getLogger(RestoreWalletFromFileViewModel::class.java)

    internal val showRestoreWalletFailureAction = SingleLiveEvent<MnemonicException>()
    internal val showUpgradeWalletAction = SingleLiveEvent<Wallet>()
    internal val showUpgradeDisclaimerAction = SingleLiveEvent<Wallet>()
    internal val startActivityAction = SingleLiveEvent<Intent>()

    val backupUri = MutableLiveData<Uri>()
    val displayName = MutableLiveData<String>()
    val showSuccessDialog = SingleLiveEvent<Boolean>()
    val showFailureDialog = SingleLiveEvent<String>()
    val restoreWallet = SingleLiveEvent<Wallet>()
    val retryRequest = SingleLiveEvent<Void>()

    fun restoreWalletFromFile(wallet: Wallet, password: String?) {
        if (!wallet.hasKeyChain(Constants.BIP44_PATH) && wallet.isEncrypted) {
            showUpgradeWalletAction.call(wallet)
        } else {
            walletApplication.setWallet(wallet)
            viewModelScope.launch { dashPayConfig.disableNotifications() }
            log.info("successfully restored wallet from file")
            walletApplication.resetBlockchainState()
            startActivityAction.call(SetPinActivity.createIntent(walletApplication,
                    R.string.set_pin_restore_wallet, false, password, onboarding = true))
        }
    }
}
