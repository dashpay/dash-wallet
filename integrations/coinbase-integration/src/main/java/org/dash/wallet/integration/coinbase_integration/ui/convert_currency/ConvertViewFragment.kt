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
        private const val ARG_INITIAL_AMOUNT = "initial_amount"
        private const val ARG_DASH_TO_FIAT = "dash_to_fiat"
        private const val DECIMAL_SEPARATOR = '.'
        @JvmStatic
        fun newInstance(
            dashToFiat: Boolean = false,
            initialAmount: Monetary? = null
        ): ConvertViewFragment {
            val args = bundleOf(ARG_DASH_TO_FIAT to dashToFiat)
            initialAmount?.let { args.putSerializable(ARG_INITIAL_AMOUNT, it) }

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        val dashToFiat = args.getBoolean(ARG_DASH_TO_FIAT)
        binding.convertView.dashToCrypto = dashToFiat

        if (args.containsKey(ARG_INITIAL_AMOUNT)) {
            val initialAmount = args.getSerializable(ARG_INITIAL_AMOUNT)
//            binding.amountView.input = when {
//                initialAmount is Coin && dashToFiat -> initialAmount.toPlainString()
//                initialAmount is Fiat && !dashToFiat -> initialAmount.toPlainString()
//                else -> throw IllegalArgumentException("dashToFiat argument and type of initialAmount do not match")
//            }
        }

        binding.keyboardView.onKeyboardActionListener = keyboardActionListener
        binding.continueBtn.setOnClickListener {
            getFaitAmount(viewModel.enteredConvertAmount, binding.currencyOptions.pickedOption)?.let{
                viewModel.onContinueEvent.value = Pair(
                    binding.convertView.dashToCrypto,
                    it
                )
            }

        }

        binding.maxButton.setOnClickListener {
            applyNewValue(viewModel.maxAmount.toPlainString(), binding.currencyOptions.pickedOption)
            maxAmountSelected = true
        }

        binding.convertView.setOnCurrencyChooserClicked {
            viewModel.getUserWalletAccounts()
        }

        viewModel.selectedCryptoCurrencyAccount.observe(viewLifecycleOwner) {
            it?.coinBaseUserAccountData?.balance?.currency?.let { currencyCode ->
                currencyConversionOptionList = listOf(currencyCode, viewModel.selectedLocalCurrencyCode, "DASH")
                binding.currencyOptions.provideOptions(currencyConversionOptionList)
                viewModel.enteredConvertAmount = "0"
                viewModel.selectedPickerCurrencyCode = binding.currencyOptions.pickedOption
                applyNewValue(viewModel.enteredConvertAmount, binding.currencyOptions.pickedOption)
                binding.currencyOptions.isVisible = true
                binding.maxButtonWrapper.isVisible = true
                binding.inputWrapper.isVisible = true
            }
        }

        binding.currencyOptions.pickedOptionIndex = 0

        binding.currencyOptions.setOnOptionPickedListener { value, index ->
            viewModel.selectedPickerCurrencyCode = value
            applyNewValue(viewModel.enteredConvertAmount, binding.currencyOptions.pickedOption)
        }

        viewModel.userAccountsWithBalance.observe(viewLifecycleOwner) {
            val list = if (binding.convertView.dashToCrypto) {
                viewModel.userAccountsInfo.value
            } else {
                it.filter { item -> item.coinBaseUserAccountData.balance?.amount?.toDouble() != 0.0 }
            }

            list?.sortedBy { item -> item.coinBaseUserAccountData.currency?.code }?.let { list ->
                parentFragmentManager.let { fragmentManager ->

                    val cryptoWalletsDialog = CryptoWalletsDialog(list, viewModel.selectedLocalCurrencyCode) { index, dialog ->
                        viewModel.setSelectedCryptoCurrency(list[index])

                        val iconUrl = if (list[index].coinBaseUserAccountData.balance?.currency.isNullOrEmpty().not()) {
                            "https://raw.githubusercontent.com/jsupa/crypto-icons/main/icons/${list[index].coinBaseUserAccountData.balance?.currency?.lowercase()}.png"
                        } else {
                            null
                        }
                        binding.currencyOptions.pickedOptionIndex = 0
                        viewModel.selectedLocalExchangeRate.value?.let { rate ->

                            binding.convertView.input = ServiceWallet(
                                list[index].coinBaseUserAccountData.currency?.name ?: "", getString(R.string.coinbase),
                                list[index].coinBaseUserAccountData.balance?.amount ?: "",
                                list[index].coinBaseUserAccountData.balance?.currency ?: "",
                                list[index].getCoinBaseExchangeRateConversion(rate).first,
                                iconUrl
                            )
                        }
                        dialog.dismiss()
                    }
                    if (!cryptoWalletsDialog.isVisible)
                        cryptoWalletsDialog.show(fragmentManager, "payment_method")
                }
            }
        }

        viewModel.selectedLocalExchangeRate.observe(viewLifecycleOwner) {
            selectedCurrencyCodeExchangeRate = ExchangeRate(Coin.COIN, it.fiat)
            binding.convertView.exchangeRate = selectedCurrencyCodeExchangeRate
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
        binding.youWillReceiveLabel.isVisible = hasBalance
        binding.youWillReceiveValue.isVisible = hasBalance
        if (hasBalance) {

            viewModel.selectedCryptoCurrencyAccount.value?.let {
                selectedCurrencyCodeExchangeRate?.let { rate ->

                    val dashAmount = when {
                        (  it.coinBaseUserAccountData.balance?.currency ==currencyCode &&   it.coinBaseUserAccountData.balance.currency !="DASH")-> {
                            val cleanedValue =
                                it.coinBaseUserAccountData.balance?.amount?.toBigDecimal()!! /
                                    it.exchangeRate.toBigDecimal()
                            val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

                            val fiatAmount = Fiat.parseFiat(rate.fiat.currencyCode, bd.toString())
                            rate.fiatToCoin(fiatAmount)
                        }
                        (  viewModel.selectedLocalCurrencyCode ==currencyCode &&   it.coinBaseUserAccountData.balance?.currency !="DASH")  -> {

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

    fun getFaitAmount(balance:String,currencyCode: String): Fiat? {
        viewModel.selectedCryptoCurrencyAccount.value?.let {
            selectedCurrencyCodeExchangeRate?.let { rate ->
            return    when {
                    (it.coinBaseUserAccountData.balance?.currency == currencyCode && it.coinBaseUserAccountData.balance.currency != "DASH") -> {
                        val cleanedValue =
                            it.coinBaseUserAccountData.balance?.amount?.toBigDecimal()!! /
                                    it.exchangeRate.toBigDecimal()
                        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

                       Fiat.parseFiat(rate.fiat.currencyCode, bd.toString())

                    }
                    (viewModel.selectedLocalCurrencyCode == currencyCode && it.coinBaseUserAccountData.balance?.currency != "DASH") -> {

                         Fiat.parseFiat(rate.fiat.currencyCode, balance)

                    }

                    else -> {
                        val formattedValue = GenericUtils.formatFiatWithoutComma(balance)
                        val dashAmount=try {
                            Coin.parseCoin(formattedValue)
                        } catch (x: Exception) {
                            Coin.ZERO
                        }
                        rate.coinToFiat(dashAmount)
                    }
                }
            }}
        return null
    }
    private var onFilterOptionChosen: ((String) -> Unit)? = null
    fun setOnFilterOptionChosen(listener: (String) -> Unit) {
        onFilterOptionChosen = listener
    }
}
