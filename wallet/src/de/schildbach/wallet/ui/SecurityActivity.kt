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
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.CompoundButton
import android.widget.Switch
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletLock
import de.schildbach.wallet.util.FingerprintHelper
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_security.*
import org.bitcoinj.wallet.Wallet


class SecurityActivity : BaseMenuActivity(), AbstractPINDialogFragment.WalletProvider,
        UnlockWalletDialogFragment.OnUnlockWalletListener {

    private lateinit var fingerprintHelper: FingerprintHelper
    private lateinit var checkPinSharedModel: CheckPinSharedModel

    override fun getLayoutId(): Int {
        return R.layout.activity_security
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.security_title)
        val hideBalanceOnLaunch = findViewById<Switch>(R.id.hide_balance_switch)
        hideBalanceOnLaunch.isChecked = configuration.hideBalance
        hideBalanceOnLaunch.setOnCheckedChangeListener { _, hideBalanceOnLaunch ->
            configuration.hideBalance = hideBalanceOnLaunch
        }

        //Fingerprint group and switch setup
        fingerprintHelper = FingerprintHelper(this)
        if (fingerprintHelper.init()) {
            fingerprint_auth_group.visibility = VISIBLE
            fingerprint_auth_switch.isChecked = fingerprintHelper.isFingerprintEnabled
            fingerprint_auth_switch.setOnCheckedChangeListener(fingerprintSwitchListener)
        } else {
            fingerprint_auth_group.visibility = GONE
        }
    }

    private val fingerprintSwitchListener= CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            UnlockWalletDialogFragment.show(supportFragmentManager)
            unCheckFingerprintAuthSwitchSilently()
        } else {
            fingerprintHelper.clear()
        }
    }

    private fun unCheckFingerprintAuthSwitchSilently() {
        fingerprint_auth_switch.setOnCheckedChangeListener(null)
        fingerprint_auth_switch.isChecked = false
        fingerprint_auth_switch.setOnCheckedChangeListener(fingerprintSwitchListener)
    }

    override fun onUnlockWallet(password: String?) {
        EnableFingerprintDialog().show(supportFragmentManager, password)
        checkPinSharedModel = ViewModelProviders.of(this).get(CheckPinSharedModel::class.java)
        checkPinSharedModel.onCorrectPinCallback.observe(this, Observer {
            fingerprint_auth_switch.isChecked = fingerprintHelper.isFingerprintEnabled
        })
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
        ResetWalletDialog.newInstance().show(supportFragmentManager, "reset_wallet_dialog")
    }

    // required by UnlockWalletDialogFragment
    override fun onWalletUpgradeComplete(password: String?) {

    }

    override fun getWallet(): Wallet {
        return WalletApplication.getInstance().wallet
    }
}
