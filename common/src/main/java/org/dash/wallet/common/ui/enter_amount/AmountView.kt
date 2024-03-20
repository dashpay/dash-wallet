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
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
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
import org.dash.wallet.common.util.toFormattedString
import org.slf4j.LoggerFactory
import java.lang.ArithmeticException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.*
import kotlin.math.min

class AmountView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {
    private val log = LoggerFactory.getLogger(AmountView::class.java)
    private val binding = AmountViewBinding.inflate(LayoutInflater.from(context), this)
    val dashFormat: MonetaryFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()
    val fiatFormat: MonetaryFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(2).optionalDecimals()

    private var onCurrencyToggleClicked: (() -> Unit)? = null
    private var onDashToFiatChanged: ((Boolean) -> Unit)? = null
    private var onAmountChanged: ((Coin) -> Unit)? = null

    private var currencySymbol = "$"
    private var isCurrencySymbolFirst = true
    var currencyDigits = GenericUtils.getCurrencyDigits()
        set(value) {
            field = value
            updateAmount()
        }

    private val cryptoFormat: DecimalFormat = DecimalFormat(
        "0.########",
        DecimalFormatSymbols(GenericUtils.getDeviceLocale())
    ).apply {
        isDecimalSeparatorAlwaysShown = false
        isGroupingUsed = false
    }

    private var _input = "0"
    var input: String
        get() = _input
        set(value) {
            _input = value.ifEmpty { "0" }
            updateAmount()
        }
    private fun getLocalizedInput(scale: Int): String = cryptoFormat.format(
        GenericUtils.toScaledBigDecimal(_input, localized = false, scale)
    )

    var fiatAmount: Fiat = Fiat.valueOf(Constants.DEFAULT_EXCHANGE_CURRENCY, 0)
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
                        // binding.inputAmount.text = formatInputWithCurrency()
                        setIconWithText(formatInputWithCurrency())
                    } else {
                        // binding.inputAmount.text = resources.getString(R.string.rate_not_available)
                        setIconWithText(resources.getString(R.string.rate_not_available))
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

    var showResultContainer: Boolean = true
        set(value) {
            field = value
            binding.resultContainer.isVisible = !dashToFiat && value
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
        // binding.inputAmount.text = formatInputWithCurrency()
        setIconWithText(formatInputWithCurrency())
        val rate = exchangeRate
        val pair = parseAmounts(input, rate)
        dashAmount = pair.first

        if (pair.second != null) {
            fiatAmount = pair.second!!

            binding.resultAmount.text = if (dashToFiat) {
                fiatAmount.toFormattedString()
            } else {
                dashFormat.format(dashAmount)
            }
        } else {
            binding.resultAmount.text = resources.getString(R.string.rate_not_available)
            log.warn("Exchange rate is not initialized")
        }
        binding.inputAmount.requestLayout()
        binding.inputAmount.invalidate()
    }

    private fun parseAmounts(input: String, rate: ExchangeRate?): Pair<Coin, Fiat?> {
        val cleanedValue = GenericUtils.formatFiatWithoutComma(input)
        var dashAmount: Coin = Coin.ZERO
        var fiatAmount: Fiat? = null

        if (dashToFiat) {
            dashAmount = Coin.parseCoin(cleanedValue)
            try {
                fiatAmount = rate?.coinToFiat(dashAmount)
            } catch (e: ArithmeticException) {
                log.info("ArithmeticException {} with {}", dashAmount, rate)
            }
        } else if (rate != null) {
            fiatAmount = Fiat.parseFiat(rate.fiat.currencyCode, cleanedValue)
            try {
                dashAmount = rate.fiatToCoin(fiatAmount)
            } catch (e: ArithmeticException) {
                log.info("ArithmeticException {} with {}", fiatAmount, rate)
            }
        }

        // if (dashAmount.isGreaterThan(Constants.MAX_MONEY)) {
        //    throw IllegalArgumentException()
        // }

        return Pair(dashAmount, fiatAmount)
    }

    private fun updateDashSymbols() {
        binding.inputCurrencyToggle.isVisible = showCurrencySelector && !dashToFiat
        binding.resultCurrencyToggle.isVisible = showCurrencySelector && dashToFiat

        if (dashToFiat) {
            binding.resultSymbolDash.isVisible = false
            binding.resultSymbolDashPostfix.isVisible = false
        } else {
            binding.resultSymbolDash.isVisible = isCurrencySymbolFirst
            binding.resultSymbolDashPostfix.isVisible = !isCurrencySymbolFirst
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

    private fun setIconWithText(text: String, iconSize: Int = 22) {
        val context = binding.inputAmount.context
        val scale = resources.displayMetrics.scaledDensity
        val maxTextWidth = binding.inputAmount.width
        var spannableString = SpannableString(text) // Space for the icon

        // show Dash Icon if DASH is the primary currency
        if (dashToFiat) {
            // TODO: adjust for dark mode

            val roomLeft = maxTextWidth - binding.inputAmount.paint.measureText("$text  ") - (iconSize * scale)
            val sizeRelative = if (roomLeft < 0) {
                val ratio = min(1.0f, (maxTextWidth + roomLeft) / maxTextWidth)
                if (ratio == Float.NEGATIVE_INFINITY) {
                    1.0f
                } else {
                    ratio
                }
            } else {
                1.0f
            }
            val sizeSpan = RelativeSizeSpan(sizeRelative)
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_dash_d_black)?.apply {
                setBounds(0, 0, (iconSize * scale * sizeRelative).toInt(), (iconSize * scale * sizeRelative).toInt())
            }
            val imageSpan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                drawable?.let { ImageSpan(it, ImageSpan.ALIGN_CENTER) }
            } else {
                drawable?.let { CenteredImageSpan(it, binding.inputAmount.context) }
            }
            imageSpan?.let {
                if (GenericUtils.isCurrencySymbolFirst()) {
                    spannableString = SpannableString("  $text")
                    spannableString.setSpan(it, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannableString.setSpan(sizeSpan, 1, spannableString.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                } else {
                    spannableString = SpannableString("$text  ")
                    val len = spannableString.length
                    spannableString.setSpan(it, len - 1, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannableString.setSpan(sizeSpan, 0, len - 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                }
            }
        } else {
            val roomLeft = maxTextWidth - binding.inputAmount.paint.measureText("$text")
            val sizeRelative = if (roomLeft < 0) {
                val ratio = min(1.0f, (maxTextWidth + roomLeft) / maxTextWidth)
                if (ratio == Float.NEGATIVE_INFINITY) {
                    1.0f
                } else {
                    ratio
                }
            } else {
                1.0f
            }
            log.info("resizing number: {} to {}", text, sizeRelative)
            val sizeSpan = RelativeSizeSpan(sizeRelative)
            spannableString.setSpan(sizeSpan, 0, spannableString.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }

        // Apply RelativeSizeSpan for variable text size

        binding.inputAmount.text = spannableString
    }
}
