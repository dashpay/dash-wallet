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
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.copy
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentPortalBinding
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeException
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.dash.wallet.integrations.crowdnode.ui.dialogs.ConfirmationDialog
import org.dash.wallet.integrations.crowdnode.ui.dialogs.OnlineAccountDetailsDialog
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
                viewModel.onlineAccountStatus != OnlineAccountStatus.Done

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI(binding)

        viewModel.observeCrowdNodeError().observe(viewLifecycleOwner) { error ->
            error?.let {
                safeNavigate(PortalFragmentDirections.portalToResult(
                    true,
                    getErrorMessage(it),
                    ""
                ))
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
                val crowdNodeBalance = viewModel.crowdNodeBalance.value ?: Coin.ZERO
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

        binding.verifyBtn.setOnClickListener {
            showConfirmationDialog()
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
            safeNavigate(PortalFragmentDirections.portalToTransfer(false))
        }

        binding.withdrawBtn.setOnClickListener {
            safeNavigate(PortalFragmentDirections.portalToTransfer(true))
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

        handleBalance(binding)
    }

    private fun updateFiatAmount(balance: Coin?, exchangeRate: ExchangeRate?) {
        val fiatRate = exchangeRate?.fiat

        if (balance != null && fiatRate != null) {
            val rate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiatRate)
            val fiatValue = rate.coinToFiat(balance)
            binding.walletBalanceLocal.text = GenericUtils.fiatToString(fiatValue)
        }
    }

    private fun handleBalance(binding: FragmentPortalBinding) {
        this.balanceAnimator = ObjectAnimator.ofFloat(
            binding.balanceLabel,
            View.ALPHA.name,
            0f, 1f
        ).apply {
            duration = 500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }

        viewModel.isBalanceLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                this.balanceAnimator?.start()
            } else {
                this.balanceAnimator?.end()
            }
        }

        viewModel.crowdNodeBalance.observe(viewLifecycleOwner) { balance ->
            binding.walletBalanceDash.setAmount(balance)
            updateFiatAmount(balance, viewModel.exchangeRate.value)
            setWithdrawalEnabled(balance)
            setMinimumEarningDepositReminder(balance, isConfirmed)
        }

        viewModel.dashBalance.observe(viewLifecycleOwner) { balance ->
            setDepositsEnabled(balance)
        }

        viewModel.exchangeRate.observe(viewLifecycleOwner) { rate ->
            updateFiatAmount(viewModel.crowdNodeBalance.value ?: Coin.ZERO, rate)
        }
    }

    private fun setWithdrawalEnabled(balance: Coin) {
        val isEnabled = balance.isPositive && !isLinkingInProgress
        binding.withdrawBtn.isEnabled = isEnabled

        if (isEnabled) {
            binding.withdrawIcon.setImageResource(R.drawable.ic_left_right_arrows)
            binding.withdrawTitle.setTextColor(resources.getColor(R.color.content_primary, null))
            binding.withdrawSubtitle.setTextColor(resources.getColor(R.color.steel_gray_500, null))
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
            binding.depositSubtitle.setTextColor(resources.getColor(R.color.steel_gray_500, null))
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
        binding.onlineAccountBtn.isClickable = status == OnlineAccountStatus.None ||
                                               status == OnlineAccountStatus.Done

        binding.onlineAccountStatus.text = getText(when (status) {
             OnlineAccountStatus.Confirming, OnlineAccountStatus.Validating -> R.string.crowdnode_online_unconfirmed
             OnlineAccountStatus.Done -> R.string.crowdnode_online_synced
             else -> R.string.secure_online_account
        })

        binding.onlineAccountTitle.text = getText(if (status == OnlineAccountStatus.None) {
            R.string.online_account_create
        } else {
            R.string.online_account
        })

        binding.onlineNavIcon.isVisible = !isLinkingInProgress

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
        if (viewModel.onlineAccountStatus == OnlineAccountStatus.Done) {
            val accountUrl = viewModel.getAccountUrl()
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(accountUrl))
            startActivity(browserIntent)
        } else if (viewModel.onlineAccountStatus == OnlineAccountStatus.None) {
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
        ConfirmationDialog().show(parentFragmentManager, "confirmation_dialog")
    }

    private fun getErrorMessage(exception: Exception): String {
        return getString(when(exception.message) {
            CrowdNodeException.WITHDRAWAL_ERROR -> R.string.crowdnode_withdraw_error
            CrowdNodeException.DEPOSIT_ERROR -> R.string.crowdnode_deposit_error
            CrowdNodeException.CONFIRMATION_ERROR -> R.string.crowdnode_bad_confirmation
            CrowdNodeException.SEND_MESSAGE_ERROR -> R.string.crowdnode_signup_error
            else -> R.string.crowdnode_transfer_error
        })
    }

    private fun showInfoDialog() {
        if (viewModel.signUpStatus == SignUpStatus.LinkedOnline) {
            OnlineAccountDetailsDialog().show(parentFragmentManager, "online_account_details")
        } else {
            AdaptiveDialog.create(
                R.drawable.ic_info_blue_encircled,
                getString(R.string.crowdnode_your_address_title),
                viewModel.accountAddress.value?.toBase58() ?: "",
                getString(R.string.button_close),
                getString(R.string.button_copy_address)
            ).show(requireActivity()) { toCopy ->
                if (toCopy == true) {
                    viewModel.accountAddress.value?.toBase58()?.copy(requireActivity(), "dash address")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        this.balanceAnimator = null
    }
}