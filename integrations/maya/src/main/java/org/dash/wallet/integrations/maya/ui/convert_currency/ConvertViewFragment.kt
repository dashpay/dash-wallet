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

package org.dash.wallet.integrations.maya.ui.convert_currency

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.FragmentConvertCurrencyViewBinding
import org.dash.wallet.integrations.maya.model.AccountDataUIModel
import org.dash.wallet.integrations.maya.ui.mayaViewModels
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols

@AndroidEntryPoint
class ConvertViewFragment : Fragment(R.layout.fragment_convert_currency_view) {
    companion object {
        private const val ARG_DASH_TO_FIAT = "dash_to_fiat"
        private const val DECIMAL_SEPARATOR = '.'

        @JvmStatic
        fun newInstance(): ConvertViewFragment {
            return ConvertViewFragment()
        }
    }

    private val binding by viewBinding(FragmentConvertCurrencyViewBinding::bind)
    private val viewModel by mayaViewModels<ConvertViewViewModel>()
    private val format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode()
    private val decimalSeparator =
        DecimalFormatSymbols.getInstance(GenericUtils.getDeviceLocale()).decimalSeparator
    private var maxAmountSelected: Boolean = false
    private var currencyConversionOptionList: List<String> = emptyList()
    private var hasInternet: Boolean = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.keyboardView.onKeyboardActionListener = keyboardActionListener
        binding.continueBtn.isEnabled = false
        binding.continueBtn.setOnClickListener {
            viewModel.continueSwap(binding.currencyOptions.pickedOption)
        }

        viewModel.selectedCryptoCurrencyAccount.observe(viewLifecycleOwner) {
            maxAmountSelected = false
            resetViewSelection(it)
        }

        viewModel.dashToCrypto.observe(viewLifecycleOwner) {
            viewModel.selectedCryptoCurrencyAccount.value?.let {
                resetViewSelection(it)
            }
        }

        binding.inputAmount.showResultContainer = false
        binding.inputAmount.showCurrencySelector = false

        binding.bottomCard.isVisible = false

        binding.currencyOptions.pickedOptionIndex = 0

        binding.maxButton.setOnClickListener {
            viewModel.selectedCryptoCurrencyAccount.value?.let { userAccountData ->
                getMaxAmount()?.let { maxAmount ->
                    val currency = userAccountData.coinbaseAccount.currency

                    if (viewModel.selectedPickerCurrencyCode == currency) {
                        applyNewValue(maxAmount, viewModel.selectedPickerCurrencyCode)
                    } else {
                        val cleanedValue =
                            if (viewModel.selectedPickerCurrencyCode == viewModel.selectedLocalCurrencyCode) {
                                maxAmount.toBigDecimal() /
                                    userAccountData.currencyToCryptoCurrencyExchangeRate
                            } else {
                                maxAmount.toBigDecimal() *
                                    userAccountData.getCryptoToDashExchangeRate()
                            }.setScale(8, RoundingMode.HALF_UP).toString()

                        applyNewValue(cleanedValue, viewModel.selectedPickerCurrencyCode)
                    }

                    maxAmountSelected = true
                }
            }
        }

        binding.currencyOptions.setOnOptionPickedListener { value, _ ->
            setAmountValue(value)
            viewModel.selectedPickerCurrencyCode = value
        }
    }

    private fun getMaxAmount(): String? {
        if (viewModel.dashToCrypto.value == true) { // from wallet -> maya
            viewModel.selectedCryptoCurrencyAccount.value?.let { account ->
                val cleanedValue =
                    viewModel.maxForDashWalletAmount.toBigDecimal() /
                        account.getCryptoToDashExchangeRate()
                return cleanedValue.setScale(8, RoundingMode.HALF_UP).toString()
            }
        } else { // coinbase -> wallet
            return viewModel.maxCoinBaseAccountAmount
        }
        return null
    }

    private fun resetViewSelection(it: AccountDataUIModel?) {
        it?.coinbaseAccount?.currency?.let { currencyCode ->
            currencyConversionOptionList = if (viewModel.dashToCrypto.value == true) {
                listOf(Constants.DASH_CURRENCY, viewModel.selectedLocalCurrencyCode, currencyCode)
            } else {
                listOf(currencyCode, viewModel.selectedLocalCurrencyCode, Constants.DASH_CURRENCY)
            }
            binding.currencyOptions.apply {
                pickedOptionIndex = 0
                provideOptions(currencyConversionOptionList)
            }
            viewModel.enteredConvertAmount = "0"
            viewModel.selectedPickerCurrencyCode = binding.currencyOptions.pickedOption
            applyNewValue(viewModel.enteredConvertAmount, binding.currencyOptions.pickedOption)
            binding.currencyOptions.isVisible = true
            binding.maxButtonWrapper.isVisible = true
            binding.inputWrapper.isVisible = true
            if (hasInternet) {
                binding.bottomCard.isVisible = true
            }
        }
    }

    private fun setAmountValue(pickedCurrencyOption: String) {
        val value = viewModel.getAmountValue(pickedCurrencyOption)
        setAmountViewInfo(pickedCurrencyOption, value)
    }

    fun setViewDetails(continueText: String, keyboardHeader: View?) {
        lifecycleScope.launchWhenStarted {
            binding.continueBtn.text = continueText
            keyboardHeader?.let {
                binding.keyboardContainer.addView(keyboardHeader, 0)
            }
        }
    }

    private val keyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

        var value = StringBuilder()

        fun refreshValue() {
            value.clear()
            val inputValue =
                if (viewModel.selectedLocalCurrencyCode == binding.currencyOptions.pickedOption) {
                    val localCurrencySymbol =
                        GenericUtils.getLocalCurrencySymbol(viewModel.selectedLocalCurrencyCode)
                    binding.inputAmount.input.split(" ")
                        .first { it != localCurrencySymbol }
                } else {
                    binding.inputAmount.input.split(" ")
                        .first { it != binding.currencyOptions.pickedOption }
                }
            if (inputValue != "0") {
                value.append(inputValue)
            }
        }

        override fun onNumber(number: Int) {
            refreshValue()
            if (value.toString() == "0") {
                // avoid entering leading zeros without decimal separator
                return
            }

            val isFraction = value.toString().indexOf(decimalSeparator) > -1

            if (isFraction) {
                val lengthOfDecimalPart =
                    value.toString().length - value.toString().indexOf(decimalSeparator)
                val decimalsThreshold =
                    if (viewModel.selectedLocalCurrencyCode == binding.currencyOptions.pickedOption) 2 else 8

                if (lengthOfDecimalPart > decimalsThreshold) {
                    return
                }
            }

            if (!maxAmountSelected) {
                try {
                    appendIfValidAfter(number.toString())

                    applyNewValue(value.toString(), binding.currencyOptions.pickedOption, isEditing = true)
                } catch (x: Exception) {
                    value.deleteCharAt(value.length - 1)
                    applyNewValue(value.toString(), binding.currencyOptions.pickedOption, isEditing = true)
                }
            }
        }

        override fun onBack(longClick: Boolean) {
            refreshValue()
            if (longClick || maxAmountSelected) {
                value.clear()
            } else if (value.isNotEmpty()) {
                value.deleteCharAt(value.length - 1)
                viewModel.resetSwapValueError()
            }
            applyNewValue(value.toString(), binding.currencyOptions.pickedOption, isEditing = true)
            maxAmountSelected = false
        }

        override fun onFunction() {
            if (maxAmountSelected) {
                return
            }
            refreshValue()
            if (value.indexOf(DECIMAL_SEPARATOR) == -1) {
                if (value.isEmpty()) {
                    value.append("0")
                }
                value.append(DECIMAL_SEPARATOR)
            }

            applyNewValue(value.toString(), binding.currencyOptions.pickedOption, isEditing = true)
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
    }

    fun applyNewValue(value: String, currencyCode: String, isEditing: Boolean = false) {
        // Create a new spannable with the two strings
        val newValue = value.ifEmpty { "0" }
        viewModel.setEnteredAmount(newValue)

        setAmountViewInfo(currencyCode, newValue, isEditing)

        val isNonZero = newValue.isNotEmpty() &&
            (newValue.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
        viewModel.updateAmounts()
        checkTheUserEnteredValue(isNonZero)
    }

    private fun setAmountViewInfo(currencyCode: String, value: String, isEditing: Boolean = false) {
        binding.inputAmount.dashToFiat = currencyCode == "DASH"
        val one = BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP)
        var currencyCodeForView = viewModel.selectedLocalCurrencyCode
        var amount = value
        val rate = when (currencyCode) {
            "DASH" -> {
                if (!isEditing && value[value.length - 1] != '.') {
                    amount = amount.toBigDecimal().stripTrailingZeros().toString()
                }
                viewModel.amount.dashFiatExchangeRate
            }
            // Fiat
            viewModel.selectedLocalCurrencyCode -> {
                amount = amount.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toString()

                one / viewModel.account.currencyToDashExchangeRate
                viewModel.amount.dashFiatExchangeRate
            }
            // Crypto
            else -> {
                currencyCodeForView = currencyCode
                amount = amount.toBigDecimal().setScale(8, RoundingMode.HALF_UP).toString()
                if (isEditing && value[value.length - 1] != '.') {
                    amount = amount.toBigDecimal().stripTrailingZeros().toPlainString()
                }
                viewModel.amount.cryptoDashExchangeRate
            }
        }

        binding.inputAmount.exchangeRate = ExchangeRate(Coin.COIN, rate.toFiat(currencyCodeForView))
        binding.inputAmount.input = amount
        viewModel.enteredConvertAmount = amount
    }

    private fun checkTheUserEnteredValue(hasBalance: Boolean) {
        binding.continueBtn.isEnabled = hasBalance
    }

    fun handleNetworkState(hasInternet: Boolean) {
        lifecycleScope.launchWhenStarted {
            this@ConvertViewFragment.hasInternet = hasInternet
            viewModel.selectedCryptoCurrencyAccount.value?.let {
                binding.bottomCard.isVisible = hasInternet
            }
            binding.convertViewNetworkStatusStub.isVisible = !hasInternet
        }
    }
}
