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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.backup.BackupWalletDialogFragment
import de.schildbach.wallet.ui.verify.VerifySeedActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.ExtraActionDialog
import org.dash.wallet.common.util.safeNavigate
import javax.inject.Inject

@AndroidEntryPoint
class SecurityFragment : Fragment() {
    private val viewModel: SecurityViewModel by viewModels()
    @Inject lateinit var authManager: SecurityFunctions

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return ComposeView(requireContext()).apply {
            setContent {
                SecurityScreen(
                    onBackClick = { findNavController().popBackStack() },
                    onBackupWalletClick = { },
                    onViewRecoveryPhraseClick = { viewRecoveryPhrase() },
                    onChangePinClick = { changePin() },
                    onFingerprintAuthClick = { checked -> onFingerPrintAuthChanged(checked) },
                    onFaceIdClick = { },
                    onAutohideBalanceClick = { hideBalance -> viewModel.setHideBalanceOnLaunch(hideBalance) },
                    onAdvancedSecurityClick = { openAdvancedSecurity() },
                    onResetWalletClick = { resetWallet() },
                    onBackupWalletToFileClick = { backupWalletToFile() }
                )
            }
        }
    }

    private fun onFingerPrintAuthChanged(isChecked: Boolean) {
        lifecycleScope.launch {
            if (isChecked) {
                if (viewModel.uiState.value.fingerprintIsEnabled) {
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

    private fun backupWalletToFile() {
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

    private fun resetWallet() {
        val state = viewModel.uiState.value
        val walletBalance = state.balance
        val fiatBalanceStr = state.balanceInLocalFormat

        if (walletBalance.isGreaterThan(Coin.ZERO) && state.needPassphraseBackup) {
            val resetWalletDialog = ExtraActionDialog.create(
                R.drawable.ic_warning_yellow_circle,
                getString(R.string.launch_reset_wallet_title),
                getString(R.string.launch_reset_wallet_message),
                getString(R.string.button_cancel),
                getString(R.string.reset_wallet_button),
                getString(R.string.launch_reset_wallet_extra_message)
            ).apply {
                requireArguments().putInt(AdaptiveDialog.POS_BUTTON_COLOR_ARG, R.style.PrimaryButtonTheme_Large_Red)
            }
            resetWalletDialog.show(
                requireActivity(),
                onResult = {
                    if (it == true) {
                        val startResetWalletDialog = AdaptiveDialog.create(
                            R.drawable.ic_warning_yellow_circle,
                            getString(
                                R.string.start_reset_wallet_title,
                                fiatBalanceStr.ifEmpty {
                                    walletBalance.toFriendlyString()
                                }
                            ),
                            getString(R.string.launch_reset_wallet_message),
                            getString(R.string.button_cancel),
                            getString(R.string.reset_wallet_button)
                        ).apply {
                            requireArguments().putInt(AdaptiveDialog.POS_BUTTON_COLOR_ARG, R.style.PrimaryButtonTheme_Large_Red)
                        }
                        startResetWalletDialog.show(requireActivity()) { confirmed ->
                            if (confirmed == true) {
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
                R.drawable.ic_warning_yellow_circle,
                getString(R.string.reset_wallet_title),
                getString(R.string.reset_wallet_message),
                getString(R.string.button_cancel),
                getString(R.string.reset_wallet_button)
            ).apply {
                requireArguments().putInt(AdaptiveDialog.POS_BUTTON_COLOR_ARG, R.style.PrimaryButtonTheme_Large_Red)
            }
            resetWalletDialog.show(requireActivity()) {
                if (it == true) {
                    checkUsernameThenReset()
                }
            }
        }
    }

    private fun checkUsernameThenReset() {
        lifecycleScope.launch {
            if (viewModel.hasIdentity && viewModel.hasPendingTxMetadataToSave()) {
                SaveMetadataAndResetDialogFragment().show(requireActivity()) { result ->
                    when (result) {
                        null -> { }
                        true, false -> {
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
        val dialog = AdaptiveDialog.progress(getString(R.string.reset_wallet_text))
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
