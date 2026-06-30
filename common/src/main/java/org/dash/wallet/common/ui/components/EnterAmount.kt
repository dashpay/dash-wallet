/*
 * Copyright 2026 Dash Core Group.
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

package org.dash.wallet.common.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R
import org.dash.wallet.common.ui.segmented_picker.PickerDisplayMode
import org.dash.wallet.common.ui.segmented_picker.SegmentedOption
import org.dash.wallet.common.ui.segmented_picker.SegmentedPicker
import org.dash.wallet.common.ui.segmented_picker.SegmentedPickerStyle
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** The two display modes for an amount: native Dash or a fiat currency. */
enum class EnterAmountMode { Dash, Fiat }

/** Sentinel currency code that maps to [EnterAmountMode.Dash]. */
const val DASH_CURRENCY_CODE: String = "DASH"

/**
 * Figma: `EnterAmount` (Design system - Android, node 4414:23352).
 *
 * Horizontal amount-input bar from the design system. Renders, left to right:
 * - an optional circular `Max` button,
 * - a centered amount column (primary amount + optional secondary amount + optional top/bottom
 *   help text),
 * - an optional circular show-balance (eye) button,
 * - an optional vertical currency picker.
 *
 * The two currencies displayed (primary and secondary) come from [currencyCodes]; the entry at
 * [selectedCurrencyIndex] is rendered as the primary amount, and the entry at the next index
 * (wrapping) is rendered as the secondary amount. Use the special code `"DASH"` to render the
 * Dash D logo instead of a fiat symbol; any other code is treated as a fiat ISO code (e.g.
 * `"USD"`, `"EUR"`) and its symbol is derived from [locale].
 *
 * The currency symbol (or Dash logo) is placed before or after the number based on [locale]'s
 * standard currency-format pattern — e.g. `$1,234.56` (en-US) vs `1 234,56 €` (fr-FR).
 */
@Composable
fun EnterAmount(
    primaryAmount: String = "0",
    secondaryAmount: String = "0",
    currencyCodes: List<String> = listOf("USD", DASH_CURRENCY_CODE),
    selectedCurrencyIndex: Int = 0,
    locale: Locale = Locale.getDefault(),
    helpTextTop: String? = null,
    helpTextBottom: String? = null,
    showMaxButton: Boolean = true,
    showBalanceButton: Boolean = true,
    showPrimaryChevron: Boolean = true,
    showSecondary: Boolean = true,
    showCurrencyPicker: Boolean = false,
    onMaxClick: () -> Unit = {},
    onBalanceClick: () -> Unit = {},
    onPrimaryAmountClick: () -> Unit = {},
    onSecondaryAmountClick: () -> Unit = {},
    onCurrencyPickerSelect: (SegmentedOption, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val colors = LocalDashColors.current
    val primaryIndex = selectedCurrencyIndex.coerceIn(0, currencyCodes.lastIndex.coerceAtLeast(0))
    val primaryCode = currencyCodes.getOrNull(primaryIndex) ?: DASH_CURRENCY_CODE
    val secondaryCode = currencyCodes
        .getOrNull((primaryIndex + 1) % currencyCodes.size.coerceAtLeast(1))
        ?: DASH_CURRENCY_CODE
    val symbolBeforeAmount = remember(locale) { isCurrencySymbolPrefix(locale) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showMaxButton) {
            MaxBtn(onClick = onMaxClick)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (helpTextTop != null) {
                Text(
                    text = helpTextTop,
                    style = MyTheme.Typography.BodySmall,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            AmountPrimary(
                amount = primaryAmount,
                currencyCode = primaryCode,
                locale = locale,
                symbolBeforeAmount = symbolBeforeAmount,
                showChevron = showPrimaryChevron,
                onClick = onPrimaryAmountClick
            )

            if (showSecondary) {
                AmountSecondary(
                    amount = secondaryAmount,
                    currencyCode = secondaryCode,
                    locale = locale,
                    symbolBeforeAmount = symbolBeforeAmount,
                    onClick = onSecondaryAmountClick
                )
            }

            if (helpTextBottom != null) {
                Text(
                    text = helpTextBottom,
                    style = MyTheme.Typography.BodySmall,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (showBalanceButton) {
            ShowBalanceBtn(onClick = onBalanceClick)
        }

        if (showCurrencyPicker && currencyCodes.size >= 2) {
            SegmentedPicker(
                options = currencyCodes.map { SegmentedOption(it) },
                selectedIndex = primaryIndex,
                style = SegmentedPickerStyle(
                    displayMode = PickerDisplayMode.Vertical,
                ),
                onOptionSelected = onCurrencyPickerSelect
            )
        }
    }
}

@Composable
private fun MaxBtn(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x1A008DE4))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val colors = LocalDashColors.current
        Text(
            text = "Max",
            style = MyTheme.Typography.LabelSmallSemibold,
            color = colors.dashBlue,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ShowBalanceBtn(onClick: () -> Unit) {
    val colors = LocalDashColors.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.primary8)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_show),
            contentDescription = null,
            modifier = Modifier.size(width = 17.5.dp, height = 14.6.dp)
        )
    }
}

@Composable
private fun AmountPrimary(
    amount: String,
    currencyCode: String,
    locale: Locale,
    symbolBeforeAmount: Boolean,
    showChevron: Boolean,
    onClick: () -> Unit
) {
    val mode = modeFor(currencyCode)
    val colors = LocalDashColors.current
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (symbolBeforeAmount) {
            PrimarySymbol(mode = mode, currencyCode = currencyCode, locale = locale)
        }
        Text(
            text = amount,
            style = MyTheme.Typography.HeadlineLargeMedium,
            color = colors.textPrimary
        )
        if (!symbolBeforeAmount) {
            PrimarySymbol(mode = mode, currencyCode = currencyCode, locale = locale)
        }
        if (showChevron) {
            Image(
                painter = painterResource(R.drawable.ic_chevron_down_small),
                contentDescription = null,
                modifier = Modifier.size(width = 10.dp, height = 6.dp)
            )
        }
    }
}

@Composable
private fun PrimarySymbol(mode: EnterAmountMode, currencyCode: String, locale: Locale) {
    val colors = LocalDashColors.current
    when (mode) {
        EnterAmountMode.Dash -> Image(
            painter = painterResource(R.drawable.ic_dash_d_black),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        EnterAmountMode.Fiat -> Text(
            text = fiatSymbolForCode(currencyCode, locale),
            style = MyTheme.Typography.HeadlineLargeMedium,
            color = colors.textPrimary
        )
    }
}

@Composable
private fun AmountSecondary(
    amount: String,
    currencyCode: String,
    locale: Locale,
    symbolBeforeAmount: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalDashColors.current
    val mode = modeFor(currencyCode)
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (symbolBeforeAmount) {
                SecondarySymbol(mode = mode, currencyCode = currencyCode, locale = locale)
            }
            Text(
                text = amount,
                style = MyTheme.Typography.BodyMedium,
                color = colors.textTertiary
            )
            if (!symbolBeforeAmount) {
                SecondarySymbol(mode = mode, currencyCode = currencyCode, locale = locale)
            }
        }
        Image(
            painter = painterResource(R.drawable.ic_chevron_down_small),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.textTertiary),
            modifier = Modifier.size(width = 5.dp, height = 2.5.dp)
        )
    }
}

@Composable
private fun SecondarySymbol(mode: EnterAmountMode, currencyCode: String, locale: Locale) {
    val colors = LocalDashColors.current
    when (mode) {
        EnterAmountMode.Dash -> Image(
            painter = painterResource(R.drawable.ic_dash_d_gray),
            contentDescription = null,
            modifier = Modifier.size(9.dp)
        )
        EnterAmountMode.Fiat -> Text(
            text = fiatSymbolForCode(currencyCode, locale),
            style = MyTheme.Typography.BodyMedium,
            color = colors.textTertiary
        )
    }
}

private fun modeFor(currencyCode: String): EnterAmountMode =
    if (currencyCode.equals(DASH_CURRENCY_CODE, ignoreCase = true)) {
        EnterAmountMode.Dash
    } else {
        EnterAmountMode.Fiat
    }

private fun fiatSymbolForCode(currencyCode: String, locale: Locale): String =
    runCatching { Currency.getInstance(currencyCode).getSymbol(locale) }
        .getOrDefault(currencyCode)

private fun isCurrencySymbolPrefix(locale: Locale): Boolean {
    val format = NumberFormat.getCurrencyInstance(locale)
    if (format is DecimalFormat) {
        val pattern = format.toPattern()
        val symbolPos = pattern.indexOf('¤') // ¤ — currency placeholder
        val firstDigitPos = pattern.indexOfAny(charArrayOf('#', '0'))
        if (symbolPos >= 0 && firstDigitPos >= 0) return symbolPos < firstDigitPos
    }
    return true
}

@Preview(name = "Fiat Primary Light", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Fiat Primary Dark", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EnterAmountFiatPrimaryPreview() {
    DashWalletTheme {
        EnterAmount(
            primaryAmount = "1,234.00",
            secondaryAmount = "12.3456",
            currencyCodes = listOf("USD", DASH_CURRENCY_CODE),
            selectedCurrencyIndex = 0,
            locale = Locale.US
        )
    }
}

@Preview(name = "Dash Primary Light", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dash Primary Dark", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EnterAmountDashPrimaryPreview() {
    DashWalletTheme {
        EnterAmount(
            primaryAmount = "12.3456",
            secondaryAmount = "1,234.00",
            currencyCodes = listOf("USD", DASH_CURRENCY_CODE),
            selectedCurrencyIndex = 1,
            locale = Locale.US
        )
    }
}

@Preview(name = "French Locale Light", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "French Locale Dark", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EnterAmountFrenchLocalePreview() {
    // fr-FR puts the symbol AFTER the amount: "1 234,00 €"
    DashWalletTheme {
        EnterAmount(
            primaryAmount = "1 234,00",
            secondaryAmount = "12,3456",
            currencyCodes = listOf("EUR", DASH_CURRENCY_CODE),
            selectedCurrencyIndex = 0,
            locale = Locale.FRANCE
        )
    }
}

@Preview(name = "With Help Text Light", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "With Help Text Dark", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EnterAmountWithHelpTextPreview() {
    DashWalletTheme {
        EnterAmount(
            primaryAmount = "1,234.00",
            secondaryAmount = "12.3456",
            helpTextTop = "Available balance: $1,234",
            helpTextBottom = "Network fee: $0.10"
        )
    }
}

@Preview(name = "Minimal Light", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Minimal Dark", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EnterAmountMinimalPreview() {
    DashWalletTheme {
        EnterAmount(
            primaryAmount = "25",
            showMaxButton = false,
            showBalanceButton = false,
            showSecondary = false
        )
    }
}

@Preview(name = "With Currency Picker Light", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "With Currency Picker Dark", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EnterAmountWithCurrencyPickerPreview() {
    DashWalletTheme {
        EnterAmount(
            primaryAmount = "100",
            secondaryAmount = "1.0023",
            currencyCodes = listOf("USD", "EUR", DASH_CURRENCY_CODE),
            selectedCurrencyIndex = 0,
            showCurrencyPicker = true
        )
    }
}