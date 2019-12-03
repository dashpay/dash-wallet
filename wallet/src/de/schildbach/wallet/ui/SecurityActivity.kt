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
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletLock
import de.schildbach.wallet_test.R
import org.bitcoinj.wallet.Wallet

class SecurityActivity : BaseMenuActivity(), AbstractPINDialogFragment.WalletProvider {
    override fun getLayoutId(): Int {
        return R.layout.activity_security
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.security_title)
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

    fun resetWallet(view: View) {

    }

    // required by UnlockWalletDialogFragment
    override fun onWalletUpgradeComplete(password: String?) {

    }

    override fun getWallet(): Wallet {
        return WalletApplication.getInstance().wallet
    }
}
