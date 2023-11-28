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
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.blinkAnimator
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.ui.wiggle
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentTransferBinding
import org.dash.wallet.integrations.crowdnode.databinding.ViewKeyboardDepositHeaderBinding
import org.dash.wallet.integrations.crowdnode.databinding.ViewKeyboardWithdrawHeaderBinding
import org.dash.wallet.integrations.crowdnode.model.ApiCode
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.WithdrawalLimitPeriod
import org.dash.wallet.integrations.crowdnode.model.WithdrawalLimitsException
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.dash.wallet.integrations.crowdnode.ui.dialogs.StakingDialog
import org.dash.wallet.integrations.crowdnode.ui.dialogs.WithdrawalLimitsInfoDialog
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import javax.inject.Inject

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class TransferFragment : Fragment(R.layout.fragment_transfer) {
    private val binding by viewBinding(FragmentTransferBinding::bind)
    private val args by navArgs<TransferFragmentArgs>()
    private val viewModel by activityViewModels<CrowdNodeViewModel>()
    private val amountViewModel by activityViewModels<EnterAmountViewModel>()
    private var balanceAnimator: ObjectAnimator? = null

    @Inject
    lateinit var securityFunctions: AuthenticationManager

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState == null) {
            val fragment = EnterAmountFragment.newInstance(
                dashToFiat = amountViewModel.dashToFiatDirection.value ?: true,
                isMaxButtonVisible = true,
                showCurrencySelector = true
            )

            val headerBinding = if (args.withdraw) {
                ViewKeyboardWithdrawHeaderBinding.inflate(layoutInflater, null, false)
            } else {
                ViewKeyboardDepositHeaderBinding.inflate(layoutInflater, null, false)
            }

            val buttonText = if (args.withdraw) {
                getString(R.string.withdraw)
            } else {
                getString(R.string.deposit)
            }

            fragment.setViewDetails(buttonText, headerBinding.root)

            parentFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_fragment_placeholder, fragment)
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        if (args.withdraw) {
            setupWithdraw()
        } else {
            setupDeposit()
        }

        viewModel.observeCrowdNodeError().observe(viewLifecycleOwner) { error ->
            error?.let {
                safeNavigate(
                    TransferFragmentDirections.transferToResult(
                        true,
                        getString(
                            if (args.withdraw) {
                                R.string.crowdnode_withdraw_error
                            } else {
                                R.string.crowdnode_deposit_error
                            }
                        ),
                        ""
                    )
                )
            }
        }

        amountViewModel.selectedExchangeRate.observe(viewLifecycleOwner) { rate ->
            binding.toolbarSubtitle.text = if (rate != null) {
                getString(
                    R.string.exchange_rate_template,
                    Coin.COIN.toPlainString(),
                    rate.fiat.toFormattedString()
                )
            } else {
                ""
            }
        }

        amountViewModel.dashToFiatDirection.observe(viewLifecycleOwner) {
            updateAvailableBalance()
        }

        amountViewModel.selectedExchangeRate.observe(viewLifecycleOwner) {
            updateAvailableBalance()
        }

        amountViewModel.amount.observe(viewLifecycleOwner) { amount ->
            val maxValue = if (args.withdraw) {
                viewModel.crowdNodeBalance.value?.balance
            } else {
                viewModel.dashBalance.value
            } ?: Coin.ZERO

            binding.balanceText.setTextAppearance(
                if (amount > maxValue) {
                    R.style.Caption_Red
                } else {
                    R.style.Caption_Secondary
                }
            )
        }

        amountViewModel.onContinueEvent.observe(viewLifecycleOwner) { pair ->
            lifecycleScope.launch {
                continueTransfer(pair.first, args.withdraw)
            }
        }
    }

    private fun setupWithdraw() {
        binding.toolbarTitle.text = getString(R.string.withdraw)
        binding.sourceIcon.setImageResource(R.drawable.ic_crowdnode_logo)
        binding.sourceLabel.text = getString(R.string.from_crowdnode)

        viewModel.crowdNodeBalance.observe(viewLifecycleOwner) { state ->
            updateAvailableBalance()

            if (state.isUpdating) {
                this.balanceAnimator?.start()
            } else {
                this.balanceAnimator?.end()
            }
        }

        this.balanceAnimator = binding.balanceText.blinkAnimator
    }

    private fun setupDeposit() {
        binding.toolbarTitle.text = getString(R.string.deposit)
        binding.sourceIcon.setImageResource(R.drawable.ic_dash_pay)
        binding.sourceLabel.text = getString(R.string.from_wallet)

        if (viewModel.shouldShowFirstDepositBanner) {
            binding.messageBanner.isVisible = true
            binding.bannerMessageText.text = getString(
                R.string.crowdnode_first_deposit,
                CrowdNodeConstants.DASH_FORMAT.format(CrowdNodeConstants.MINIMUM_DASH_DEPOSIT)
            )
            binding.messageBanner.setOnClickListener {
                StakingDialog().show(parentFragmentManager, "staking")
            }
        }

        lifecycleScope.launch {
            showWithdrawalLimitsInfo()
        }

        viewModel.dashBalance.observe(viewLifecycleOwner) {
            updateAvailableBalance()
        }
    }

    private suspend fun continueTransfer(value: Coin, isWithdraw: Boolean) {
        if (!isWithdraw) {
            if (viewModel.shouldShowFirstDepositBanner &&
                value.isLessThan(CrowdNodeConstants.MINIMUM_DASH_DEPOSIT)
            ) {
                showErrorBanner()
                return
            }

            securityFunctions.authenticate(requireActivity()) ?: return
        }

        val isSuccess = AdaptiveDialog.withProgress(getString(R.string.please_wait_title), requireActivity()) {
            if (isWithdraw) {
                handleWithdraw(value)
            } else {
                handleDeposit(value)
            }
        }

        if (isSuccess) {
            if (isWithdraw) {
                viewModel.logEvent(AnalyticsConstants.CrowdNode.WITHDRAWAL_REQUESTED)
                safeNavigate(
                    TransferFragmentDirections.transferToResult(
                        false,
                        getString(R.string.withdrawal_requested),
                        getString(R.string.withdrawal_requested_message)
                    )
                )
            } else {
                viewModel.logEvent(AnalyticsConstants.CrowdNode.DEPOSIT_REQUESTED)
                safeNavigate(
                    TransferFragmentDirections.transferToResult(
                        false,
                        getString(R.string.deposit_sent),
                        getString(R.string.deposit_sent_message)
                    )
                )
            }
        }
    }

    private suspend fun handleDeposit(value: Coin): Boolean {
        try {
            viewModel.deposit(value, true)
            return true
        } catch (ex: LeftoverBalanceException) {
            val result = MinimumBalanceDialog().showAsync(requireActivity())

            if (result == true) {
                viewModel.deposit(value, false)
                return true
            }
        }

        return false
    }

    private suspend fun handleWithdraw(value: Coin): Boolean {
        return try {
            return viewModel.withdraw(value)
        } catch (ex: WithdrawalLimitsException) {
            showWithdrawalLimitsError(ex.period)
            false
        }
    }

    private fun updateAvailableBalance() {
        val balance = if (args.withdraw) {
            viewModel.crowdNodeBalance.value?.balance
        } else {
            viewModel.dashBalance.value
        } ?: Coin.ZERO

        val minValue = if (args.withdraw) {
            balance.div(ApiCode.WithdrawAll.code)
        } else {
            CrowdNodeConstants.API_OFFSET + Coin.valueOf(ApiCode.MaxCode.code)
        }

        amountViewModel.setMinAmount(minValue)
        amountViewModel.setMaxAmount(balance)

        val dashToFiat = amountViewModel.dashToFiatDirection.value == true
        val rate = amountViewModel.selectedExchangeRate.value
        setAvailableBalanceText(balance, rate, dashToFiat)
    }

    private fun setAvailableBalanceText(balance: Coin, exchangeRate: ExchangeRate?, dashToFiat: Boolean) {
        val rate = exchangeRate?.let { org.bitcoinj.utils.ExchangeRate(Coin.COIN, it.fiat) }

        binding.balanceText.text = when {
            dashToFiat -> getString(R.string.available_balance, balance.toFriendlyString())
            rate != null -> getString(
                R.string.available_balance,
                rate.coinToFiat(balance).toFormattedString()
            )
            else -> ""
        }
    }

    private fun showErrorBanner() {
        binding.messageBanner.setBackgroundColor(resources.getColor(R.color.content_warning, null))
        binding.messageBanner.wiggle()
    }

    private suspend fun showWithdrawalLimitsInfo() {
        if (viewModel.shouldShowWithdrawalLimitsInfo()) {
            val limits = viewModel.getWithdrawalLimits()
            val result = WithdrawalLimitsInfoDialog(
                limits[0],
                limits[1],
                limits[2]
            ).showAsync(requireActivity())

            if (result == false) {
                viewModel.triggerWithdrawalLimitsShown()
            }
        }
    }

    private suspend fun showWithdrawalLimitsError(period: WithdrawalLimitPeriod) {
        if (period == WithdrawalLimitPeriod.PerBlock) {
            AdaptiveDialog.create(
                R.drawable.ic_warning,
                getString(R.string.crowdnode_withdrawal_limits_per_block_title),
                getString(R.string.crowdnode_withdrawal_limits_per_block_message),
                getString(R.string.button_okay)
            ).showAsync(requireActivity())
        } else {
            val limits = viewModel.getWithdrawalLimits()
            val okButtonText = if (period == WithdrawalLimitPeriod.PerTransaction) {
                if (viewModel.onlineAccountStatus == OnlineAccountStatus.Done) {
                    getString(R.string.read_withdrawal_policy)
                } else {
                    getString(R.string.online_account_create)
                }
            } else {
                ""
            }

            val doAction = WithdrawalLimitsInfoDialog(
                limits[0],
                limits[1],
                limits[2],
                highlightedLimit = period,
                okButtonText = okButtonText
            ).showAsync(requireActivity())

            if (doAction == true) {
                if (viewModel.onlineAccountStatus == OnlineAccountStatus.Done) {
                    openWithdrawalPolicy()
                } else {
                    safeNavigate(TransferFragmentDirections.transferToOnlineAccountEmail())
                }
            }
        }
    }

    private fun openWithdrawalPolicy() {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.crowdnode_withdrawal_policy)))
        startActivity(browserIntent)
    }
}
