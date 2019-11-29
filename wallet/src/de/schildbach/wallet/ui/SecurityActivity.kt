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

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Switch
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletLock
import de.schildbach.wallet.ui.AbstractWalletActivity.log
import de.schildbach.wallet_test.R
import org.bitcoinj.wallet.Wallet


class SecurityActivity : BaseMenuActivity(), AbstractPINDialogFragment.WalletProvider {
    override fun getLayoutId(): Int {
        return R.layout.activity_security
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.security_title)
        val hideBalanceOnLaunch = findViewById<Switch>(R.id.hide_balance_switch)
        hideBalanceOnLaunch.isChecked = configuration.hideBalanceOnLaunch
        hideBalanceOnLaunch.setOnCheckedChangeListener {_, hideBalanceOnLaunch ->
            configuration.hideBalanceOnLaunch = hideBalanceOnLaunch
        }
    }

    fun backupWallet(view: View) {
        val wallet = WalletApplication.getInstance().wallet
        //Only allow to backup when wallet is unlocked
        val walletLock = WalletLock.getInstance()
        if (WalletLock.getInstance().isWalletLocked(wallet)) {
            UnlockWalletDialogFragment.show(supportFragmentManager) {
                if (!walletLock.isWalletLocked(wallet)) {
                    backupWallet(view)
                }
            }
            return
        }

        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED) {
            BackupWalletDialogFragment.show(supportFragmentManager)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 1)
        }
    }

    fun viewRecoveryPhrase(view: View) {
        BackupWalletToSeedDialogFragment.show(supportFragmentManager)
    }

    fun changePin(view: View) {
        startActivity(SetPinActivity.createIntent(this, R.string.wallet_options_encrypt_keys_change, true))
    }

    fun openAdvancedSecurity(view: View) {

    }

    private fun executeResetWallet() {
        val walletLock = WalletLock.getInstance()
        if (WalletLock.getInstance().isWalletLocked(wallet)) {
            UnlockWalletDialogFragment.show(supportFragmentManager) {
                if (!walletLock.isWalletLocked(wallet)) {
                    executeResetWallet()
                }
            }
            return
        }

        try {
            val newWallet = Wallet(Constants.NETWORK_PARAMETERS)
            newWallet.addKeyChain(Constants.BIP44_PATH)

            log.info("creating new wallet after wallet wipe")

            val walletBackupFile = getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF)
            if (walletBackupFile.exists()) walletBackupFile.delete()

            walletApplication.replaceWallet(newWallet)
            walletApplication.saveWallet()
            configuration.armBackupReminder()
            configuration.armBackupSeedReminder()
            log.info("New wallet created to replace the wiped locked wallet")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resetWallet(view: View) {
        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setTitle(R.string.wallet_lock_reset_wallet_title)
        dialogBuilder.setMessage(R.string.wallet_lock_reset_wallet_message)
        dialogBuilder.setNegativeButton(R.string.wallet_lock_reset_wallet_title) { _, _ -> executeResetWallet() }
        dialogBuilder.setPositiveButton(android.R.string.no, null)
        dialogBuilder.setCancelable(false)
        val dialog: Dialog = dialogBuilder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    // required by UnlockWalletDialogFragment
    override fun onWalletUpgradeComplete(password: String?) {

    }

    override fun getWallet(): Wallet {
        return WalletApplication.getInstance().wallet
    }
}
