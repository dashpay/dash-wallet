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

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.viewModels
import androidx.core.os.CancellationSignal
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.backup.BackupWalletDialogFragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivitySecurityBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.DeterministicSeed
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.ExtraActionDialog

@AndroidEntryPoint
class SecurityActivity : LockScreenActivity() {

    private val viewModel: SecurityViewModel by viewModels()
    private lateinit var binding: ActivitySecurityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setTitle(R.string.security_title)

        viewModel.hideBalance.observe(this) {
            Log.i("FINGERPRINT", "set hide balance: ${it}")
            binding.hideBalanceSwitch.isChecked = it
        }

        viewModel.fingerprintIsAvailable.observe(this) {
            Log.i("FINGERPRINT", "set fingerprint available: ${it}")
            binding.fingerprintAuthGroup.isVisible = it
        }

        viewModel.fingerprintIsEnabled.observe(this) {
            Log.i("FINGERPRINT", "set fingerprint enabled: ${it}")
            binding.fingerprintAuthSwitch.isChecked = it
        }

        binding.hideBalanceSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setHideBalanceOnLaunch(isChecked)
        }

        binding.fingerprintAuthSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                if (isChecked) {
                    if (viewModel.fingerprintIsEnabled.value == true) {
                        return@launch
                    }

                    if (setupBiometric()) {
                        viewModel.setEnableFingerprint(true)
                        return@launch
                    }
                }

                viewModel.setEnableFingerprint(false)
            }
        }

        binding.backupWallet.isVisible = BuildConfig.DEBUG
        viewModel.init()
        setContentView(binding.root)
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

    private suspend fun setupBiometric(): Boolean {
        val pin = CheckPinDialog.showAsync(this@SecurityActivity)

        if (pin != null) {
            val cancellationSignal = CancellationSignal() // TODO
            try {
                return viewModel.biometricHelper.savePassword(this@SecurityActivity, pin)
            } catch (ex: Exception) {
                // TODO
                Log.i("FINGERPRINT", "Error: ${ex.message}")
            }
        }

        return false
    }
}
