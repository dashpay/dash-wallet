/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.common.ui.enter_amount

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Monetary
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.FragmentEnterAmountBinding
import org.dash.wallet.common.ui.exchange_rates.ExchangeRatesDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import java.text.DecimalFormatSymbols

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class EnterAmountFragment: Fragment(R.layout.fragment_enter_amount) {
    companion object {
        private const val ARG_INITIAL_AMOUNT = "initial_amount"
        private const val ARG_DASH_TO_FIAT = "dash_to_fiat"
        private const val ARG_MAX_BUTTON_VISIBLE = "max_visible"
        private const val ARG_SHOW_CURRENCY_SELECTOR_BUTTON = "show_currency_selector"

        @JvmStatic
        fun newInstance(
            dashToFiat: Boolean = false,
            initialAmount: Monetary? = null,
            isMaxButtonVisible: Boolean = true,
            showCurrencySelector: Boolean = true
        ): EnterAmountFragment {
            val args = bundleOf(
                ARG_DASH_TO_FIAT to dashToFiat,
                ARG_MAX_BUTTON_VISIBLE to isMaxButtonVisible,
                ARG_SHOW_CURRENCY_SELECTOR_BUTTON to showCurrencySelector
            )
            initialAmount?.let { args.putSerializable(ARG_INITIAL_AMOUNT, it) }

            return EnterAmountFragment().apply {
                arguments = args
            }
        }
    }

    private val binding by viewBinding(FragmentEnterAmountBinding::bind)
    private val viewModel: EnterAmountViewModel by activityViewModels()
    private val decimalSeparator = DecimalFormatSymbols.getInstance(GenericUtils.getDeviceLocale()).decimalSeparator
    private var maxSelected: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        binding.maxButtonWrapper.isVisible = args.getBoolean(ARG_MAX_BUTTON_VISIBLE)
        binding.amountView.showCurrencySelector = args.getBoolean(ARG_SHOW_CURRENCY_SELECTOR_BUTTON)
        val dashToFiat = args.getBoolean(ARG_DASH_TO_FIAT)
        binding.amountView.dashToFiat = dashToFiat

        if (args.containsKey(ARG_INITIAL_AMOUNT)) {
            val initialAmount = args.getSerializable(ARG_INITIAL_AMOUNT)
            binding.amountView.input = when {
                initialAmount is Coin && dashToFiat -> initialAmount.toPlainString()
                initialAmount is Fiat && !dashToFiat -> initialAmount.toPlainString()
                else -> throw IllegalArgumentException("dashToFiat argument and type of initialAmount do not match")
            }
        }

        binding.keyboardView.onKeyboardActionListener = keyboardActionListener
        binding.continueBtn.setOnClickListener {
            viewModel.onContinueEvent.value = Pair(
                binding.amountView.dashAmount,
                binding.amountView.fiatAmount
            )
        }

        binding.maxButton.setOnClickListener {
            binding.amountView.dashToFiat = true
            binding.amountView.input = viewModel.maxAmount.toPlainString()
            maxSelected = true
        }

        binding.amountView.setOnCurrencyToggleClicked {
            parentFragmentManager.let { fragmentManager ->
                ExchangeRatesDialog(viewModel.selectedCurrencyCode) { rate, _, dialog ->
                    viewModel.selectedCurrencyCode = rate.currencyCode
                    dialog.dismiss()
                }.show(fragmentManager, "payment_method")
            }
        }

        viewModel.selectedExchangeRate.observe(viewLifecycleOwner) {
            binding.amountView.exchangeRate = ExchangeRate(Coin.COIN, it.fiat)
        }
    }

    fun setViewDetails(continueText: String, keyboardHeader: View?) {
        lifecycleScope.launchWhenStarted {
            binding.continueBtn.text = continueText
            keyboardHeader?.let {
                binding.keyboardContainer.addView(keyboardHeader, 0)
                binding.keyboardHeaderDivider.isVisible = true
            }
        }
    }

    private val keyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {
        var value = StringBuilder()

        fun refreshValue() {
            value.clear()
            value.append(binding.amountView.input)
        }

        override fun onNumber(number: Int) {
            refreshValue()

            if (value.toString() == "0") {
                // avoid entering leading zeros without decimal separator
                value.clear()
            }

            val formattedValue = value.toString()
            val isFraction = formattedValue.indexOf(decimalSeparator) > -1

            if (isFraction) {
                val lengthOfDecimalPart = formattedValue.length - formattedValue.indexOf(decimalSeparator)
                val decimalsThreshold = if (binding.amountView.dashToFiat) 8 else 2

                if (lengthOfDecimalPart > decimalsThreshold) {
                    return
                }
            }

            if (!maxSelected) {
                value.append(number)

                try {
                    binding.amountView.input = value.toString()
                } catch (ex: Exception) {
                    value.deleteCharAt(value.length - 1)
                    binding.amountView.input = value.toString()
                }
            }
        }

        override fun onBack(longClick: Boolean) {
            refreshValue()

            if (longClick || maxSelected) {
                value.clear()
            } else if (value.isNotEmpty()) {
                value.deleteCharAt(value.length - 1)
            }

            binding.amountView.input = value.toString()
            maxSelected = false
        }

        override fun onFunction() {
            if (maxSelected) {
                return
            }

            refreshValue()

            if (value.indexOf(decimalSeparator) < 0) {
                value.append(decimalSeparator)
            }

            binding.amountView.input = value.toString()
        }
    }
}