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
import org.bitcoinj.core.Monetary
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.livedata.EventObserver
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentConvertCurrencyBinding
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountDataUIModel
import org.dash.wallet.integration.coinbase_integration.model.getCoinBaseExchangeRateConversion
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.ServiceWallet
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.crypto_wallets.CryptoWalletsDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.ConvertViewViewModel
import java.math.RoundingMode

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class ConvertViewFragment : Fragment(R.layout.fragment_convert_currency) {
    companion object {
        private const val ARG_DASH_TO_FIAT = "dash_to_fiat"
        private const val DECIMAL_SEPARATOR = '.'
        @JvmStatic
        fun newInstance(
            dashToCrypto: Boolean = false,
            initialAmount: Monetary? = null
        ): ConvertViewFragment {
            val args = bundleOf(ARG_DASH_TO_FIAT to dashToCrypto)

            return ConvertViewFragment().apply {
                arguments = args
            }
        }
    }

    private val binding by viewBinding(FragmentConvertCurrencyBinding::bind)
    private val viewModel: ConvertViewViewModel by activityViewModels()
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()
    private var maxAmountSelected: Boolean = false
    var selectedCurrencyCodeExchangeRate: ExchangeRate? = null
    var currencyConversionOptionList: List<String> = emptyList()
    private var cryptoWalletsDialog: CryptoWalletsDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        val dashToCrypto = args.getBoolean(ARG_DASH_TO_FIAT)
       // binding.convertView.dashToCrypto = dashToCrypto

        binding.keyboardView.onKeyboardActionListener = keyboardActionListener
        binding.continueBtn.isEnabled = false
        binding.continueBtn.setOnClickListener {
            getFaitAmount(viewModel.enteredConvertAmount, binding.currencyOptions.pickedOption)?.let {
                viewModel.onContinueEvent.value = Pair(
                    binding.convertView.dashToCrypto,
                    it
                )
            }
        }

        binding.convertView.setOnCurrencyChooserClicked {
            viewModel.getUserWalletAccounts(binding.convertView.dashToCrypto)
        }

        viewModel.selectedCryptoCurrencyAccount.observe(viewLifecycleOwner) {
            it?.coinBaseUserAccountData?.balance?.currency?.let { currencyCode ->
                currencyConversionOptionList = listOf(currencyCode, viewModel.selectedLocalCurrencyCode, "DASH")
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

        binding.bottomCard.isVisible = false

        binding.currencyOptions.pickedOptionIndex = 0
        binding.maxButton.setOnClickListener {
            viewModel.selectedCryptoCurrencyAccount.value?.let { userAccountData ->
                if (viewModel.selectedPickerCurrencyCode == userAccountData?.coinBaseUserAccountData?.balance?.currency &&
                    viewModel.enteredConvertAmount != "0"
                ) {
                    applyNewValue(viewModel.maxAmount.toPlainString(), viewModel.selectedPickerCurrencyCode)
                } else {
                    val cleanedValue = if (viewModel.selectedPickerCurrencyCode == viewModel.selectedLocalCurrencyCode) {

                        viewModel.maxAmount.toPlainString().toBigDecimal() /
                            userAccountData.currencyToCryptoCurrencyExchangeRate.toBigDecimal()
                    } else {

                        viewModel.maxAmount.toPlainString().toBigDecimal() *
                            userAccountData.cryptoCurrencyToDashExchangeRate.toBigDecimal()
                    }.setScale(8, RoundingMode.HALF_UP).toString()

                    applyNewValue(cleanedValue, viewModel.selectedPickerCurrencyCode)
                }

                maxAmountSelected = true
            }
        }

        binding.currencyOptions.setOnOptionPickedListener { value, index ->

            setAmountValue(value, viewModel.enteredConvertAmount)
            viewModel.selectedPickerCurrencyCode = value
        }

        viewModel.userAccountsWithBalance.observe(
            viewLifecycleOwner,
            EventObserver {
                it?.sortedBy { item -> item.coinBaseUserAccountData.currency?.code }?.let { list ->
                    parentFragmentManager.let { fragmentManager ->

                        cryptoWalletsDialog = CryptoWalletsDialog(
                            list,
                            viewModel.selectedLocalCurrencyCode
                        ) { index, dialog ->
                            viewModel.setSelectedCryptoCurrency(list[index])

                            val iconUrl =
                                if (list[index].coinBaseUserAccountData.balance?.currency.isNullOrEmpty()
                                    .not()
                                ) {
                                    GenericUtils.getCoinIcon(list[index].coinBaseUserAccountData.balance?.currency?.lowercase())
                                } else {
                                    null
                                }
                            viewModel.selectedLocalExchangeRate.value?.let { rate ->

                                binding.convertView.input = ServiceWallet(
                                    list[index].coinBaseUserAccountData.currency?.name ?: "",
                                    getString(R.string.coinbase),
                                    list[index].coinBaseUserAccountData.balance?.amount ?: "",
                                    list[index].coinBaseUserAccountData.balance?.currency ?: "",
                                    list[index].getCoinBaseExchangeRateConversion(rate).first,
                                    iconUrl
                                )
                            }
                            dialog.dismiss()
                        }
                        if (this.cryptoWalletsDialog?.isVisible == false) {
                            cryptoWalletsDialog?.show(fragmentManager, "payment_method")
                        }
                    }
                }
            }
        )


        viewModel.selectedLocalExchangeRate.observe(viewLifecycleOwner) {
            selectedCurrencyCodeExchangeRate = ExchangeRate(Coin.COIN, it.fiat)
            binding.convertView.exchangeRate = selectedCurrencyCodeExchangeRate
        }
    }

    private fun setAmountValue(pickedCurrencyOption: String, valueToBind: String) {
        val userAccountData = viewModel.selectedCryptoCurrencyAccount.value

        val cleanedValue =
            if (viewModel.selectedPickerCurrencyCode !== pickedCurrencyOption && viewModel.enteredConvertAmount != "0") {
                when {
                    (userAccountData?.coinBaseUserAccountData?.balance?.currency == viewModel.selectedPickerCurrencyCode) -> {
                        if (pickedCurrencyOption == viewModel.selectedLocalCurrencyCode) {

                            valueToBind.toBigDecimal() /
                                userAccountData.currencyToCryptoCurrencyExchangeRate.toBigDecimal()
                        } else {

                            valueToBind.toBigDecimal() *
                                userAccountData.cryptoCurrencyToDashExchangeRate.toBigDecimal()
                        }
                    }
                    (viewModel.selectedLocalCurrencyCode == viewModel.selectedPickerCurrencyCode) -> {
                        if (pickedCurrencyOption == userAccountData?.coinBaseUserAccountData?.balance?.currency) {
                            valueToBind.toBigDecimal() *
                                userAccountData.currencyToCryptoCurrencyExchangeRate.toBigDecimal()
                        } else {
                            valueToBind.toBigDecimal() *
                                userAccountData?.currencyToDashExchangeRate?.toBigDecimal()!!
                        }
                    }

                    else -> {
                        if (pickedCurrencyOption == userAccountData?.coinBaseUserAccountData?.balance?.currency) {
                            valueToBind.toBigDecimal() /
                                userAccountData.cryptoCurrencyToDashExchangeRate.toBigDecimal()
                        } else {

                            valueToBind.toBigDecimal() /
                                userAccountData?.currencyToDashExchangeRate?.toBigDecimal()!!
                        }
                    }
                }.setScale(8, RoundingMode.HALF_UP).toString()
            } else {
                valueToBind
            }

        applyNewValue(cleanedValue, pickedCurrencyOption)
    }

    fun setViewDetails(continueText: String, keyboardHeader: View?) {
        lifecycleScope.launchWhenStarted {
            binding.continueBtn.text = continueText
            keyboardHeader?.let {
                binding.keyboardContainer.addView(keyboardHeader, 0)
            }
        }
    }

    fun setUserAccountsInfo(userAccountsList: List<CoinBaseUserAccountDataUIModel>?) {
        viewModel.setUserAccountsList(userAccountsList)
    }

    private val keyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

        var value = StringBuilder()

        fun refreshValue() {
            value.clear()
            val inputValue = binding.inputAmount.text.split(" ")
                .first { it != binding.currencyOptions.pickedOption }
            if (inputValue != "0")
                value.append(inputValue)
        }

        override fun onNumber(number: Int) {
            refreshValue()
            if (value.toString() == "0") {
                // avoid entering leading zeros without decimal separator
                return
            }
            val formattedValue = GenericUtils.formatFiatWithoutComma(value.toString())
            val isFraction = formattedValue.indexOf(DECIMAL_SEPARATOR) > -1
//            if (isFraction) {
//                val lengthOfDecimalPart = formattedValue.length - formattedValue.indexOf(DECIMAL_SEPARATOR)
//                val decimalsThreshold = if (viewModel.dashToFiatDirectionValue) 8 else 2
//                if (lengthOfDecimalPart > decimalsThreshold) {
//                    return
//                }
//            }
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
        val text = "$balance $currencyCode"

        val spannable: Spannable = SpannableString(text)
        val textSize = 21.0f / binding.inputAmount.paint.textSize

        spannable.setSpan(
            RelativeSizeSpan(textSize), balance.length,
            text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannable.setSpan(
            context?.resources?.getColor(R.color.gray_900)?.let { ForegroundColorSpan(it) }, balance.length,
            text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.inputAmount.text = spannable
        viewModel.enteredConvertAmount = balance

        val hasBalance = balance.isNotEmpty() && balance != "0"
        binding.continueBtn.isEnabled = hasBalance
        binding.youWillReceiveLabel.isVisible = hasBalance
        binding.youWillReceiveValue.isVisible = hasBalance
        if (hasBalance) {

            viewModel.selectedCryptoCurrencyAccount.value?.let {
                selectedCurrencyCodeExchangeRate?.let { rate ->

                    val dashAmount = when {
                        (it.coinBaseUserAccountData.balance?.currency == currencyCode && it.coinBaseUserAccountData.balance.currency != "DASH") -> {
                            val cleanedValue =
                                it.coinBaseUserAccountData.balance?.amount?.toBigDecimal()!! /
                                    it.currencyToCryptoCurrencyExchangeRate.toBigDecimal()
                            val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

                            val fiatAmount = Fiat.parseFiat(rate.fiat.currencyCode, bd.toString())
                            rate.fiatToCoin(fiatAmount)
                        }
                        (viewModel.selectedLocalCurrencyCode == currencyCode && it.coinBaseUserAccountData.balance?.currency != "DASH") -> {

                            val fiatAmount = Fiat.parseFiat(rate.fiat.currencyCode, balance)
                            rate.fiatToCoin(fiatAmount)
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
                    binding.youWillReceiveValue.text = context?.getString(R.string.you_will_receive_dash, dashFormat.format(dashAmount))
                }
            }
        }
    }

    private fun getFaitAmount(balance: String, currencyCode: String): Fiat? {
        viewModel.selectedCryptoCurrencyAccount.value?.let {
            selectedCurrencyCodeExchangeRate?.let { rate ->
                return when {
                    (it.coinBaseUserAccountData.balance?.currency == currencyCode && it.coinBaseUserAccountData.balance.currency != "DASH") -> {
                        val cleanedValue =
                            it.coinBaseUserAccountData.balance?.amount?.toBigDecimal()!! /
                                it.currencyToCryptoCurrencyExchangeRate.toBigDecimal()
                        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

                        Fiat.parseFiat(rate.fiat.currencyCode, bd.toString())
                    }
                    (viewModel.selectedLocalCurrencyCode == currencyCode && it.coinBaseUserAccountData.balance?.currency != "DASH") -> {

                        Fiat.parseFiat(rate.fiat.currencyCode, balance)
                    }

                    else -> {
                        val formattedValue = GenericUtils.formatFiatWithoutComma(balance)
                        val dashAmount = try {
                            Coin.parseCoin(formattedValue)
                        } catch (x: Exception) {
                            Coin.ZERO
                        }
                        rate.coinToFiat(dashAmount)
                    }
                }
            }
        }
        return null
    }
    private var onFilterOptionChosen: ((String) -> Unit)? = null
    fun setOnFilterOptionChosen(listener: (String) -> Unit) {
        onFilterOptionChosen = listener
    }
}
