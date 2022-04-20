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

package org.dash.wallet.integration.coinbase_integration.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.fragment.app.Fragment
import android.view.View
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.VALUE_ZERO
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.EnterAmountToTransferFragmentBinding
import org.dash.wallet.integration.coinbase_integration.viewmodels.EnterAmountToTransferViewModel

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class EnterAmountToTransferFragment : Fragment(R.layout.enter_amount_to_transfer_fragment) {

    companion object {
        private const val DECIMAL_SEPARATOR = '.'
        fun newInstance() = EnterAmountToTransferFragment()
    }

    private val viewModel by activityViewModels<EnterAmountToTransferViewModel>()
    private val binding  by viewBinding(EnterAmountToTransferFragmentBinding::bind)
    private var exchangeRate: ExchangeRate? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.keyboardView.onKeyboardActionListener = keyboardActionListener
        binding.currencyOptions.pickedOptionIndex = 0

        binding.currencyOptions.setOnOptionPickedListener { value, index ->
            viewModel.isFiatSelected = index == 1
            val cleanedValue = viewModel.formatInput
            formatTransferredAmount(cleanedValue)
        }

        binding.transferBtn.setOnClickListener {
            exchangeRate?.let { rate ->
                val fiatAmount = if (viewModel.isFiatSelected){
                    Fiat.parseFiat(rate.fiat.currencyCode, viewModel.inputValue)
                } else {
                    val value = viewModel.applyCoinbaseExchangeRateToDash
                    Fiat.parseFiat(rate.fiat.currencyCode, value)
                }

                val dashAmount = viewModel.applyCoinbaseExchangeRateToFiat
                val coin = try {
                    Coin.parseCoin(dashAmount)
                } catch (x: Exception) {
                    Coin.ZERO
                }
                val pair = Pair(fiatAmount, coin)
                viewModel.onContinueTransferEvent.value = Pair(
                    viewModel.transferDirectionState.value ?: false, pair
                )
            }

        }

        binding.maxButton.setOnClickListener {
            binding.currencyOptions.pickedOptionIndex = 0
            formatTransferredAmount(viewModel.maxValue)
            viewModel.isMaxAmountSelected = true
        }

        viewModel.localCurrencyExchangeRate.observe(viewLifecycleOwner){
            exchangeRate = ExchangeRate(Coin.COIN, it.fiat)
        }
    }

    private fun formatTransferredAmount(value: String){
        val text = viewModel.applyNewValue(value, binding.currencyOptions.pickedOption)
        val spannableString = SpannableString(text).apply {
            if (binding.currencyOptions.pickedOptionIndex == 0){
                spanAmount(this, viewModel.formattedValue.length, text.length)
            } else {
                if (GenericUtils.isCurrencyFirst(viewModel.fiatAmount) && text.length - viewModel.fiatBalance.length > 0) {
                    spanAmount(this, 0, text.length - viewModel.fiatBalance.length)
                } else {
                    spanAmount(this, viewModel.inputValue.length, text.length)
                }
            }
        }

        binding.inputAmount.text = spannableString
        binding.transferBtn.isEnabled = viewModel.hasBalance

        convertAmountTransferred()
    }

    private fun spanAmount(spannable: SpannableString, from: Int, to: Int): SpannableString {
        val textSize = 21.0f / binding.inputAmount.paint.textSize
        return spannable.apply {
            setSpan(
                RelativeSizeSpan(textSize), from,
                to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                context?.resources?.getColor(R.color.gray_900)
                    ?.let { ForegroundColorSpan(it) },
                from,
                to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun convertAmountTransferred(){
        if (viewModel.hasBalance){
            viewModel.convertToDash()
        } else {
            viewModel.setEnteredConvertDashAmount(Coin.ZERO)
        }
    }

    private val keyboardActionListener = object: NumericKeyboardView.OnKeyboardActionListener {

        var value = StringBuilder()

        private fun refreshValue(){
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
            if (inputValue != VALUE_ZERO)
                value.append(inputValue)
        }

        private fun appendIfValidAfter(number: String) {
            try {
                value.append(number)
                val formattedValue = GenericUtils.formatFiatWithoutComma(value.toString())
                Coin.parseCoin(formattedValue)
            } catch (e: Exception) {
                value.deleteCharAt(value.length - 1)
            }
        }

        override fun onNumber(number: Int) {
            refreshValue()
            if (value.toString() == VALUE_ZERO) {
                return
            }
            val isFraction = value.toString().indexOf(viewModel.decimalSeparator) > -1

            if (isFraction) {
                val lengthOfDecimalPart = value.toString().length - value.toString().indexOf(viewModel.decimalSeparator)
                val decimalsThreshold = if (binding.currencyOptions.pickedOptionIndex == 1) 2 else 8

                if (lengthOfDecimalPart > decimalsThreshold) {
                    return
                }
            }
            if (!viewModel.isMaxAmountSelected){
                try {
                    appendIfValidAfter(number.toString())
                    formatTransferredAmount(value.toString())
                } catch (e: Exception){
                    value.deleteCharAt(value.length - 1)
                    formatTransferredAmount(value.toString())
                }
            }

        }

        override fun onBack(longClick: Boolean) {
            refreshValue()
            if (longClick || viewModel.isMaxAmountSelected) {
                value.clear()
            } else if (value.isNotEmpty()) {
                value.deleteCharAt(value.length - 1)
            }
            formatTransferredAmount(value.toString())
            viewModel.isMaxAmountSelected = false
        }

        override fun onFunction() {
            if (viewModel.isMaxAmountSelected) return
            refreshValue()
            if (value.indexOf(DECIMAL_SEPARATOR) == -1) {
                if (value.isEmpty()) {
                    value.append(VALUE_ZERO)
                }
                value.append(DECIMAL_SEPARATOR)
            }
            formatTransferredAmount(value.toString())
        }
    }
}