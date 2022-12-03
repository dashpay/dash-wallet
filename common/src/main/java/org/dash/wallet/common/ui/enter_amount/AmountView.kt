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

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.AmountViewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*

class AmountView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {
    private val binding = AmountViewBinding.inflate(LayoutInflater.from(context), this)
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()
    private val fiatFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(2).optionalDecimals()

    private var onCurrencyToggleClicked: (() -> Unit)? = null
    private var onDashToFiatChanged: ((Boolean) -> Unit)? = null
    private var onAmountChanged: ((Coin) -> Unit)? = null

    private var currencySymbol = "$"
    private var isCurrencySymbolFirst = true

    private var _input = "0"
    var input: String
        get() = _input
        set(value) {
            _input = value.ifEmpty { "0" }
            updateAmount()
        }

    var fiatAmount: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)
        private set

    var dashAmount: Coin = Coin.ZERO
        private set(value) {
            field = value
            onAmountChanged?.invoke(value)
        }

    var exchangeRate: ExchangeRate? = null
        set(value) {
            field = value
            updateCurrency()
            updateAmount()
            updateDashSymbols()
        }

    var dashToFiat: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateDashSymbols()
                onDashToFiatChanged?.invoke(value)

                if (value) {
                    input = dashFormat.minDecimals(0)
                        .optionalDecimals(0,6).format(dashAmount).toString()
                } else {
                    binding.resultAmount.text = dashFormat.format(dashAmount)

                    exchangeRate?.let {
                        fiatAmount = it.coinToFiat(dashAmount)
                        _input = fiatFormat.minDecimals(0)
                            .optionalDecimals(0,2).format(fiatAmount).toString()
                        binding.inputAmount.text = formatInputWithCurrency()
                    }
                }
            }
        }

    var showCurrencySelector: Boolean = true
        set(value) {
            field = value
            binding.inputCurrencyToggle.isVisible = !dashToFiat && value
            binding.inputContainer.isClickable = !dashToFiat && value
            binding.resultCurrencyToggle.isVisible = dashToFiat && value
            binding.resultContainer.isClickable = dashToFiat && value
        }

    init {
        val padding = resources.getDimensionPixelOffset(R.dimen.default_horizontal_padding)
        updatePadding(left = padding, right = padding)
        updateCurrency()

        binding.inputContainer.setOnClickListener {
            if (showCurrencySelector && !dashToFiat) {
                onCurrencyToggleClicked?.invoke()
            }
        }
        binding.resultContainer.setOnClickListener {
            if (showCurrencySelector && dashToFiat) {
                onCurrencyToggleClicked?.invoke()
            }
        }

//        binding.inputAmount.doAfterTextChanged {
//            Log.i("REFACTORING", "text size: ${binding.inputAmount.textSize}")
//            binding.inputSymbolDash.updateLayoutParams {
//
//            }
//        }
    }

    fun setOnCurrencyToggleClicked(listener: () -> Unit) {
        onCurrencyToggleClicked = listener
    }

    fun setOnDashToFiatChanged(listener: (Boolean) -> Unit) {
        onDashToFiatChanged = listener
    }

    fun setOnAmountChanged(listener: (Coin) -> Unit) {
        onAmountChanged = listener
    }

    private fun updateCurrency() {
        exchangeRate?.let { rate ->
            val currencyFormat = (NumberFormat.getCurrencyInstance() as DecimalFormat).apply {
                currency = Currency.getInstance(rate.fiat.currencyCode)
            }
            this.currencySymbol = currencyFormat.decimalFormatSymbols.currencySymbol
            this.isCurrencySymbolFirst = currencyFormat.format(1.0).startsWith(currencySymbol)
        }
    }

    private fun updateAmount() {
        binding.inputAmount.text = formatInputWithCurrency()
        val rate = exchangeRate

        if (rate != null) {
            val cleanedValue = GenericUtils.formatFiatWithoutComma(input)

            if (dashToFiat) {
                dashAmount = Coin.parseCoin(cleanedValue)
                fiatAmount = rate.coinToFiat(dashAmount)
            } else {
                fiatAmount = Fiat.parseFiat(rate.fiat.currencyCode, cleanedValue)
                dashAmount = rate.fiatToCoin(fiatAmount)
            }

            binding.resultAmount.text = if (dashToFiat) {
                GenericUtils.fiatToString(fiatAmount)
            } else {
                dashFormat.format(dashAmount)
            }
        } else {
            binding.resultAmount.text = "0"
            Log.e(AmountView::class.java.name, "Exchange rate is not initialized")
        }
    }

    private fun updateDashSymbols() {
        binding.inputCurrencyToggle.isVisible = showCurrencySelector && !dashToFiat
        binding.resultCurrencyToggle.isVisible = showCurrencySelector && dashToFiat

        if (dashToFiat) {
            binding.inputSymbolDash.isVisible = isCurrencySymbolFirst
            binding.inputSymbolDashPostfix.isVisible = !isCurrencySymbolFirst

            binding.resultSymbolDash.isVisible = false
            binding.resultSymbolDashPostfix.isVisible = false
        } else {
            binding.resultSymbolDash.isVisible = isCurrencySymbolFirst
            binding.resultSymbolDashPostfix.isVisible = !isCurrencySymbolFirst

            binding.inputSymbolDash.isVisible = false
            binding.inputSymbolDashPostfix.isVisible = false
        }
    }

    private fun formatInputWithCurrency(): String {
        return when {
            dashToFiat -> input
            isCurrencySymbolFirst -> "$currencySymbol $input"
            else -> "$input $currencySymbol"
        }
    }
}
