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
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.security.SecurityFunctions
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
import javax.inject.Inject

@AndroidEntryPoint
class SecurityActivity : LockScreenActivity() {

    private val viewModel: SecurityViewModel by viewModels()
    private lateinit var binding: ActivitySecurityBinding
    @Inject lateinit var authManager: SecurityFunctions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        binding.appBar.toolbar.setTitle(R.string.security_title)
        binding.appBar.toolbar.setNavigationOnClickListener { finish() }

        viewModel.hideBalance.observe(this) {
            binding.hideBalanceSwitch.isChecked = it
        }

        viewModel.fingerprintIsAvailable.observe(this) {
            binding.fingerprintAuthGroup.isVisible = it
        }

        viewModel.fingerprintIsEnabled.observe(this) {
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
            val pin = authManager.authenticate(this@SecurityActivity, true)
            pin?.let { BackupWalletDialogFragment.show(supportFragmentManager) }
        }
    }

    fun viewRecoveryPhrase(view: View) {
        DecryptSeedWithPinDialog.show(this) { seed ->
            if (seed.isNotEmpty()) {
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
            val pin = authManager.authenticate(this@SecurityActivity, true)
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
                    authManager.authenticate(this@SecurityActivity) { pin ->
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

    private fun startViewSeedActivity(seed: Array<String>) {
        viewModel.logEvent(AnalyticsConstants.Security.VIEW_RECOVERY_PHRASE)
        val intent = ViewSeedActivity.createIntent(this, seed)
        startActivity(intent)
    }

    private suspend fun setupBiometric(): Boolean {
        try {
            val pin = authManager.authenticate(this@SecurityActivity, true)
            pin?.let {
                return viewModel.biometricHelper.savePassword(this@SecurityActivity, pin)
            }
        } catch (ex: Exception) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.error),
                ex.localizedMessage ?: "",
                getString(R.string.button_dismiss)
            ).show(this)
        }

        return false
    }
}
