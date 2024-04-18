/*
 * Copyright 2024 Dash Core Group.
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
import org.dash.wallet.integrations.maya.model.CurrencyInputType
import org.dash.wallet.integrations.maya.ui.mayaViewModels
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols

@AndroidEntryPoint
class ConvertViewFragment : Fragment(R.layout.fragment_convert_currency_view) {
    companion object {
        val log = LoggerFactory.getLogger(ConvertViewFragment::class.java)
        @JvmStatic
        fun newInstance(): ConvertViewFragment {
            return ConvertViewFragment()
        }
    }

    private val binding by viewBinding(FragmentConvertCurrencyViewBinding::bind)
    private val viewModel by mayaViewModels<ConvertViewViewModel>()
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
                viewModel.getMaxAmount()?.let { maxAmount ->
                    val cryptoCurrency = userAccountData.coinbaseAccount.currency

                    if (viewModel.selectedPickerCurrencyCode == cryptoCurrency) {
                        applyNewValue(
                            maxAmount.crypto.toString(),
                            viewModel.selectedPickerCurrencyCode,
                            isLocalized = false
                        )
                    } else {
                        val cleanedValue =
                            if (viewModel.selectedPickerCurrencyCode == viewModel.selectedLocalCurrencyCode) {
                                maxAmount.fiat
                            } else {
                                maxAmount.dash
                            }.toString()

                        applyNewValue(cleanedValue, viewModel.selectedPickerCurrencyCode, isLocalized = false)
                    }

                    maxAmountSelected = true
                }
            }
        }

        binding.currencyOptions.setOnOptionPickedListener { value, _ ->
            setAmountValue(value)
            viewModel.selectedPickerCurrencyCode = value
        }

        initAmount()
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
            viewModel.enteredConvertAmount = GenericUtils.toLocalizedString(
                viewModel.amount.anchoredValue,
                viewModel.amount.anchoredType != CurrencyInputType.Fiat,
                viewModel.amount.anchoredCurrencyCode
            )
            viewModel.selectedPickerCurrencyCode = binding.currencyOptions.pickedOption
            applyNewValue(viewModel.enteredConvertAmount, binding.currencyOptions.pickedOption, isLocalized = true)
            binding.currencyOptions.isVisible = true
            binding.maxButtonWrapper.isVisible = true
            binding.inputWrapper.isVisible = true
            if (hasInternet) {
                binding.bottomCard.isVisible = true
            }
        }
    }

    private fun initAmount() {
        setAmountValue(viewModel.selectedPickerCurrencyCode)
    }

    private fun setAmountValue(pickedCurrencyOption: String) {
        val value = viewModel.getAmountValue(pickedCurrencyOption)
        viewModel.amount.setAnchoredType(pickedCurrencyOption)
        setAmountViewInfo(pickedCurrencyOption, value, isLocalized = false)
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
                    if (viewModel.selectedLocalCurrencyCode == binding.currencyOptions.pickedOption) {
                        GenericUtils.getCurrencyDigits()
                    } else {
                        8
                    }

                if (lengthOfDecimalPart > decimalsThreshold) {
                    return
                }
            }

            if (!maxAmountSelected) {
                try {
                    appendIfValidAfter(number.toString())

                    applyNewValue(
                        value.toString(),
                        binding.currencyOptions.pickedOption,
                        isLocalized = true,
                        isEditing = true
                    )
                } catch (x: Exception) {
                    value.deleteCharAt(value.length - 1)
                    applyNewValue(
                        value.toString(),
                        binding.currencyOptions.pickedOption,
                        isLocalized = true,
                        isEditing = true
                    )
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
            applyNewValue(
                value.toString(),
                binding.currencyOptions.pickedOption,
                isLocalized = true,
                isEditing = true
            )
            maxAmountSelected = false
        }

        override fun onFunction() {
            if (maxAmountSelected) {
                return
            }
            refreshValue()
            if (value.indexOf(decimalSeparator) == -1) {
                if (value.isEmpty()) {
                    value.append("0")
                }
                value.append(decimalSeparator)
            }

            applyNewValue(value.toString(), binding.currencyOptions.pickedOption, isLocalized = true, isEditing = true)
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

    fun applyNewValue(value: String, currencyCode: String, isLocalized: Boolean, isEditing: Boolean = false) {
        // Create a new spannable with the two strings
        val newValue = value.ifEmpty { "0" }
        viewModel.setEnteredAmount(newValue, isLocalized)

        setAmountViewInfo(currencyCode, newValue, isLocalized, isEditing)

        val isNonZero = newValue.isNotEmpty() &&
            (newValue.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
        viewModel.updateAmounts()
        checkTheUserEnteredValue(isNonZero)
    }

    private fun setAmountViewInfo(
        currencyCode: String,
        value: String,
        isLocalized: Boolean,
        isEditing: Boolean = false
    ) {
        binding.inputAmount.dashToFiat = currencyCode == "DASH"
        val one = BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP)
        var currencyCodeForView = viewModel.selectedLocalCurrencyCode
        var amount = value
        val amountBG = GenericUtils.toScaledBigDecimal(value, isLocalized)
        val rate = when (currencyCode) {
            "DASH" -> {
                if (!isEditing) {
                    if (amountBG != BigDecimal.ZERO.setScale(amountBG.scale())) {
                        amount = viewModel.cryptoFormat.format(amountBG)
                    } else {
                        amount = viewModel.cryptoFormat.format(BigDecimal.ZERO)
                    }
                }
                viewModel.amount.dashFiatExchangeRate
            }
            // Fiat
            viewModel.selectedLocalCurrencyCode -> {
                if (!isEditing) {
                    val digits = GenericUtils.getCurrencyDigits()
                    amount = viewModel.fiatFormat.format(amount.toBigDecimal().setScale(digits, RoundingMode.HALF_UP))
                }
                one / viewModel.account.currencyToDashExchangeRate
                viewModel.amount.dashFiatExchangeRate
            }
            // Crypto
            else -> {
                currencyCodeForView = currencyCode
                if (!isEditing) {
                    amount = if (amountBG != BigDecimal.ZERO.setScale(amountBG.scale())) {
                        viewModel.cryptoFormat.format(amountBG)
                    } else {
                        viewModel.cryptoFormat.format(BigDecimal.ZERO)
                    }
                }
                viewModel.amount.cryptoDashExchangeRate
            }
        }

        binding.inputAmount.input = amount
        binding.inputAmount.currencyDigits = if (currencyCode == viewModel.selectedLocalCurrencyCode) {
            GenericUtils.getCurrencyDigits()
        } else {
            8
        }
        binding.inputAmount.exchangeRate = ExchangeRate(Coin.COIN, rate.toFiat(currencyCodeForView.substring(0, 3)))
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
