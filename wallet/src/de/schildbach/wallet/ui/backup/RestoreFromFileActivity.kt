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

package de.schildbach.wallet.ui.backup

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.AbstractPINDialogFragment
import de.schildbach.wallet.ui.EncryptNewKeyChainDialogFragment
import de.schildbach.wallet.ui.RestoreWalletFromFileViewModel
import de.schildbach.wallet.ui.RestoreWalletFromSeedDialogFragment
import de.schildbach.wallet.ui.SET_PIN_REQUEST_CODE
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog
import de.schildbach.wallet_test.R
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.SecureActivity
import org.dash.wallet.common.ui.BaseAlertDialogBuilder


@Suppress("DEPRECATION")
@SuppressLint("Registered")
open class RestoreFromFileActivity : SecureActivity(), AbstractPINDialogFragment.WalletProvider {

    companion object {
        const val DIALOG_RESTORE_WALLET_PERMISSION = 1

        const val DIALOG_RESTORE_WALLET = 2

        const val REQUEST_CODE_RESTORE_WALLET = 1
    }

    private lateinit var viewModel: RestoreWalletFromFileViewModel
    //private lateinit var sharedViewModel: RestoreWalletViewModel

    private lateinit var walletApplication: WalletApplication
    private lateinit var walletBuffer: Wallet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[RestoreWalletFromFileViewModel::class.java]
        //sharedViewModel = ViewModelProvider(this)[RestoreWalletViewModel::class.java]
        walletApplication = (application as WalletApplication)
        initViewModel()
    }

    @SuppressLint("StringFormatInvalid")
    private fun initViewModel() {
        viewModel.showRestoreWalletFailureAction.observe(this, Observer {
            val message = when {
                TextUtils.isEmpty(it.message) -> it.javaClass.simpleName
                else -> it.message!!
            }

            BaseAlertDialogBuilder(this).apply {
                title = getString(R.string.import_export_keys_dialog_failure_title)
                this.message = getString(R.string.import_keys_dialog_failure, message)
                positiveText = getString(R.string.button_dismiss)
                negativeText = getString(R.string.button_retry)
                negativeAction = { RestoreWalletFromSeedDialogFragment.show(supportFragmentManager) }
                showIcon = true
            }.buildAlertDialog().show()

        })
        viewModel.showUpgradeWalletAction.observe(this, {
            walletBuffer = it
            EncryptNewKeyChainDialogFragment.show(supportFragmentManager, Constants.BIP44_PATH)
        })
        viewModel.showUpgradeDisclaimerAction.observe(this, Observer {
            UpgradeWalletDisclaimerDialog.show(supportFragmentManager, false)
        })
        viewModel.startActivityAction.observe(this, Observer {
            startActivityForResult(it, SET_PIN_REQUEST_CODE)
        })
        viewModel.restoreWallet.observe(this, Observer {
            walletBuffer = it
            viewModel.restoreWalletFromFile(wallet, null)
        })
        viewModel.retryRequest.observe(this, Observer {
            RestoreWalletDialogFragment.showPick(supportFragmentManager)
        })
    }

    internal fun restoreWalletFromFile() {
        walletApplication.initEnvironmentIfNeeded()
        RestoreWalletDialogFragment.showPick(supportFragmentManager)
    }

    override fun onCreateDialog(id: Int): Dialog {
        return when (id) {
            DIALOG_RESTORE_WALLET_PERMISSION -> createRestoreWalletPermissionDialog()
            else -> super.onCreateDialog(id)
        }
    }

    private fun createRestoreWalletPermissionDialog(): Dialog {
        return RestoreFromFileHelper.createRestoreWalletPermissionDialog(this)
    }

    override fun getWallet(): Wallet {
        return walletBuffer
    }

    override fun onWalletUpgradeComplete(password: String) {
        viewModel.restoreWalletFromFile(walletBuffer, password)
    }

}
