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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.dash.wallet.common.databinding.FragmentIntegrationPortalBinding
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.setRoundedBackground
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.integration.uphold.R
import org.dash.wallet.integration.uphold.data.RequirementsCheckResult
import org.dash.wallet.integration.uphold.data.UpholdConstants

@AndroidEntryPoint
class UpholdPortalFragment : Fragment(R.layout.fragment_integration_portal) {
    companion object {
        const val AUTH_RESULT_ACTION = "UpholdPortalFragment.AUTH_RESULT"
    }

    private val binding by viewBinding(FragmentIntegrationPortalBinding::bind)
    private val viewModel by viewModels<UpholdViewModel>()

    private val authResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val uri = intent.extras?.get("uri") as Uri?
            val code = uri?.getQueryParameter("code")
            val state = uri?.getQueryParameter("state")

            if (code != null && state != null) {
                lifecycleScope.launch {
                    AdaptiveDialog.withProgress(getString(R.string.loading), requireActivity()) {
                        viewModel.onAuthResult(code, state)
                    }
                }
            }

            startActivity(intent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.balanceDash.setFormat(viewModel.balanceFormat)
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
        binding.additionalInfo.setRoundedBackground(R.style.UpholdTextHighlight)

        setConnectedState(viewModel.uiState.value.isUserLoggedIn)

        binding.toolbar.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.buyBtn.setOnClickListener {
            val uri = viewModel.topperBuyUrl(getString(R.string.dash_wallet_name))
            viewModel.logEvent(AnalyticsConstants.Topper.ENTER_UPHOLD)
            requireActivity().openCustomTab(uri)
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

        if (viewModel.uiState.value.isUserLoggedIn) {
            lifecycleScope.launch {
                viewModel.refreshBalance()
                viewModel.checkCapabilities()
            }
        }

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            authResultReceiver,
            IntentFilter(AUTH_RESULT_ACTION)
        )
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
        val intent = Intent(requireContext(), UpholdTransferActivity::class.java)
        intent.putExtra(UpholdTransferActivity.EXTRA_TITLE, getString(R.string.uphold_account))
        intent.putExtra(UpholdTransferActivity.EXTRA_MESSAGE, getString(R.string.uphold_withdrawal_instructions))
        intent.putExtra(UpholdTransferActivity.EXTRA_MAX_AMOUNT, viewModel.uiState.value.balance)
        startActivity(intent)
    }

    private fun confirmLogout() {
        lifecycleScope.launch {
            val logout = AdaptiveDialog.custom(R.layout.uphold_logout_confirm).showAsync(requireActivity())

            if (logout == true) {
                setConnectedState(false)
                viewModel.revokeUpholdAccessToken()
                requireActivity().openCustomTab(UpholdConstants.LOGOUT_URL)
            }
        }
    }

    private fun showNotLoggedInDialog() {
        val dialog = AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.uphold_error),
            getString(R.string.uphold_error_not_logged_in),
            getString(R.string.button_dismiss),
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
        binding.additionalInfo.isVisible = !isConnected
        binding.transferBtn.isEnabled = isConnected
        binding.transferIcon.setRoundedBackground(
            if (isConnected) {
                R.style.TransferDashCircle
            } else {
                R.style.DisabledCircle
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(authResultReceiver)
    }
}
