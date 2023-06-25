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

package org.dash.wallet.integration.uphold.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.databinding.FragmentIntegrationPortalBinding
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.integration.uphold.R
import org.dash.wallet.integration.uphold.data.RequirementsCheckResult
import org.dash.wallet.integration.uphold.data.UpholdConstants

@AndroidEntryPoint
class UpholdPortalFragment: Fragment(R.layout.fragment_integration_portal) {
    private val binding by viewBinding(FragmentIntegrationPortalBinding::bind)
    private val monetaryFormat = MonetaryFormat().noCode().minDecimals(8)

    private val viewModel by viewModels<UpholdViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.balanceDash.setFormat(monetaryFormat)
        binding.balanceDash.setApplyMarkup(false)

        binding.toolbarTitle.text = getString(R.string.uphold_account)
        binding.toolbarIcon.setImageResource(R.drawable.ic_uphold)
        binding.disconnectedIndicator.isVisible = false
        binding.balanceHeader.text = getString(R.string.uphold_account_dash_balance)
        binding.additionalInfo.isVisible = true
        binding.additionalInfoIcon.setImageResource(R.drawable.logo_topper)
        binding.additionalInfoTxt.text = getString(R.string.uphold_powered_by)
        binding.convertBtn.isVisible = false
        binding.transferSubtitle.text = getString(R.string.uphold_transfer_to_this_wallet)
        binding.disconnectTitle.text = getString(R.string.uphold_disconnect)
        binding.linkAccountBtn.text = getString(R.string.uphold_link_account)

        setConnectedState(viewModel.uiState.value.isUserLoggedIn)

        binding.toolbar.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.transferBtn.setOnClickListener {
            if (!viewModel.uiState.value.balance.isZero) {
                viewModel.logEvent(AnalyticsConstants.Uphold.TRANSFER_DASH)
                openWithdrawals()
            }
        }
        binding.linkAccountBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Uphold.BUY_DASH)
            linkAccount()
        }

        binding.disconnectBtn.setOnClickListener { confirmLogout() }

        viewModel.uiState.observe(viewLifecycleOwner) { uiState ->
            binding.balanceDash.setAmount(uiState.balance)
            uiState.fiatBalance?.let {
                binding.balanceLocal.text = GenericUtils.fiatToString(it)
            }

            if (uiState.isUserLoggedIn) {
                setConnectedState(true)
            } else if (binding.connectedGroup.isVisible) {
                // The screen thinks it's still connected. Show the dialog and change the state.
                showNotLoggedInDialog()
            }

            uiState.errorCode?.let {
                showErrorAlert(it)
            }
        }

        lifecycleScope.launch {
            viewModel.refreshBalance()
            viewModel.checkCapabilities()
        }
    }

    private fun showErrorAlert(code: Int) {
        var messageId = R.string.loading_error

        if (code == 400 || code == 408 || code >= 500) messageId = R.string.uphold_error_not_available
        if (code == 403 || code >= 400) messageId = R.string.uphold_error_report_issue

        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.uphold_error),
            getString(messageId),
            getString(android.R.string.ok)
        ).show(requireActivity()) {
            viewModel.errorHandled()
        }
    }

    private fun openWithdrawals() {
        UpholdWithdrawalHelper.requirementsSatisfied(requireActivity()) { result ->
            if (result === RequirementsCheckResult.Satisfied) {
                openTransferActivity()
            } else if (result === RequirementsCheckResult.Resolve) {
                requireActivity().openCustomTab(UpholdConstants.PROFILE_URL)
            }
        }
    }

    private fun openTransferActivity() {
        // TODO
//        val intent = Intent()
//        intent.setClassName(requireContext(), "de.schildbach.wallet.ui.send.UpholdTransferActivity")
//        intent.putExtra("extra_title", getString(R.string.uphold_account))
//        intent.putExtra("extra_message", getString(R.string.uphold_withdrawal_instructions))
//        intent.putExtra("extra_max_amount", viewModel.uiState.value.balance.toString())
//        startActivityForResult(intent, REQUEST_CODE_TRANSFER)
    }

    private fun confirmLogout() {
        AdaptiveDialog.custom(R.layout.uphold_logout_confirm).show(requireActivity())
//
//        alertDialogBuilder.title = getString(R.string.uphold_logout_title)
//        alertDialogBuilder.positiveText = getString(R.string.uphold_go_to_website)
//        alertDialogBuilder.positiveAction = {
//            lifecycleScope.launch {
//                viewModel.revokeUpholdAccessToken()
//            }

            // TODO
//            fun onSuccess(result: String) {
//                if (isFinishing()) {
//                    return
//                }
//                startUpholdSplashActivity()
//                openUpholdToLogout()
//            }
//
//            override fun onError(e: Exception, otpRequired: Boolean) {
//                if (isFinishing()) {
//                    return
//                }
//                if (e is UpholdException) {
//                    showErrorAlert(e.code)
//                } else showErrorAlert(-1)
//            }
//        }
//        alertDialogBuilder.negativeText = getString(android.R.string.cancel)
//        alertDialogBuilder.view = dialogView
    }

    private fun openUpholdToLogout() {
        val url = UpholdConstants.LOGOUT_URL
        requireActivity().openCustomTab(url)
//        super.turnOffAutoLogout() TODO
    }

    private fun showNotLoggedInDialog() {
        val dialog = AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.uphold_error),
            getString(R.string.uphold_error_not_logged_in),
            getString(R.string.uphold_link_account),
            ""
        )
        dialog.isCancelable = false
        dialog.show(requireActivity()) {
            setConnectedState(false)
        }
    }

    private fun linkAccount() {
        val url = viewModel.getLinkAccountUrl()
        viewModel.logEvent(AnalyticsConstants.Uphold.LINK_ACCOUNT)
        requireActivity().openCustomTab(url)
    }

    private fun setConnectedState(isConnected: Boolean) {
        binding.connectedGroup.isVisible = isConnected
        binding.linkAccountBtn.isVisible = !isConnected
    }
}
