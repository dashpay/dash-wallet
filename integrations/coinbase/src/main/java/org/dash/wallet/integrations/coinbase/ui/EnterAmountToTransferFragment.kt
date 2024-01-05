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

package org.dash.wallet.integrations.coinbase.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.isCurrencyFirst
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.R
import org.dash.wallet.integrations.coinbase.databinding.EnterAmountToTransferFragmentBinding
import org.dash.wallet.integrations.coinbase.viewmodels.EnterAmountToTransferViewModel
import org.dash.wallet.integrations.coinbase.viewmodels.coinbaseViewModels

@AndroidEntryPoint
class EnterAmountToTransferFragment : Fragment(R.layout.enter_amount_to_transfer_fragment) {

    companion object {
        private const val DECIMAL_SEPARATOR = '.'
        fun newInstance() = EnterAmountToTransferFragment()
    }

    private val viewModel by coinbaseViewModels<EnterAmountToTransferViewModel>()
    private val binding by viewBinding(EnterAmountToTransferFragmentBinding::bind)
    private var exchangeRate: ExchangeRate? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.isFiatSelected = false
        viewModel.isMaxAmountSelected = false
        binding.currencyOptions.apply {
            pickedOptionIndex = 0
            provideOptions(listOf(Constants.DASH_CURRENCY, viewModel.localCurrencyCode))
        }
        binding.keyboardView.onKeyboardActionListener = keyboardActionListener
        formatTransferredAmount(CoinbaseConstants.VALUE_ZERO)

        binding.currencyOptions.setOnOptionPickedListener { value, index ->
            viewModel.isFiatSelected = index == 1
            val cleanedValue = viewModel.formatInput
            formatTransferredAmount(cleanedValue)
        }

        binding.transferBtn.setOnClickListener {
            val cleanedInput = GenericUtils.formatFiatWithoutComma(viewModel.inputValue)
            val fiatAmount: Fiat
            val dashAmount: Coin
            if (viewModel.isFiatSelected) {
                fiatAmount = exchangeRate?.let { rate ->
                    Fiat.parseFiat(rate.fiat.currencyCode, cleanedInput)
                } ?: Fiat.parseFiat(CoinbaseConstants.DEFAULT_CURRENCY_USD, CoinbaseConstants.VALUE_ZERO)
                dashAmount = viewModel.applyExchangeRateToFiat(fiatAmount)
            } else {
                dashAmount = Coin.parseCoin(cleanedInput)
                fiatAmount = exchangeRate?.coinToFiat(dashAmount)
                    ?: Fiat.parseFiat(CoinbaseConstants.DEFAULT_CURRENCY_USD, CoinbaseConstants.VALUE_ZERO)
            }

            viewModel.onContinueTransferEvent.value = Pair(fiatAmount, dashAmount)
        }

        binding.maxButton.setOnClickListener {
            viewModel.isMaxAmountSelected = true
            formatTransferredAmount(viewModel.maxValue)
        }

        viewModel.localCurrencyExchangeRate.observe(viewLifecycleOwner) {
            exchangeRate = it?.let { ExchangeRate(Coin.COIN, it.fiat) }
        }

        viewModel.keyboardStateCallback.observe(viewLifecycleOwner) {
            binding.bottomCard.isVisible = it
        }
    }

    private fun formatTransferredAmount(value: String) {
        val text = viewModel.applyNewValue(value, binding.currencyOptions.pickedOption)
        val spannableString = SpannableString(text).apply {
            if (binding.currencyOptions.pickedOptionIndex == 0) {
                spanAmount(this, viewModel.formattedValue.length, text.length)
            } else {
                if (viewModel.fiatAmount?.isCurrencyFirst() == true && text.length - viewModel.fiatBalance.length > 0) {
                    spanAmount(this, 0, text.length - viewModel.fiatBalance.length)
                } else {
                    spanAmount(this, viewModel.inputValue.length, text.length)
                }
            }
        }

        binding.inputAmount.text = spannableString
        binding.transferBtn.isEnabled = viewModel.hasBalance
        viewModel.setBalanceForWallet()
    }

    private fun spanAmount(spannable: SpannableString, from: Int, to: Int): SpannableString {
        val textSize = 21.0f / binding.inputAmount.paint.textSize
        return spannable.apply {
            setSpan(
                RelativeSizeSpan(textSize),
                from,
                to,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                context?.resources?.getColor(R.color.content_primary, null)
                    ?.let { ForegroundColorSpan(it) },
                from,
                to,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private val keyboardActionListener = object: NumericKeyboardView.OnKeyboardActionListener {

        var value = StringBuilder()

        private fun refreshValue() {
            value.clear()
            val inputValue = if (binding.currencyOptions.pickedOptionIndex == 1) {
                val localCurrencySymbol =
                    GenericUtils.getLocalCurrencySymbol(viewModel.localCurrencyCode)
                binding.inputAmount.text.split(" ")
                    .first { it != localCurrencySymbol }
            } else {
                binding.inputAmount.text.split(" ")
                    .first { it != binding.currencyOptions.pickedOption }
            }
            if (inputValue != CoinbaseConstants.VALUE_ZERO) {
                value.append(inputValue)
            }
        }

        private fun appendIfValidAfter(number: String) {
            try {
                value.append(number)
                val formattedValue = GenericUtils.formatFiatWithoutComma(value.toString())
                Coin.parseCoin(formattedValue)
                formatTransferredAmount(value.toString())
            } catch (e: Exception) {
                value.deleteCharAt(value.length - 1)
                formatTransferredAmount(value.toString())
            }
        }

        override fun onNumber(number: Int) {
            refreshValue()
            if (value.toString() == CoinbaseConstants.VALUE_ZERO) {
                value.clear()
            }
            val formattedValue = value.toString()
            val isFraction = formattedValue.indexOf(viewModel.decimalSeparator) > -1

            if (isFraction) {
                val lengthOfDecimalPart = formattedValue.length - formattedValue.indexOf(viewModel.decimalSeparator)
                val decimalsThreshold = if (binding.currencyOptions.pickedOptionIndex == 1) 2 else 8

                if (lengthOfDecimalPart > decimalsThreshold) {
                    return
                }
            }

            if (!viewModel.isMaxAmountSelected) {
                appendIfValidAfter(number.toString())
            }
        }

        override fun onBack(longClick: Boolean) {
            refreshValue()
            if (longClick || viewModel.isMaxAmountSelected) {
                value.clear()
            } else if (value.isNotEmpty()) {
                value.deleteCharAt(value.length - 1)
            }
            viewModel.removeBannerCallback.call()
            formatTransferredAmount(value.toString())
            viewModel.isMaxAmountSelected = false
        }

        override fun onFunction() {
            if (viewModel.isMaxAmountSelected) return
            refreshValue()
            if (value.indexOf(DECIMAL_SEPARATOR) < 0) {
                if (value.isEmpty()) {
                    value.append(CoinbaseConstants.VALUE_ZERO)
                }
                value.append(DECIMAL_SEPARATOR)
            }
            formatTransferredAmount(value.toString())
        }
    }

    fun setViewDetails(continueText: String, keyboardHeader: View?) {
        lifecycleScope.launchWhenStarted {
            binding.transferBtn.text = continueText
            keyboardHeader?.let {
                binding.keyboardContainer.addView(keyboardHeader, 0)
            }
        }
    }
}
