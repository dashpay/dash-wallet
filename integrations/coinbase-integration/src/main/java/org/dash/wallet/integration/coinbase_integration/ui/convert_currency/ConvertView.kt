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

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.ConvertViewBinding
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.ServiceWallet
import java.math.RoundingMode

class ConvertView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {
    private val binding = ConvertViewBinding.inflate(LayoutInflater.from(context), this)
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()
    private val fiatFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(2).optionalDecimals()

    private var onCurrencyChooserClicked: (() -> Unit)? = null
    private var onSwapClicked: (() -> Unit)? = null

    private var currencySymbol = "$"
    private var isCurrencySymbolFirst = true

    private var _input: ServiceWallet? = null
    var input: ServiceWallet?
        get() = _input
        set(value) {
            _input = value
            updateAmount()
        }

    var exchangeRate: ExchangeRate? = null
        set(value) {
            field = value
//            updateCurrency()
//            updateAmount()
//            updateDashSymbols()
        }

    var dashToCrypto: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateUiWithSwap()
                // updateDashSymbols()

//                if (value) {
//                    input = dashFormat.minDecimals(0)
//                        .optionalDecimals(0,6).format(dashAmount).toString()
//                } else {
//                    binding.resultAmount.text = dashFormat.format(dashAmount)
//
//                    exchangeRate?.let {
//                        fiatAmount = it.coinToFiat(dashAmount)
//                        _input = fiatFormat.minDecimals(0)
//                            .optionalDecimals(0,2).format(fiatAmount).toString()
//                        binding.inputAmount.text = formatInputWithCurrency()
//                    }
//                }
            }
        }

    init {

//        binding.convertFromDashTitle.isVisible =input== null
//        binding.fromDataGroup.isVisible = input!= null
        binding.convertFromDashBalance.isVisible = input != null
        updateUiWithSwap()
        //  updateCurrency()
        binding.swapBtn.setOnClickListener {
            dashToCrypto = !dashToCrypto
            updateUiWithSwap()
            onSwapClicked?.invoke()
        }
        binding.convertFromBtn.convertItemClickListener = object :
            CryptoConvertItem.ConvertItemClickListener {
            override fun onConvertItemClickListener() {
                if (!dashToCrypto) {
                    onCurrencyChooserClicked?.invoke()
                }
            }
        }
        binding.convertToBtn.convertItemClickListener = object :
            CryptoConvertItem.ConvertItemClickListener {
            override fun onConvertItemClickListener() {
                if (dashToCrypto) {
                    onCurrencyChooserClicked?.invoke()
                }
            }
        }

        // binding.selectTheCoinTitle.isVisible = input == null
    }

    private fun updateUiWithSwap() {
        setConvertFromBtnData()
        setConvertToBtnData()
    }

    private fun setConvertFromBtnData() {
        binding.convertFromBtn.setCryptoItemArrowVisibility(!dashToCrypto)
        if (dashToCrypto) {
            binding.convertFromBtn.setCryptoItemGroupVisibility(true)
            binding.convertFromBtn.setConvertItemServiceName(R.string.dash_wallet_name)
            binding.convertFromBtn.setConvertItemTitle(R.string.dash)
            ContextCompat.getDrawable(context, R.drawable.ic_dash_blue_filled)
                ?.let { binding.convertFromBtn.setConvertItemIcon(it) }
        } else {
            setFromBtnData()
        }
    }

    private fun setConvertToBtnData() {
        binding.convertToBtn.setCryptoItemArrowVisibility(dashToCrypto)
        if (!dashToCrypto) {
            binding.convertToBtn.setCryptoItemGroupVisibility(true)
            binding.convertToBtn.setConvertItemServiceName(R.string.dash_wallet_name)
            binding.convertToBtn.setConvertItemTitle(R.string.dash)
            ContextCompat.getDrawable(context, R.drawable.ic_dash_blue_filled)
                ?.let { binding.convertToBtn.setConvertItemIcon(it) }
        } else {
            setToBtnData()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setFromBtnData() {
        binding.convertFromBtn.setCryptoItemGroupVisibility(input != null)
        binding.convertFromDashBalance.isVisible = (input != null)
        binding.convertFromDashFiatAmount.isVisible = (input != null)
        input?.let {
            binding.convertFromBtn.setConvertItemServiceName(it.cryptoWalletService)
            binding.convertFromBtn.setConvertItemTitle(it.cryptoWalletName)
            binding.convertFromBtn.setConvertItemIcon(it.icon)

            exchangeRate?.let { currentExchangeRate ->

                val balance = it.balance.toBigDecimal().setScale(8, RoundingMode.HALF_UP).toString()
                val coin = try {
                    Coin.parseCoin(balance)
                } catch (x: Exception) {
                    Coin.ZERO
                }

                binding.convertFromDashBalance.text = "${context.getString(R.string.balance)} ${dashFormat.minDecimals(0)
                    .optionalDecimals(0,8).format(coin)} ${input?.currency}"

                binding.convertFromDashFiatAmount.text = "${Constants.PREFIX_ALMOST_EQUAL_TO} ${input?.faitAmount}"
            }
        }
    }

    private fun setToBtnData() {
        binding.convertToBtn.setCryptoItemGroupVisibility(input != null)
        input?.let {
            binding.convertToBtn.setConvertItemServiceName(it.cryptoWalletService)
            binding.convertToBtn.setConvertItemTitle(it.cryptoWalletName)
            binding.convertToBtn.setConvertItemIcon(it.icon)
        }
    }

    fun setOnCurrencyChooserClicked(listener: () -> Unit) {
        onCurrencyChooserClicked = listener
    }

    fun setOnSwapClicked(listener: () -> Unit) {
        onSwapClicked = listener
    }

//    private fun updateCurrency() {
//        exchangeRate?.let { rate ->
//            val currencyFormat = (NumberFormat.getCurrencyInstance() as DecimalFormat).apply {
//                currency = Currency.getInstance(rate.fiat.currencyCode)
//            }
//            this.currencySymbol = currencyFormat.decimalFormatSymbols.currencySymbol
//            this.isCurrencySymbolFirst = currencyFormat.format(1.0).startsWith(currencySymbol)
//        }
//    }

    private fun updateAmount() {

        if (dashToCrypto) {
            setToBtnData()
        } else {
            setFromBtnData()
        }

//        val rate = exchangeRate
//
//        if (rate != null) {
//            val cleanedValue = GenericUtils.formatFiatWithoutComma(input)
//
//            if (dashToFiat) {
//                dashAmount = Coin.parseCoin(cleanedValue)
//                fiatAmount = rate.coinToFiat(dashAmount)
//            } else {
//                fiatAmount = Fiat.parseFiat(rate.fiat.currencyCode, cleanedValue)
//                dashAmount = rate.fiatToCoin(fiatAmount)
//            }
//
//            binding.resultAmount.text = if (dashToFiat) {
//                GenericUtils.fiatToString(fiatAmount)
//            } else {
//                dashFormat.format(dashAmount)
//            }
//        } else {
//            binding.resultAmount.text = "0"
//            Log.e(ConvertView::class.java.name, "Exchange rate is not initialized")
//        }
    }

//    private fun updateDashSymbols() {
//        binding.inputCurrencyToggle.isVisible = !dashToFiat && showCurrencySelector
//        binding.resultCurrencyToggle.isVisible = dashToFiat && showCurrencySelector
//
//        if (dashToFiat) {
//            binding.inputSymbolDash.isVisible = isCurrencySymbolFirst
//            binding.inputSymbolDashPostfix.isVisible = !isCurrencySymbolFirst
//
//            binding.resultSymbolDash.isVisible = false
//            binding.resultSymbolDashPostfix.isVisible = false
//        } else {
//            binding.resultSymbolDash.isVisible = isCurrencySymbolFirst
//            binding.resultSymbolDashPostfix.isVisible = !isCurrencySymbolFirst
//
//            binding.inputSymbolDash.isVisible = false
//            binding.inputSymbolDashPostfix.isVisible = false
//        }
//    }

//    private fun formatInputWithCurrency(): String {
//        return when {
//            dashToFiat -> input
//            isCurrencySymbolFirst -> "$currencySymbol $input"
//            else -> "$input $currencySymbol"
//        }
//    }

//    var showCurrencySelector: Boolean = true
//        set(value) {
//            field = value
//            binding.inputCurrencyToggle.isVisible = value
//            binding.resultCurrencyToggle.isVisible = value
//        }
}
