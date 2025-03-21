/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.more

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.backup.BackupWalletDialogFragment
import de.schildbach.wallet.ui.verify.VerifySeedActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentSecurityBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.ExtraActionDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.goBack
import org.dash.wallet.common.util.safeNavigate
import javax.inject.Inject

@AndroidEntryPoint
class SecurityFragment : Fragment(R.layout.fragment_security) {
    private val viewModel: SecurityViewModel by viewModels()
    private val binding by viewBinding(FragmentSecurityBinding::bind)
    @Inject lateinit var authManager: SecurityFunctions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.appBar.toolbar.setTitle(R.string.security_title)
        binding.appBar.toolbar.setNavigationOnClickListener { goBack() }

        viewModel.hideBalance.observe(viewLifecycleOwner) {
            binding.hideBalanceSwitch.isChecked =  it ?: false
        }

        viewModel.fingerprintIsAvailable.observe(viewLifecycleOwner) {
            binding.fingerprintAuthGroup.isVisible = it
        }

        viewModel.fingerprintIsEnabled.observe(viewLifecycleOwner) {
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

        binding.changePin.setOnClickListener { changePin() }
        binding.viewRecoveryPhrase.setOnClickListener { viewRecoveryPhrase() }
        binding.advancedSecurity.setOnClickListener { openAdvancedSecurity() }
        binding.resetWallet.setOnClickListener { resetWallet() }
        binding.backupWallet.setOnClickListener { backupWallet() }
        binding.backupWallet.isVisible = BuildConfig.DEBUG

        viewModel.init()
    }

    private fun backupWallet() {
        lifecycleScope.launch {
            val pin = authManager.authenticate(requireActivity(), true)
            pin?.let { BackupWalletDialogFragment.show(requireActivity()) }
        }
    }

    private fun viewRecoveryPhrase() {
        DecryptSeedWithPinDialog.show(requireActivity()) { seed ->
            if (seed.isNotEmpty()) {
                showSeed(seed)
            }
        }
    }

    private fun changePin() {
        viewModel.logEvent(AnalyticsConstants.Security.CHANGE_PIN)
        startActivity(
            SetPinActivity.createIntent(
                requireContext(),
                R.string.wallet_options_encrypt_keys_change,
                true
            )
        )
    }

    private fun openAdvancedSecurity() {
        lifecycleScope.launch {
            val pin = authManager.authenticate(requireActivity(), true)
            pin?.let {
                viewModel.logEvent(AnalyticsConstants.Security.ADVANCED_SECURITY)
                startActivity(Intent(requireActivity(), AdvancedSecurityActivity::class.java))
            }
        }
    }

    // TODO: tests
    private fun resetWallet() {
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
            resetWalletDialog.show(
                requireActivity(),
                onResult = {
                    if (it == true) {
                        val startResetWalletDialog = AdaptiveDialog.create(
                            R.drawable.ic_warning,
                            getString(
                                R.string.start_reset_wallet_title,
                                fiatBalanceStr.ifEmpty {
                                    walletBalance.toFriendlyString()
                                }
                            ),
                            getString(R.string.launch_reset_wallet_message),
                            getString(R.string.button_cancel),
                            getString(R.string.reset_wallet_text)
                        )
                        startResetWalletDialog.show(requireActivity()) { confirmed ->
                            if (confirmed == true) {
//                                doReset()
                                checkUsernameThenReset()
                            }
                        }
                    }
                },
                onExtraMessageAction = {
                    authManager.authenticate(requireActivity()) { pin ->
                        pin?.let {
                            startActivity(VerifySeedActivity.createIntent(requireContext(), pin, false))
                        }
                    }
                }
            )
        } else {
            val resetWalletDialog = AdaptiveDialog.create(
                null,
                getString(R.string.reset_wallet_title),
                getString(R.string.reset_wallet_message),
                getString(R.string.button_cancel),
                getString(R.string.positive_reset_text)
            )
            resetWalletDialog.show(requireActivity()) {
                if (it == true) {
                    doReset()
                }
            }
        }
    }

    private fun checkUsernameThenReset() {
        lifecycleScope.launch {
            if (viewModel.hasIdentity && viewModel.hasPendingTxMetadataToSave()) {
                TransactionMetadataDialog.newInstance().show(requireActivity()) {
                    lifecycleScope.launch {
                        if (it == true) {
                            viewModel.setSaveOnReset()
                            //safeNavigate(SecurityFragmentDirections.securityToTransactionMetadata())
                            doReset()
                        } else {
                            doReset()
                        }
                    }
                }
            } else {
                doReset()
            }
        }
    }

    private fun doReset() {
        viewModel.logEvent(AnalyticsConstants.Security.RESET_WALLET)
        val dialog = AdaptiveDialog.progress(getString(R.string.perm_lock_wipe_wallet))
        dialog.show(requireActivity())
        (requireActivity() as AbstractBindServiceActivity).doUnbindService()
        viewModel.triggerWipe() {
            dialog.dismissAllowingStateLoss()
            startActivity(OnboardingActivity.createIntent(requireContext()))
            requireActivity().finishAffinity()
        }
    }

    private fun showSeed(seed: Array<String>) {
        viewModel.logEvent(AnalyticsConstants.Security.VIEW_RECOVERY_PHRASE)
        safeNavigate(SecurityFragmentDirections.securityToShowSeed(seed, true))
    }

    private suspend fun setupBiometric(): Boolean {
        try {
            val pin = authManager.authenticate(requireActivity(), true)
            pin?.let {
                return viewModel.biometricHelper.savePassword(requireActivity(), pin)
            }
        } catch (ex: Exception) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.error),
                ex.localizedMessage ?: "",
                getString(R.string.button_dismiss)
            ).show(requireActivity())
        }

        return false
    }
}
