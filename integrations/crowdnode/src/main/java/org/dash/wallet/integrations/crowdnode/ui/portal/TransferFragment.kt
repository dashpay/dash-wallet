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

import android.os.Bundle
import android.view.View
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
import org.dash.wallet.common.services.SecurityModel
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
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import javax.inject.Inject

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class TransferFragment : Fragment(R.layout.fragment_transfer) {
    private val binding by viewBinding(FragmentTransferBinding::bind)
    private val args by navArgs<TransferFragmentArgs>()
    private val viewModel by activityViewModels<CrowdNodeViewModel>()
    private val amountViewModel by activityViewModels<EnterAmountViewModel>()

    @Inject
    lateinit var securityModel: SecurityModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState == null) {
            val fragment = EnterAmountFragment.newInstance(
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

        viewModel.dashBalance.observe(viewLifecycleOwner) { balance ->
            amountViewModel.setMaxAmount(balance)
            val dashToFiat = amountViewModel.dashToFiatDirection.value == true
            val rate = amountViewModel.selectedExchangeRate.value
            setAvailableBalance(balance, rate, dashToFiat)
        }

        viewModel.crowdNodeError.observe(viewLifecycleOwner) { error ->
            error?.let {
                // TODO: withdraw
                safeNavigate(TransferFragmentDirections.transferToResult(
                    true, getString(R.string.crowdnode_deposit_error), "")
                )
            }
        }

        amountViewModel.selectedExchangeRate.observe(viewLifecycleOwner) { rate ->
            binding.toolbarSubtitle.text = getString(
                R.string.exchange_rate_template,
                Coin.COIN.toPlainString(),
                GenericUtils.fiatToString(rate.fiat)
            )
        }

        amountViewModel.dashToFiatDirection.observe(viewLifecycleOwner) { dashToFiat ->
            val balance = viewModel.dashBalance.value ?: Coin.ZERO
            val rate = amountViewModel.selectedExchangeRate.value
            setAvailableBalance(balance, rate, dashToFiat)
        }

        amountViewModel.selectedExchangeRate.observe(viewLifecycleOwner) { rate ->
            val balance = viewModel.dashBalance.value ?: Coin.ZERO
            val dashToFiat = amountViewModel.dashToFiatDirection.value == true
            setAvailableBalance(balance, rate, dashToFiat)
        }

        amountViewModel.amount.observe(viewLifecycleOwner) { amount ->
            binding.availableDashText.setTextAppearance(
                if (amount > viewModel.dashBalance.value ?: Coin.ZERO) {
                    R.style.Caption_Red
                } else {
                    R.style.Caption_SteelGray
                }
            )
        }

        amountViewModel.onContinueEvent.observe(viewLifecycleOwner) { pair ->
            lifecycleScope.launch {
                continueTransfer(pair.first)
            }
        }
    }

    private suspend fun continueTransfer(value: Coin) {
        securityModel.requestPinCode(requireActivity()) ?: return

        var result = false
        AdaptiveDialog.withProgress(getString(R.string.please_wait_title), requireActivity()) {
            result = if (args.withdraw) {
                viewModel.withdraw(value)
            } else {
                viewModel.deposit(value)
            }
        }

        if (result) {
            // TODO: withdraw
            safeNavigate(TransferFragmentDirections.transferToResult(
                false,
                getString(R.string.deposit_sent),
                getString(R.string.deposit_sent_message)
            ))
        }
    }

    private fun setAvailableBalance(balance: Coin, exchangeRate: ExchangeRate?, dashToFiat: Boolean) {
        val rate = exchangeRate?.let { org.bitcoinj.utils.ExchangeRate(Coin.COIN, it.fiat) }

        binding.availableDashText.text = when {
            dashToFiat -> getString(R.string.available_balance, balance.toFriendlyString())
            rate != null -> getString(R.string.available_balance,
                GenericUtils.fiatToString(rate.coinToFiat(balance)))
            else -> ""
        }
    }
}