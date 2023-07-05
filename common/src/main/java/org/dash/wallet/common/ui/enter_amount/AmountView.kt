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

import android.content.ClipboardManager
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import android.util.AttributeSet
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
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
    val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()
    val fiatFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
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
                        .optionalDecimals(0, 6).format(dashAmount).toString()
                } else {
                    binding.resultAmount.text = dashFormat.format(dashAmount)

                    if (exchangeRate != null) {
                        fiatAmount = exchangeRate!!.coinToFiat(dashAmount)
                        _input = fiatFormat.minDecimals(0)
                            .optionalDecimals(0, 2).format(fiatAmount).toString()
                        binding.inputAmount.text = formatInputWithCurrency()
                    } else {
                        binding.inputAmount.text = resources.getString(R.string.rate_not_available)
                    }
                }
            }
        }

    var showCurrencySelector: Boolean = true
        set(value) {
            field = value
            binding.inputCurrencyToggle.isVisible = !dashToFiat && value
            binding.resultCurrencyToggle.isVisible = dashToFiat && value
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
        binding.inputContainer.setOnLongClickListener {
            handlePasteAmount(it)
            true
        }
        binding.resultContainer.setOnClickListener {
            if (showCurrencySelector && dashToFiat) {
                onCurrencyToggleClicked?.invoke()
            }
        }
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
        val pair = parseAmounts(input, rate)
        dashAmount = pair.first

        if (pair.second != null) {
            fiatAmount = pair.second!!

            binding.resultAmount.text = if (dashToFiat) {
                GenericUtils.fiatToString(fiatAmount)
            } else {
                dashFormat.format(dashAmount)
            }
        } else {
            binding.resultAmount.text = resources.getString(R.string.rate_not_available)
            Log.e(AmountView::class.java.name, "Exchange rate is not initialized")
        }
    }

    private fun parseAmounts(input: String, rate: ExchangeRate?): Pair<Coin, Fiat?> {
        val cleanedValue = GenericUtils.formatFiatWithoutComma(input)
        var dashAmount: Coin = Coin.ZERO
        var fiatAmount: Fiat? = null

        if (dashToFiat) {
            dashAmount = Coin.parseCoin(cleanedValue)
            fiatAmount = rate?.coinToFiat(dashAmount)
        } else if (rate != null) {
            fiatAmount = Fiat.parseFiat(rate.fiat.currencyCode, cleanedValue)
            dashAmount = rate.fiatToCoin(fiatAmount)
        }

        if (dashAmount.isGreaterThan(Constants.MAX_MONEY)) {
            throw IllegalArgumentException()
        }

        return Pair(dashAmount, fiatAmount)
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

    private fun handlePasteAmount(view: View) {
        val clipboard = view.context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clipboardText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""

        if (isValidInput(clipboardText)) {
            val wrapper = ContextThemeWrapper(view.context, R.style.My_PopupOverlay)
            val popupMenu = PopupMenu(wrapper, view)
            popupMenu.inflate(R.menu.paste_menu)
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.paste_menu_item -> input = clipboardText
                }
                true
            }
            popupMenu.show()
        }
    }

    private fun isValidInput(input: String): Boolean {
        return try {
            // Only show the Paste popup if the value in the clipboard is valid
            parseAmounts(input, exchangeRate)
            true
        } catch (ex: Exception) {
            false
        }
    }
}
