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
import android.animation.TimeInterpolator
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
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ISecurityFunctions
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentTransferBinding
import org.dash.wallet.integrations.crowdnode.databinding.ViewKeyboardDepositHeaderBinding
import org.dash.wallet.integrations.crowdnode.databinding.ViewKeyboardWithdrawHeaderBinding
import org.dash.wallet.integrations.crowdnode.model.ApiCode
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.dash.wallet.integrations.crowdnode.ui.dialogs.StakingDialog
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import kotlin.math.exp
import kotlin.math.sin
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
    lateinit var securityFunctions: ISecurityFunctions

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
                safeNavigate(TransferFragmentDirections.transferToResult(
                    true,
                    getString(if (args.withdraw) {
                        R.string.crowdnode_withdraw_error
                    } else {
                        R.string.crowdnode_deposit_error
                    }),
                    ""
                ))
            }
        }

        amountViewModel.selectedExchangeRate.observe(viewLifecycleOwner) { rate ->
            binding.toolbarSubtitle.text = getString(
                R.string.exchange_rate_template,
                Coin.COIN.toPlainString(),
                GenericUtils.fiatToString(rate.fiat)
            )
        }

        amountViewModel.dashToFiatDirection.observe(viewLifecycleOwner) {
            updateAvailableBalance()
        }

        amountViewModel.selectedExchangeRate.observe(viewLifecycleOwner) {
            updateAvailableBalance()
        }

        amountViewModel.amount.observe(viewLifecycleOwner) { amount ->
            val maxValue = if (args.withdraw) viewModel.crowdNodeBalance else viewModel.dashBalance

            binding.balanceText.setTextAppearance(
                if (amount > (maxValue.value ?: Coin.ZERO)) {
                    R.style.Caption_Red
                } else {
                    R.style.Caption_SteelGray
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

        viewModel.crowdNodeBalance.observe(viewLifecycleOwner) {
            updateAvailableBalance()
        }

        this.balanceAnimator = ObjectAnimator.ofFloat(
            binding.balanceText,
            View.ALPHA.name,
            0f, 0.5f
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

        viewModel.dashBalance.observe(viewLifecycleOwner) {
            updateAvailableBalance()
        }
    }

    private suspend fun continueTransfer(value: Coin, isWithdraw: Boolean) {
        if (!isWithdraw) {
            if (viewModel.shouldShowFirstDepositBanner &&
                value.isLessThan(CrowdNodeConstants.MINIMUM_DASH_DEPOSIT)
            ) {
                showBannerError()
                return
            }

            securityFunctions.requestPinCode(requireActivity()) ?: return
        }

        val isSuccess = AdaptiveDialog.withProgress(getString(R.string.please_wait_title), requireActivity()) {
            if (isWithdraw) {
                viewModel.withdraw(value)
            } else {
                viewModel.deposit(value)
            }
        }


        if (isSuccess) {
            if (isWithdraw) {
                safeNavigate(TransferFragmentDirections.transferToResult(
                    false,
                    getString(R.string.withdrawal_requested),
                    getString(R.string.withdrawal_requested_message)
                ))
            } else {
                safeNavigate(TransferFragmentDirections.transferToResult(
                    false,
                    getString(R.string.deposit_sent),
                    getString(R.string.deposit_sent_message)
                ))
            }
        }
    }

    private fun updateAvailableBalance() {
        val balance = if (args.withdraw) {
            viewModel.crowdNodeBalance.value
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
            rate != null -> getString(R.string.available_balance,
                GenericUtils.fiatToString(rate.coinToFiat(balance)))
            else -> ""
        }
    }

    private fun showBannerError() {
        binding.messageBanner.setBackgroundColor(resources.getColor(R.color.content_warning, null))
        runWiggleAnimation(binding.messageBanner)
    }

    private fun runWiggleAnimation(view: View) {
        val frequency = 3f
        val decay = 2f
        val decayingSineWave = TimeInterpolator { input ->
            val raw = sin(frequency * input * 2 * Math.PI)
            (raw * exp((-input * decay).toDouble())).toFloat()
        }

        view.animate()
            .xBy(-100f)
            .setInterpolator(decayingSineWave)
            .setDuration(300)
            .start()
    }
}