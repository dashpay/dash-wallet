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

package org.dash.wallet.integration.coinbase_integration.ui.convert_currency

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Constants
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.DASH_CURRENCY
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentConvertCurrencyBinding
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountDataUIModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.ConvertViewViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class ConvertViewFragment : Fragment(R.layout.fragment_convert_currency) {
    companion object {
        private const val ARG_DASH_TO_FIAT = "dash_to_fiat"
        private const val DECIMAL_SEPARATOR = '.'

        @JvmStatic
        fun newInstance(
            dashToCrypto: Boolean = false,
        ): ConvertViewFragment {
            val args = bundleOf(ARG_DASH_TO_FIAT to dashToCrypto)

            return ConvertViewFragment().apply {
                arguments = args
            }
        }
    }

    private val binding by viewBinding(FragmentConvertCurrencyBinding::bind)
    private val viewModel by activityViewModels<ConvertViewViewModel>()
    private val format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode()
    private val decimalSeparator =
        DecimalFormatSymbols.getInstance(GenericUtils.getDeviceLocale()).decimalSeparator
    private var maxAmountSelected: Boolean = false
    var selectedCurrencyCodeExchangeRate: ExchangeRate? = null
    var currencyConversionOptionList: List<String> = emptyList()
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        val dashToCrypto = args.getBoolean(ARG_DASH_TO_FIAT)
        viewModel.setOnSwapDashFromToCryptoClicked(dashToCrypto)

        binding.keyboardView.onKeyboardActionListener = keyboardActionListener
        binding.continueBtn.isEnabled = false
        binding.continueBtn.setOnClickListener {
            getFaitAmount(
                viewModel.enteredConvertAmount,
                binding.currencyOptions.pickedOption
            )?.let {
                viewModel.onContinueEvent.value = Pair(
                    viewModel.dashToCrypto.value ?: false,
                    it
                )
            }
        }


        viewModel.selectedLocalExchangeRate.observe(viewLifecycleOwner) {
            selectedCurrencyCodeExchangeRate = ExchangeRate(Coin.COIN, it.fiat)
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


        binding.bottomCard.isVisible = false

        binding.currencyOptions.pickedOptionIndex = 0

        binding.maxButton.setOnClickListener {
            viewModel.selectedCryptoCurrencyAccount.value?.let { userAccountData ->
                getMaxAmount()?.let { maxAmount ->
                    if (viewModel.selectedPickerCurrencyCode == userAccountData.coinBaseUserAccountData.balance?.currency
                    ) {
                        applyNewValue(maxAmount, viewModel.selectedPickerCurrencyCode)
                    } else {
                        val cleanedValue =
                            if (viewModel.selectedPickerCurrencyCode == viewModel.selectedLocalCurrencyCode) {

                                maxAmount.toBigDecimal() /
                                    userAccountData.currencyToCryptoCurrencyExchangeRate.toBigDecimal()
                            } else {

                                maxAmount.toBigDecimal() *
                                    userAccountData.cryptoCurrencyToDashExchangeRate.toBigDecimal()
                            }.setScale(8, RoundingMode.HALF_UP).toString()

                        applyNewValue(cleanedValue, viewModel.selectedPickerCurrencyCode)
                    }

                    maxAmountSelected = true
                }
            }
        }

        binding.currencyOptions.setOnOptionPickedListener { value, index ->
            setAmountValue(value, viewModel.enteredConvertAmount)
            viewModel.selectedPickerCurrencyCode = value
        }
    }

    private fun getMaxAmount(): String? {

        if (viewModel.dashToCrypto.value == true) {
            viewModel.selectedCryptoCurrencyAccount.value?.let { account ->
                val cleanedValue =
                    viewModel.maxForDashWalletAmount.toBigDecimal() /
                        account.cryptoCurrencyToDashExchangeRate.toBigDecimal()
                return cleanedValue.setScale(8, RoundingMode.HALF_UP).toString()
            }
        } else {
            return viewModel.maxCoinBaseAccountAmount
        }
        return null
    }

    private fun resetViewSelection(it: CoinBaseUserAccountDataUIModel?) {
        it?.coinBaseUserAccountData?.balance?.currency?.let { currencyCode ->
            currencyConversionOptionList = if (viewModel.dashToCrypto.value == true)
                listOf(DASH_CURRENCY, viewModel.selectedLocalCurrencyCode, currencyCode)
            else
                listOf(currencyCode, viewModel.selectedLocalCurrencyCode, DASH_CURRENCY)
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
            binding.bottomCard.isVisible = true
        }
    }

    private fun setAmountValue(pickedCurrencyOption: String, valueToBind: String) {
        viewModel.selectedCryptoCurrencyAccount.value?.let { userAccountData ->


            val cleanedValue =
                if (viewModel.selectedPickerCurrencyCode !== pickedCurrencyOption &&
                    (
                        viewModel.enteredConvertAmount.toBigDecimalOrNull()
                            ?: BigDecimal.ZERO
                        ) > BigDecimal.ZERO
                ) {
                    val convertedValue = when {
                        (userAccountData.coinBaseUserAccountData.balance?.currency == viewModel.selectedPickerCurrencyCode) -> {
                            if (pickedCurrencyOption == viewModel.selectedLocalCurrencyCode) {

                                (
                                    valueToBind.toBigDecimal() /
                                        userAccountData.currencyToCryptoCurrencyExchangeRate.toBigDecimal()
                                    )
                                    .setScale(8, RoundingMode.HALF_UP).toString()
                            } else {

                                val bd = toDashValue(valueToBind, userAccountData, true)
                                val coin = try {
                                    Coin.parseCoin(bd.toString())
                                } catch (x: Exception) {
                                    Coin.ZERO
                                }
                                if (coin.isZero) {
                                    0.toBigDecimal()
                                } else {
                                    bd
                                }
                            }
                        }
                        (viewModel.selectedLocalCurrencyCode == viewModel.selectedPickerCurrencyCode) -> {
                            if (pickedCurrencyOption == userAccountData.coinBaseUserAccountData.balance?.currency) {
                                (
                                    valueToBind.toBigDecimal() *
                                        userAccountData.currencyToCryptoCurrencyExchangeRate.toBigDecimal()
                                    )
                                    .setScale(8, RoundingMode.HALF_UP).toString()
                            } else {
                                val bd = toDashValue(valueToBind, userAccountData)
                                val coin = try {
                                    Coin.parseCoin(bd.toString())
                                } catch (x: Exception) {
                                    Coin.ZERO
                                }
                                if (coin.isZero) {
                                    0.toBigDecimal()
                                } else {
                                    bd
                                }
                            }
                        }

                        else -> {
                            if (pickedCurrencyOption == userAccountData.coinBaseUserAccountData.balance?.currency) {
                                (
                                    valueToBind.toBigDecimal() /
                                        userAccountData.cryptoCurrencyToDashExchangeRate.toBigDecimal()
                                    )
                                    .setScale(8, RoundingMode.HALF_UP).toString()
                            } else {

                                (
                                    valueToBind.toBigDecimal() /
                                        userAccountData.currencyToDashExchangeRate.toBigDecimal()
                                    )
                                    .setScale(8, RoundingMode.HALF_UP).toString()
                            }
                        }
                    }
                    convertedValue.toString()
                } else {
                    valueToBind
                }

            applyNewValue(cleanedValue, pickedCurrencyOption)
        }
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
                    binding.inputAmount.text.split(" ")
                        .first { it != localCurrencySymbol }
                } else {
                    binding.inputAmount.text.split(" ")
                        .first { it != binding.currencyOptions.pickedOption }
                }
            if (inputValue != "0")
                value.append(inputValue)
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

                    applyNewValue(value.toString(), binding.currencyOptions.pickedOption)
                } catch (x: Exception) {
                    value.deleteCharAt(value.length - 1)
                    applyNewValue(value.toString(), binding.currencyOptions.pickedOption)
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
            applyNewValue(value.toString(), binding.currencyOptions.pickedOption)
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

            applyNewValue(value.toString(), binding.currencyOptions.pickedOption)
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

    fun applyNewValue(value: String, currencyCode: String) {
        // Create a new spannable with the two strings
        val balance = value.ifEmpty { "0" }
        val isFraction = balance.indexOf(decimalSeparator) > -1
        val lengthOfDecimalPart = balance.length - balance.indexOf(decimalSeparator)
        val spannableString = if (viewModel.selectedLocalCurrencyCode == currencyCode) {
            val cleanedValue = GenericUtils.formatFiatWithoutComma(balance)
            val fiatAmount = Fiat.parseFiat(viewModel.selectedLocalCurrencyCode, cleanedValue)
            val localCurrencySymbol =
                GenericUtils.getLocalCurrencySymbol(viewModel.selectedLocalCurrencyCode)

            val faitBalance = if (isFraction && lengthOfDecimalPart > 2) {
                format.format(fiatAmount).toString()
            } else {
                balance
            }
            val text = if (GenericUtils.isCurrencyFirst(fiatAmount)) {
                "$localCurrencySymbol $faitBalance"
            } else {
                "$faitBalance $localCurrencySymbol"
            }
            SpannableString(text).apply {
                if (GenericUtils.isCurrencyFirst(fiatAmount) && text.length - faitBalance.length > 0) {
                    setAmountFormat(this, 0, text.length - faitBalance.length)
                } else {
                    setAmountFormat(this, balance.length, text.length)
                }
            }
        } else {

            val formattedValue = if (balance.contains("E")) {
                DecimalFormat("########.########").format(balance.toDouble())
            } else {
                balance
            }

            val text = "$formattedValue $currencyCode"

            SpannableString(text).apply {
                setAmountFormat(this, formattedValue.length, text.length)
            }
        }

        binding.inputAmount.text = spannableString
        viewModel.enteredConvertAmount = balance

        val hasBalance = balance.isNotEmpty() &&
            (balance.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO


        if (hasBalance) {

            viewModel.selectedCryptoCurrencyAccount.value?.let {
                selectedCurrencyCodeExchangeRate?.let { rate ->

                    val dashAmount = when {
                        (it.coinBaseUserAccountData.balance?.currency == currencyCode && it.coinBaseUserAccountData.balance.currency != DASH_CURRENCY) -> {
                            val bd =
                                toDashValue(balance, it, true)
                            try {
                                Coin.parseCoin(bd.toString())
                            } catch (x: Exception) {
                                Coin.ZERO
                            }
                        }
                        (viewModel.selectedLocalCurrencyCode == currencyCode && it.coinBaseUserAccountData.balance?.currency != DASH_CURRENCY) -> {

                            val bd =
                                toDashValue(balance, it)
                            try {
                                Coin.parseCoin(bd.toString())
                            } catch (x: Exception) {
                                Coin.ZERO
                            }
                        }

                        else -> {
                            val formattedValue = GenericUtils.formatFiatWithoutComma(balance)
                            try {
                                Coin.parseCoin(formattedValue)
                            } catch (x: Exception) {
                                Coin.ZERO
                            }
                        }
                    }

                    viewModel.setEnteredConvertDashAmount(dashAmount)
                }
            }
        } else {
            viewModel.setEnteredConvertDashAmount(Coin.ZERO)
        }

        checkTheUserEnteredValue(hasBalance)
    }



    private fun setAmountFormat(
        spannable: Spannable,
        from: Int,
        to: Int
    ): Spannable {
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

    private fun getFaitAmount(balance: String, currencyCode: String): Pair<Fiat?, Coin?>? {
        viewModel.selectedCryptoCurrencyAccount.value?.let {
            selectedCurrencyCodeExchangeRate?.let { rate ->
                val fiatAmount = when {
                    (it.coinBaseUserAccountData.balance?.currency == currencyCode && it.coinBaseUserAccountData.balance.currency != DASH_CURRENCY) -> {
                        val cleanedValue =
                            balance.toBigDecimal() /
                                it.currencyToCryptoCurrencyExchangeRate.toBigDecimal()
                        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

                        Fiat.parseFiat(rate.fiat.currencyCode, bd.toString())
                    }
                    (viewModel.selectedLocalCurrencyCode == currencyCode && it.coinBaseUserAccountData.balance?.currency != DASH_CURRENCY) -> {

                        Fiat.parseFiat(rate.fiat.currencyCode, balance)
                    }

                    else -> {
                        val cleanedValue =
                            balance.toBigDecimal() /
                                it.currencyToDashExchangeRate.toBigDecimal()
                        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

                        Fiat.parseFiat(rate.fiat.currencyCode, bd.toString())
                    }
                }

                val bd =
                    toDashValue(balance, it)
                val coin = try {
                    Coin.parseCoin(bd.toString())
                } catch (x: Exception) {
                    Coin.ZERO
                }
                return Pair(fiatAmount, coin)
            }
        }
        return null
    }

    private fun toDashValue(
        valueToBind: String,
        userAccountData: CoinBaseUserAccountDataUIModel,
        fromCrypto: Boolean = false
    ): BigDecimal {
        val convertedValue = if (fromCrypto) {
            valueToBind.toBigDecimal() *
                userAccountData.cryptoCurrencyToDashExchangeRate.toBigDecimal()
        } else {
            valueToBind.toBigDecimal() *
                userAccountData.currencyToDashExchangeRate.toBigDecimal()
        }.setScale(8, RoundingMode.HALF_UP)
        return convertedValue
    }

    private fun checkTheUserEnteredValue(hasBalance: Boolean) {
        binding.continueBtn.isEnabled = hasBalance
    }
}