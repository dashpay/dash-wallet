/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode.ui.portal

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.blinkAnimator
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentPortalBinding
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeException
import org.dash.wallet.integrations.crowdnode.model.MessageStatusException
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.dash.wallet.integrations.crowdnode.ui.dialogs.ConfirmationDialog
import org.dash.wallet.integrations.crowdnode.ui.dialogs.OnlineAccountDetailsDialog
import org.dash.wallet.integrations.crowdnode.ui.dialogs.StakingDialog
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

@AndroidEntryPoint
class PortalFragment : Fragment(R.layout.fragment_portal) {
    companion object {
        private val NEGLIGIBLE_AMOUNT: Coin = CrowdNodeConstants.MINIMUM_DASH_DEPOSIT.div(50)
    }

    private val binding by viewBinding(FragmentPortalBinding::bind)
    private val viewModel by activityViewModels<CrowdNodeViewModel>()
    private var balanceAnimator: ObjectAnimator? = null

    private val isConfirmed: Boolean
        get() = viewModel.signUpStatus === SignUpStatus.Finished ||
            viewModel.onlineAccountStatus == OnlineAccountStatus.Done

    private val isLinkingInProgress: Boolean
        get() = viewModel.onlineAccountStatus != OnlineAccountStatus.None &&
            viewModel.onlineAccountStatus != OnlineAccountStatus.Creating &&
            viewModel.onlineAccountStatus != OnlineAccountStatus.SigningUp &&
            viewModel.onlineAccountStatus != OnlineAccountStatus.Done

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI(binding)

        viewModel.observeCrowdNodeError().observe(viewLifecycleOwner) { error ->
            error?.let {
                safeNavigate(
                    PortalFragmentDirections.portalToResult(
                        true,
                        getErrorMessage(it),
                        ""
                    )
                )
            }
        }

        viewModel.networkError.observe(viewLifecycleOwner) {
            Toast.makeText(
                requireContext(),
                R.string.network_unavailable_balance_not_accurate,
                Toast.LENGTH_LONG
            ).show()
        }

        viewModel.observeOnlineAccountStatus().observe(viewLifecycleOwner) { status ->
            setOnlineAccountStatus(status)

            if (viewModel.signUpStatus == SignUpStatus.LinkedOnline) {
                val crowdNodeBalance = viewModel.crowdNodeBalance.value?.balance ?: Coin.ZERO
                val walletBalance = viewModel.dashBalance.value ?: Coin.ZERO

                setWithdrawalEnabled(crowdNodeBalance)
                setDepositsEnabled(walletBalance)
                setMinimumEarningDepositReminder(crowdNodeBalance, isConfirmed)

                lifecycleScope.launch {
                    if (viewModel.getShouldShowConfirmationDialog()) {
                        showConfirmationDialog()
                    }
                }
            }
        }

        viewModel.onlineAccountRequest.observe(viewLifecycleOwner) { args ->
            safeNavigate(
                PortalFragmentDirections.portalToSignUp(
                    args[CrowdNodeViewModel.URL_ARG]!!,
                    args[CrowdNodeViewModel.EMAIL_ARG] ?: ""
                )
            )
        }
    }

    private fun setupUI(binding: FragmentPortalBinding) {
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.walletBalanceDash.setFormat(viewModel.dashFormat)
        binding.walletBalanceDash.setApplyMarkup(true)
        binding.walletBalanceDash.setAmount(Coin.ZERO)

        binding.depositBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.CrowdNode.PORTAL_DEPOSIT)
            safeNavigate(PortalFragmentDirections.portalToTransfer(false))
        }

        binding.withdrawBtn.setOnClickListener {
            continueWithdraw()
        }

        binding.onlineAccountBtn.setOnClickListener {
            handleOnlineAccountNavigation()
        }

        binding.supportBtn.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.crowdnode_support_url)))
            startActivity(browserIntent)
        }

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_info) {
                showInfoDialog()
            }

            true
        }

        binding.verifyBtn.setOnClickListener {
            showConfirmationDialog()
        }

        handleBalance(binding)
    }

    private fun updateFiatAmount(balance: Coin?, exchangeRate: ExchangeRate?) {
        val fiatRate = exchangeRate?.fiat

        if (balance != null && fiatRate != null) {
            val rate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiatRate)
            val fiatValue = rate.coinToFiat(balance)
            binding.walletBalanceLocal.text = fiatValue.toFormattedString()
        }
    }

    private fun handleBalance(binding: FragmentPortalBinding) {
        this.balanceAnimator = binding.balanceLabel.blinkAnimator
        binding.root.setOnRefreshListener {
            viewModel.refreshBalance()
        }

        viewModel.crowdNodeBalance.observe(viewLifecycleOwner) { state ->
            if (state.isUpdating) {
                this.balanceAnimator?.start()
            } else {
                binding.root.isRefreshing = false
                this.balanceAnimator?.end()
            }

            binding.walletBalanceDash.setAmount(state.balance)
            updateFiatAmount(state.balance, viewModel.exchangeRate.value)
            setWithdrawalEnabled(state.balance)
            setMinimumEarningDepositReminder(state.balance, isConfirmed)
        }

        viewModel.dashBalance.observe(viewLifecycleOwner) { balance ->
            setDepositsEnabled(balance)
        }

        viewModel.exchangeRate.observe(viewLifecycleOwner) { rate ->
            updateFiatAmount(viewModel.crowdNodeBalance.value?.balance ?: Coin.ZERO, rate)
        }
    }

    private fun setWithdrawalEnabled(balance: Coin) {
        val isEnabled = balance.isPositive && !isLinkingInProgress
        binding.withdrawBtn.isEnabled = isEnabled

        if (isEnabled) {
            binding.withdrawIcon.setImageResource(R.drawable.ic_left_right_arrows)
            binding.withdrawTitle.setTextColor(resources.getColor(R.color.content_primary, null))
            binding.withdrawSubtitle.setTextColor(resources.getColor(R.color.content_tertiary, null))
        } else {
            binding.withdrawIcon.setImageResource(R.drawable.ic_withdraw_disabled)
            binding.withdrawTitle.setTextColor(resources.getColor(R.color.content_disabled, null))
            binding.withdrawSubtitle.setTextColor(resources.getColor(R.color.content_disabled, null))
        }
    }

    private fun setDepositsEnabled(balance: Coin) {
        val isEnabled = balance.isPositive && !isLinkingInProgress
        binding.depositBtn.isEnabled = isEnabled

        if (isEnabled) {
            binding.depositIcon.setImageResource(R.drawable.ic_deposit_enabled)
            binding.depositTitle.setTextColor(resources.getColor(R.color.content_primary, null))
            binding.depositSubtitle.setTextColor(resources.getColor(R.color.content_tertiary, null))
        } else {
            binding.depositIcon.setImageResource(R.drawable.ic_deposit_disabled)
            binding.depositTitle.setTextColor(resources.getColor(R.color.content_disabled, null))
            binding.depositSubtitle.setTextColor(resources.getColor(R.color.content_disabled, null))
        }
    }

    private fun setMinimumEarningDepositReminder(balance: Coin, isConfirmed: Boolean) {
        val balanceLessThanMinimum = balance < CrowdNodeConstants.MINIMUM_DASH_DEPOSIT

        if (balanceLessThanMinimum && isConfirmed) {
            binding.minimumDashRequirement.isVisible = true

            if (balance < NEGLIGIBLE_AMOUNT) {
                binding.minimumDashRequirement.text = getString(
                    R.string.crowdnode_minimum_deposit,
                    CrowdNodeConstants.DASH_FORMAT.format(CrowdNodeConstants.MINIMUM_DASH_DEPOSIT)
                )
            } else {
                binding.minimumDashRequirement.text = getString(
                    R.string.crowdnode_minimum_deposit_difference,
                    CrowdNodeConstants.DASH_FORMAT.format(CrowdNodeConstants.MINIMUM_DASH_DEPOSIT - balance)
                )
            }
        } else {
            binding.minimumDashRequirement.isVisible = false
        }
    }

    private fun setOnlineAccountStatus(status: OnlineAccountStatus) {
        binding.onlineAccountBtn.isClickable = !isLinkingInProgress
        binding.onlineNavIcon.isVisible = !isLinkingInProgress

        binding.onlineAccountStatus.text = getText(
            when (status) {
                OnlineAccountStatus.Done -> R.string.crowdnode_online_synced
                OnlineAccountStatus.None -> R.string.secure_online_account
                OnlineAccountStatus.SigningUp -> R.string.crowdnode_signup_to_finish
                else -> R.string.crowdnode_in_process
            }
        )

        binding.onlineAccountTitle.text = getText(
            if (status == OnlineAccountStatus.None) {
                R.string.online_account_create
            } else {
                R.string.online_account
            }
        )

        binding.addressStatusWarning.isVisible =
            status == OnlineAccountStatus.Validating ||
            status == OnlineAccountStatus.Confirming

        binding.warningIcon.isVisible = status == OnlineAccountStatus.Confirming
        binding.verifyBtn.isVisible = status == OnlineAccountStatus.Confirming
        binding.warningMessage.text = getString(
            if (status == OnlineAccountStatus.Confirming) {
                R.string.verification_required
            } else {
                R.string.validating_address
            }
        )
        binding.warningMessage.gravity =
            if (status == OnlineAccountStatus.Confirming) {
                Gravity.START
            } else {
                Gravity.CENTER
            }
    }

    private fun handleOnlineAccountNavigation() {
        when (viewModel.onlineAccountStatus) {
            OnlineAccountStatus.None, OnlineAccountStatus.Creating -> showOnlineInfoOrEnterEmail()
            OnlineAccountStatus.SigningUp -> viewModel.initiateOnlineSignUp()
            OnlineAccountStatus.Done -> openCrowdNodeProfile()
            else -> { }
        }

        if (viewModel.onlineAccountStatus == OnlineAccountStatus.None) {
            lifecycleScope.launch {
                if (viewModel.getShouldShowOnlineInfo()) {
                    safeNavigate(PortalFragmentDirections.portalToOnlineAccountInfo())
                    viewModel.setOnlineInfoShown(true)
                } else {
                    safeNavigate(PortalFragmentDirections.portalToOnlineAccountEmail())
                }
            }
        }
    }

    private fun showConfirmationDialog() {
        viewModel.logEvent(AnalyticsConstants.CrowdNode.PORTAL_VERIFY)

        if (requireActivity().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            ConfirmationDialog().show(parentFragmentManager, "confirmation_dialog")
        }
    }

    private fun getErrorMessage(exception: Exception): String {
        if (exception is MessageStatusException) {
            return getString(R.string.crowdnode_signup_error)
        }

        return getString(
            when (exception.message) {
                CrowdNodeException.WITHDRAWAL_ERROR -> R.string.crowdnode_withdraw_error
                CrowdNodeException.DEPOSIT_ERROR -> R.string.crowdnode_deposit_error
                CrowdNodeException.CONFIRMATION_ERROR -> R.string.crowdnode_bad_confirmation
                else -> R.string.crowdnode_transfer_error
            }
        )
    }

    private fun showInfoDialog() {
        viewModel.logEvent(AnalyticsConstants.CrowdNode.PORTAL_INFO_BUTTON)

        if (viewModel.signUpStatus == SignUpStatus.LinkedOnline) {
            OnlineAccountDetailsDialog().show(parentFragmentManager, "online_account_details")
        } else {
            StakingDialog().show(parentFragmentManager, "staking")
        }
    }

    private fun openCrowdNodeProfile() {
        val accountUrl = viewModel.getAccountUrl()
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(accountUrl))
        startActivity(browserIntent)
    }

    private fun showOnlineInfoOrEnterEmail() {
        viewModel.logEvent(AnalyticsConstants.CrowdNode.PORTAL_CREATE_ONLINE_ACCOUNT)

        lifecycleScope.launch {
            if (viewModel.getShouldShowOnlineInfo()) {
                safeNavigate(PortalFragmentDirections.portalToOnlineAccountInfo())
                viewModel.setOnlineInfoShown(true)
            } else {
                safeNavigate(PortalFragmentDirections.portalToOnlineAccountEmail())
            }
        }
    }

    private fun continueWithdraw() {
        viewModel.logEvent(AnalyticsConstants.CrowdNode.PORTAL_WITHDRAW)

        if ((viewModel.dashBalance.value ?: Coin.ZERO) >= CrowdNodeConstants.MINIMUM_LEFTOVER_BALANCE) {
            safeNavigate(PortalFragmentDirections.portalToTransfer(true))
        } else {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.positive_balance_required),
                getString(R.string.withdrawal_required_balance),
                getString(R.string.button_close),
                getString(R.string.buy_dash)
            ).show(requireActivity()) {
                if (it == true) {
                    viewModel.logEvent(AnalyticsConstants.CrowdNode.PORTAL_WITHDRAW_BUY)
                    viewModel.buyDash()
                } else {
                    viewModel.logEvent(AnalyticsConstants.CrowdNode.PORTAL_WITHDRAW_CANCEL)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        this.balanceAnimator = null
    }
}
