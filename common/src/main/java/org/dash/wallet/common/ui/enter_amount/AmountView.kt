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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.AmountViewBinding
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

    private var currencySymbol = "$"
    private var isCurrencySymbolFirst = true

    var input: String = "0"
        set(value) {
            field = if(value.isEmpty()) "0" else value
            updateAmount()
        }

    var fiatAmount: Fiat = Fiat.valueOf("USD", 0)
        private set

    var dashAmount: Coin = Coin.ZERO
        private set

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

                val monetary = if (value) dashAmount else fiatAmount
                input = if (monetary.value == 0L) {
                    "0"
                } else {
                    if (value) {
                        dashFormat.format(dashAmount)
                    } else {
                        fiatFormat.format(fiatAmount)
                    }.toString()
                }
            }
        }

    init {
        val padding = resources.getDimensionPixelOffset(R.dimen.default_horizontal_padding)
        updatePadding(left = padding, right = padding)
        updateCurrency()

        binding.convertDirectionBtn.setOnClickListener {
            dashToFiat = !dashToFiat
        }
        binding.inputCurrencyToggle.setOnClickListener {
            onCurrencyToggleClicked?.invoke()
        }
        binding.resultCurrencyToggle.setOnClickListener {
            onCurrencyToggleClicked?.invoke()
        }
    }

    fun setOnCurrencyToggleClicked(listener: () -> Unit) {
        onCurrencyToggleClicked = listener
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
        binding.inputAmount.text = when {
            dashToFiat -> input
            isCurrencySymbolFirst -> "$currencySymbol $input"
            else -> "$input $currencySymbol"
        }

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

            binding.resultAmount.text = when {
                !dashToFiat -> dashFormat.format(dashAmount)
                isCurrencySymbolFirst -> "$currencySymbol ${fiatFormat.format(fiatAmount)}"
                else -> "${fiatFormat.format(fiatAmount)} $currencySymbol"
            }
        } else {
            binding.resultAmount.text = "0"
            Log.e(AmountView::class.java.name, "Exchange rate is not initialized")
        }
    }

    private fun updateDashSymbols() {
        binding.inputCurrencyToggle.isVisible = !dashToFiat
        binding.resultCurrencyToggle.isVisible = dashToFiat

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
}
