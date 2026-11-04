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

package org.dash.wallet.common.ui.enter_amount

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R
import org.dash.wallet.common.ui.components.DASH_CURRENCY_CODE
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.EnterAmount
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.TopIntro
import java.text.DecimalFormatSymbols
import java.util.Locale

/** The decimal separator [locale] displays, e.g. `,` for `de-DE`. */
fun decimalSeparatorFor(locale: Locale): Char = DecimalFormatSymbols.getInstance(locale).decimalSeparator

/**
 * Renders an amount kept as a plain `.`-separated string (the form [processAmountKeyInput] produces
 * and `BigDecimal` parses) with [decimalSeparator], for display only. A trailing separator is kept,
 * so "12." shows as "12," while the user is typing.
 */
fun localizeAmountForDisplay(amount: String, decimalSeparator: Char): String =
    amount.replace('.', decimalSeparator)

/**
 * Full-height amount entry page: an optional [header], the design-system [EnterAmount] bar, optional
 * content under it, and the [NumericKeyboardCompose] keypad flush with the bottom edge, with an
 * optional [primaryButton] inside the keypad panel.
 *
 * State is hoisted. [amount] is the plain `.`-separated amount string for the currency at
 * [selectedCurrencyIndex]; the page localizes its decimal separator (and the keypad's separator
 * key label) from [locale] for display only. Keys go to [onKeyInput] unprocessed ("0"–"9", ".",
 * "back", "back_long"); the owner applies them with [processAmountKeyInput], usually in its
 * ViewModel, so it can choose the decimal places per currency.
 *
 * Switching between Dash and fiat works in one of two ways:
 * - pass [onCurrencySelected] to show [EnterAmount]'s vertical currency picker, or
 * - pass [secondaryAmount] and tap handlers ([onPrimaryAmountClick] / [onSecondaryAmountClick]) to
 *   toggle by tapping the amounts.
 *
 * The Max and show-balance buttons are shown only when [onMaxClick] / [onBalanceClick] are set.
 *
 * The page fills the space its [modifier] gives it; the flexible gap between the amount and the
 * keypad needs a bounded height (e.g. `Modifier.fillMaxSize()` or `Modifier.weight(1f)`).
 *
 * @param keypadEnabled false dims the keypad and ignores taps (e.g. offline, or while the amount is
 *   being validated). It does not affect [primaryButton], which manages its own enabled state.
 * @param header content above the amount bar, e.g. a [TopIntro] heading.
 * @param belowAmount content directly under the amount bar, e.g. a validation message.
 * @param primaryButton content at the bottom of the keypad panel, usually [AmountEntryPrimaryButton].
 */
@Composable
fun AmountEntryPage(
    amount: String,
    currencyCodes: List<String>,
    selectedCurrencyIndex: Int,
    onKeyInput: (String) -> Unit,
    modifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(),
    secondaryAmount: String? = null,
    showPrimaryChevron: Boolean = true,
    helpTextTop: String? = null,
    helpTextBottom: String? = null,
    keypadEnabled: Boolean = true,
    onCurrencySelected: ((Int) -> Unit)? = null,
    onPrimaryAmountClick: () -> Unit = {},
    onSecondaryAmountClick: () -> Unit = {},
    onMaxClick: (() -> Unit)? = null,
    onBalanceClick: (() -> Unit)? = null,
    header: @Composable ColumnScope.() -> Unit = {},
    belowAmount: @Composable ColumnScope.() -> Unit = {},
    primaryButton: (@Composable ColumnScope.() -> Unit)? = null
) {
    val decimalSeparator = remember(locale) { decimalSeparatorFor(locale) }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            header()
            EnterAmount(
                primaryAmount = localizeAmountForDisplay(amount, decimalSeparator),
                secondaryAmount = secondaryAmount?.let { localizeAmountForDisplay(it, decimalSeparator) } ?: "0",
                currencyCodes = currencyCodes,
                selectedCurrencyIndex = selectedCurrencyIndex,
                locale = locale,
                helpTextTop = helpTextTop,
                helpTextBottom = helpTextBottom,
                showMaxButton = onMaxClick != null,
                showBalanceButton = onBalanceClick != null,
                showPrimaryChevron = showPrimaryChevron,
                showSecondary = secondaryAmount != null,
                showCurrencyPicker = onCurrencySelected != null,
                onMaxClick = { onMaxClick?.invoke() },
                onBalanceClick = { onBalanceClick?.invoke() },
                onPrimaryAmountClick = onPrimaryAmountClick,
                onSecondaryAmountClick = onSecondaryAmountClick,
                onCurrencyPickerSelect = { _, index -> onCurrencySelected?.invoke(index) }
            )
            belowAmount()
        }

        // Empty flexible space between the amount bar and the keypad.
        Spacer(modifier = Modifier.weight(1f))

        NumericKeyboardCompose(
            modifier = Modifier.fillMaxWidth(),
            enabled = keypadEnabled,
            decimalSeparator = decimalSeparator,
            onKeyInput = onKeyInput,
            bottomSlot = primaryButton
        )
    }
}

/**
 * The standard primary action for [AmountEntryPage]'s `primaryButton` slot: a full-width large
 * filled button, inset to line up with the keypad keys.
 */
@Composable
fun ColumnScope.AmountEntryPrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    DashButton(
        text = text,
        style = Style.FilledBlue,
        size = Size.Large,
        isEnabled = enabled,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, start = 20.dp, end = 20.dp)
    )
}

/** Receive-style page: no Max or balance buttons, Dash/fiat picker, Continue action. */
@Preview(name = "Picker Light", showBackground = true, widthDp = 393, heightDp = 700, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Picker Dark", showBackground = true, widthDp = 393, heightDp = 700, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AmountEntryPagePickerPreview() {
    DashWalletTheme {
        AmountEntryPage(
            amount = "12.5",
            currencyCodes = listOf("USD", DASH_CURRENCY_CODE),
            selectedCurrencyIndex = 0,
            locale = Locale.US,
            onKeyInput = {},
            onCurrencySelected = {},
            modifier = Modifier
                .fillMaxSize()
                .background(LocalDashColors.current.backgroundPrimary),
            header = { TopIntro(heading = "Enter amount") },
            primaryButton = {
                AmountEntryPrimaryButton(
                    text = stringResource(R.string.button_continue),
                    enabled = true,
                    onClick = {}
                )
            }
        )
    }
}

/** Every option on: Max and balance buttons, tap-to-toggle secondary amount, help text. */
@Preview(name = "Full Light", showBackground = true, widthDp = 393, heightDp = 700, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Full Dark", showBackground = true, widthDp = 393, heightDp = 700, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AmountEntryPageFullPreview() {
    DashWalletTheme {
        AmountEntryPage(
            amount = "0.5",
            secondaryAmount = "14.20",
            currencyCodes = listOf(DASH_CURRENCY_CODE, "USD"),
            selectedCurrencyIndex = 0,
            locale = Locale.US,
            helpTextTop = "Available: 1.2345 DASH",
            onKeyInput = {},
            onMaxClick = {},
            onBalanceClick = {},
            modifier = Modifier
                .fillMaxSize()
                .background(LocalDashColors.current.backgroundPrimary),
            primaryButton = {
                AmountEntryPrimaryButton(
                    text = stringResource(R.string.button_continue),
                    enabled = false,
                    onClick = {}
                )
            }
        )
    }
}

/** German locale with the keypad disabled: decimal comma, € after the amount, dimmed keys. */
@Preview(name = "German Disabled", showBackground = true, widthDp = 393, heightDp = 700)
@Composable
private fun AmountEntryPageGermanDisabledPreview() {
    DashWalletTheme {
        AmountEntryPage(
            amount = "125.50",
            currencyCodes = listOf("EUR", DASH_CURRENCY_CODE),
            selectedCurrencyIndex = 0,
            locale = Locale.GERMANY,
            keypadEnabled = false,
            onKeyInput = {},
            onCurrencySelected = {},
            modifier = Modifier
                .fillMaxSize()
                .background(LocalDashColors.current.backgroundPrimary)
        )
    }
}
