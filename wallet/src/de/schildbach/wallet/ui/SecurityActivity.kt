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
import android.content.Intent
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
import de.schildbach.wallet.util.FingerprintHelper
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_security.*
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet

class SecurityActivity : BaseMenuActivity(), AbstractPINDialogFragment.WalletProvider {

    private lateinit var fingerprintHelper: FingerprintHelper
    private lateinit var checkPinSharedModel: CheckPinSharedModel

    companion object {
        private const val AUTH_REQUEST_CODE_BACKUP = 1
        private const val ENABLE_FINGERPRINT_REQUEST_CODE = 2
        private const val FINGERPRINT_ENABLED_REQUEST_CODE = 3
        private const val AUTH_REQUEST_CODE_VIEW_RECOVERYPHRASE = 4
        private const val AUTH_REQUEST_CODE_ADVANCED_SECURITY = 5
    }

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

        val checkPinSharedModel: CheckPinSharedModel = ViewModelProviders.of(this).get(CheckPinSharedModel::class.java)
        checkPinSharedModel.onCorrectPinCallback.observe(this, Observer<Pair<Int?, String?>> { (requestCode, pin) ->
            when (requestCode) {
                AUTH_REQUEST_CODE_BACKUP -> {
                    val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
                    if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                        BackupWalletDialogFragment.show(supportFragmentManager)
                    } else {
                        ActivityCompat.requestPermissions(this, arrayOf(permission), AUTH_REQUEST_CODE_BACKUP)
                    }
                }
                ENABLE_FINGERPRINT_REQUEST_CODE -> {
                    if (pin != null) {
                        EnableFingerprintDialog.show(pin, FINGERPRINT_ENABLED_REQUEST_CODE,
                                supportFragmentManager)
                    }
                }
                FINGERPRINT_ENABLED_REQUEST_CODE -> {
                    updateFingerprintSwitchSilently(fingerprintHelper.isFingerprintEnabled)
                    configuration.enableFingerprint = fingerprintHelper.isFingerprintEnabled
                }
                AUTH_REQUEST_CODE_ADVANCED_SECURITY -> {
                    startActivity(Intent(this, AdvancedSecurityActivity::class.java))
                }
            }
        })

        val decryptSeedSharedModel : DecryptSeedSharedModel = ViewModelProviders.of(this).get(DecryptSeedSharedModel::class.java)
        decryptSeedSharedModel.onDecryptSeedCallback.observe(this, Observer<Pair<Int?, DeterministicSeed?>> { (requestCode, seed) ->
            when (requestCode) {
                AUTH_REQUEST_CODE_VIEW_RECOVERYPHRASE -> {
                    startViewSeedActivity(seed)
                }
            }
        })

        //Fingerprint group and switch setup
        fingerprintHelper = FingerprintHelper(this)
        if (fingerprintHelper.init()) {
            fingerprint_auth_group.visibility = VISIBLE
            fingerprint_auth_switch.isChecked = fingerprintHelper.isFingerprintEnabled
            fingerprint_auth_switch.setOnCheckedChangeListener(fingerprintSwitchListener)
            configuration.enableFingerprint = fingerprintHelper.isFingerprintEnabled
    } else {
            fingerprint_auth_group.visibility = GONE
        }
    }

    private val fingerprintSwitchListener= CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            CheckPinDialog.show(this, ENABLE_FINGERPRINT_REQUEST_CODE)
            updateFingerprintSwitchSilently(false)
        } else {
            fingerprintHelper.clear()
            configuration.enableFingerprint = false
        }
    }

    private fun updateFingerprintSwitchSilently(checked: Boolean) {
        fingerprint_auth_switch.setOnCheckedChangeListener(null)
        fingerprint_auth_switch.isChecked = checked
        fingerprint_auth_switch.setOnCheckedChangeListener(fingerprintSwitchListener)
    }

    fun backupWallet(view: View) {
        CheckPinDialog.show(this, AUTH_REQUEST_CODE_BACKUP, true)
    }

    fun viewRecoveryPhrase(view: View) {
        DecryptSeedWithPinDialog.show(this, AUTH_REQUEST_CODE_VIEW_RECOVERYPHRASE, true)
    }

    fun changePin(view: View) {
        startActivity(SetPinActivity.createIntent(this, R.string.wallet_options_encrypt_keys_change, true))
    }

    fun openAdvancedSecurity(view: View) {
        CheckPinDialog.show(this, AUTH_REQUEST_CODE_ADVANCED_SECURITY, true)
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

    private fun startViewSeedActivity(seed : DeterministicSeed?) {
        val mnemonicCode = seed!!.mnemonicCode
        var seedArray = mnemonicCode!!.toTypedArray()
        val intent = ViewSeedActivity.createIntent(this, seedArray)
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        if (requestCode == AUTH_REQUEST_CODE_BACKUP) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                BackupWalletDialogFragment.show(supportFragmentManager)

        }
    }
}
