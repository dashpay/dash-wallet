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

package de.schildbach.wallet.ui.more

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.CompoundButton
import androidx.activity.viewModels
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.backup.BackupWalletDialogFragment
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_security.*
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.DeterministicSeed
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.ExtraActionDialog

@AndroidEntryPoint
class SecurityActivity : BaseMenuActivity() {

    private val viewModel: SecurityViewModel by viewModels()

    override fun getLayoutId(): Int {
        return R.layout.activity_security
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.security_title)
        val hideBalanceOnLaunch = findViewById<SwitchCompat>(R.id.hide_balance_switch)
        hideBalanceOnLaunch.isChecked = configuration.hideBalance
        hideBalanceOnLaunch.setOnCheckedChangeListener { _, hideBalanceOnLaunch ->
            configuration.hideBalance = hideBalanceOnLaunch
            viewModel.logEvent(
                if (hideBalanceOnLaunch) {
                    AnalyticsConstants.Security.AUTOHIDE_BALANCE_ON
                } else {
                    AnalyticsConstants.Security.AUTOHIDE_BALANCE_OFF
                }
            )
        }

        //Fingerprint group and switch setup
        if (viewModel.fingerprintHelper.isAvailable) {
            fingerprint_auth_group.visibility = VISIBLE
            fingerprint_auth_switch.isChecked = viewModel.fingerprintHelper.isFingerprintEnabled
            fingerprint_auth_switch.setOnCheckedChangeListener(fingerprintSwitchListener)
            configuration.enableFingerprint = viewModel.fingerprintHelper.isFingerprintEnabled // TODO
        } else {
            fingerprint_auth_group.visibility = GONE
        }

        if (BuildConfig.DEBUG) {
            backup_wallet.visibility = VISIBLE
        }

        viewModel.init()
    }

    private val fingerprintSwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            lifecycleScope.launch {
                CheckPinDialog.showAsync(this@SecurityActivity)?.let { pin ->
                    EnableFingerprintDialog.show(pin, this@SecurityActivity) {
                        // TODO check
                        viewModel.logEvent(AnalyticsConstants.Security.FINGERPRINT_ON)
                        updateFingerprintSwitchSilently(viewModel.fingerprintHelper.isFingerprintEnabled)
                        configuration.enableFingerprint = viewModel.fingerprintHelper.isFingerprintEnabled
                    }
                }
            }
        } else {
            viewModel.turnFingerprintAuthOff()
        }
    }

    private fun updateFingerprintSwitchSilently(checked: Boolean) {
        fingerprint_auth_switch.setOnCheckedChangeListener(null)
        fingerprint_auth_switch.isChecked = checked
        fingerprint_auth_switch.setOnCheckedChangeListener(fingerprintSwitchListener)
    }

    fun backupWallet(view: View) {
        lifecycleScope.launch {
            val pin = CheckPinDialog.showAsync(this@SecurityActivity, true)
            pin?.let { BackupWalletDialogFragment.show(supportFragmentManager) }
        }
    }

    fun viewRecoveryPhrase(view: View) {
        DecryptSeedWithPinDialog.show(this, true) { seed ->
            if (seed != null) {
                startViewSeedActivity(seed)
            }
        }
    }

    fun changePin(view: View) {
        viewModel.logEvent(AnalyticsConstants.Security.CHANGE_PIN)
        startActivity(
            SetPinActivity.createIntent(
                this,
                R.string.wallet_options_encrypt_keys_change,
                true
            )
        )
    }

    fun openAdvancedSecurity(view: View) {
        lifecycleScope.launch {
            val pin = CheckPinDialog.showAsync(this@SecurityActivity, true)
            pin?.let {
                viewModel.logEvent(AnalyticsConstants.Security.ADVANCED_SECURITY)
                startActivity(Intent(this@SecurityActivity, AdvancedSecurityActivity::class.java))
            }
        }
    }

    // TODO: tests
    fun resetWallet(view: View) {
        val walletBalance = viewModel.balance
        val fiatBalanceStr = viewModel.getBalanceInLocalFormat()

        if (walletBalance.isGreaterThan(Coin.ZERO) && viewModel.needPassphraseBackUp) {
            val resetWalletDialog = ExtraActionDialog.create(
                R.drawable.ic_warning,
                getString(R.string.launch_reset_wallet_title),
                getString(R.string.launch_reset_wallet_message),
                getString(R.string.button_cancel),
                getString(R.string.continue_reset),
                getString(R.string.launch_reset_wallet_extra_message)
            )
            resetWalletDialog.show(this,
                onResult = {
                    if (it == true) {
                        val startResetWalletDialog = AdaptiveDialog.create(
                            R.drawable.ic_warning,
                            getString(R.string.start_reset_wallet_title, fiatBalanceStr.ifEmpty {
                                walletBalance.toFriendlyString()
                            }),
                            getString(R.string.launch_reset_wallet_message),
                            getString(R.string.button_cancel),
                            getString(R.string.reset_wallet_text)
                        )
                        startResetWalletDialog.show(this) { confirmed ->
                            if (confirmed == true) {
                                doReset()
                            }
                        }
                    }
                },
                onExtraMessageAction = {
                    CheckPinDialog.show(this) { pin ->
                        pin?.let {
                            startActivity(VerifySeedActivity.createIntent(this, pin, false))
                        }
                    }
                })
        } else {
            val resetWalletDialog = AdaptiveDialog.create(
                null,
                getString(R.string.reset_wallet_title),
                getString(R.string.reset_wallet_message),
                getString(R.string.button_cancel),
                getString(R.string.positive_reset_text)
            )
            resetWalletDialog.show(this) {
                if (it == true) {
                    doReset()
                }
            }
        }
    }

    private fun doReset() {
        viewModel.logEvent(AnalyticsConstants.Security.RESET_WALLET)
        toAbstractBindService()?.unbindServiceServiceConnection()
        viewModel.triggerWipe()
        startActivity(OnboardingActivity.createIntent(this))
        finishAffinity()
    }

    private fun startViewSeedActivity(seed : DeterministicSeed?) {
        viewModel.logEvent(AnalyticsConstants.Security.VIEW_RECOVERY_PHRASE)
        val mnemonicCode = seed!!.mnemonicCode
        val seedArray = mnemonicCode!!.toTypedArray()
        val intent = ViewSeedActivity.createIntent(this, seedArray)
        startActivity(intent)
    }
}

fun Activity.toAbstractBindService(): AbstractBindServiceActivity? {
    return this as? AbstractBindServiceActivity
}
