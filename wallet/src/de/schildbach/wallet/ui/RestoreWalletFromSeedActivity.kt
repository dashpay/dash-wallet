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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.ui.DialogBuilder
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*

class RestoreWalletFromSeedActivity : BaseMenuActivity() {
    private val log = LoggerFactory.getLogger(RestoreWalletFromSeedDialogFragment::class.java)


    override fun getLayoutId(): Int {
        return R.layout.activity_recover_wallet_from_seed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.recover_wallet_title)
    }

    @SuppressLint("StringFormatInvalid")
    fun onContinueClick(view: View) {
        val seed = findViewById<EditText>(R.id.seed).text.trim()
        if (seed.isEmpty()) {
            return
        }

        val words = ArrayList(mutableListOf(*seed.split(' ').toTypedArray()))
        try {
            MnemonicCode.INSTANCE.check(words)
            restoreWallet(WalletUtils.restoreWalletFromSeed(words, Constants.NETWORK_PARAMETERS))
            log.info("successfully restored wallet from seed: {}", words.size)
        } catch (x: IOException) {
            val dialog = DialogBuilder.warn(this, R.string.import_export_keys_dialog_failure_title)
            dialog.setMessage(getString(R.string.import_keys_dialog_failure, x.message))
            dialog.setNeutralButton(R.string.button_dismiss, null)
            dialog.show()
            log.info("problem restoring wallet from seed: ", x)
        } catch (x: MnemonicException) {
            val dialog = DialogBuilder.warn(this, R.string.import_export_keys_dialog_failure_title)
            dialog.setMessage(getString(R.string.import_keys_dialog_failure, x.message))
            dialog.setNeutralButton(R.string.button_dismiss, null)
            dialog.show()
            log.info("problem restoring wallet from seed: ", x)
        }

    }

    private fun restoreWallet(wallet: Wallet) {
        walletApplication.replaceWallet(wallet)
        getSharedPreferences(Constants.WALLET_LOCK_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        configuration.disarmBackupReminder()

        if (!wallet.hasKeyChain(Constants.BIP44_PATH)) {
            if (wallet.isEncrypted) {
                EncryptNewKeyChainDialogFragment.show(supportFragmentManager, Constants.BIP44_PATH)
            } else {
                // Upgrade the wallet now
                wallet.addKeyChain(Constants.BIP44_PATH)
                walletApplication.saveWallet()
                // Tell the user that the wallet is being upgraded (BIP44)
                // and they will have to enter a PIN.
                UpgradeWalletDisclaimerDialog.show(supportFragmentManager)
            }
        } else {
            resetBlockchain()
        }
    }

    private fun resetBlockchain() {
        val dialog = DialogBuilder(this)
        dialog.setTitle(R.string.restore_wallet_dialog_success)
        dialog.setMessage(getString(R.string.restore_wallet_dialog_success_replay))
        dialog.setPositiveButton(R.string.button_ok) { _, _ ->
            walletApplication.resetBlockchain()
            val intent = SetPinActivity.createIntent(walletApplication, R.string.set_pin_create_new_wallet)
            SingleLiveEvent<Intent>().call(intent)
        }
        dialog.show()
    }
}
