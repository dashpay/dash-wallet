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

package org.dash.wallet.integrations.maya.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dash.wallet.common.ui.components.DASH_CURRENCY_CODE
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.EnterAmount
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardCompose
import org.dash.wallet.integrations.maya.R
import java.text.DecimalFormatSymbols
import java.util.Locale

@Composable
fun DEXEnterAmountScreen(
    viewModel: DEXEnterAmountViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DEXEnterAmountScreenContent(
        amount = uiState.amount,
        currencyCodes = uiState.currencyCodes,
        selectedCurrencyIndex = uiState.selectedCurrencyIndex,
        continueEnabled = uiState.continueEnabled,
        isValidating = uiState.isValidating,
        isOnline = uiState.isOnline,
        validationErrorRes = uiState.validationErrorRes,
        assetCurrencyCode = uiState.assetCurrencyCode,
        assetDisplayCode = uiState.assetDisplayCode,
        onKeyInput = viewModel::onKeyInput,
        onCurrencySelected = viewModel::onCurrencySelected,
        onBackClick = onBackClick,
        onContinueClick = viewModel::onContinueClicked
    )
}

@Composable
private fun DEXEnterAmountScreenContent(
    amount: String,
    currencyCodes: List<String>,
    selectedCurrencyIndex: Int,
    continueEnabled: Boolean,
    isValidating: Boolean,
    isOnline: Boolean,
    // Non-null when the validation quote rejected the entered amount: the message to show under
    // the amount bar (see DEXEnterAmountUIState.validationErrorRes).
    @StringRes validationErrorRes: Int?,
    // Plain code of the asset being bought ("BTC"), the format argument for validation messages
    // that name the coin.
    assetCurrencyCode: String,
    // Heading form of the asset being bought: tokens qualified with their host network
    // ("USDT (Ethereum)"); native L1 coins just the code ("BTC").
    assetDisplayCode: String,
    onKeyInput: (String) -> Unit,
    onCurrencySelected: (Int) -> Unit,
    onBackClick: () -> Unit,
    onContinueClick: () -> Unit,
    // Overridable so previews can force a specific locale (symbol position, separators).
    locale: Locale = Locale.getDefault()
) {
    // The ViewModel keeps the amount as a plain '.'-separated string (it round-trips through
    // BigDecimal parsing and persistence); the locale's decimal separator is applied here,
    // at the display boundary, and on the keypad's separator key label.
    val decimalSeparator = remember(locale) { DecimalFormatSymbols.getInstance(locale).decimalSeparator }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalDashColors.current.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NavBarBack(
                onBackClick = onBackClick
            )

            // Amount input bar (design-system EnterAmount). Fiat is the primary input; DASH and
            // the asset being bought are offered as alternate display currencies in the picker.
            // No Max / balance / help text in this frame — only the amount + currency selector.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // .height(110.dp)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TopIntro(heading = stringResource(R.string.dex_enter_amount_title, assetDisplayCode))
                EnterAmount(
                    primaryAmount = amount.replace('.', decimalSeparator),
                    currencyCodes = currencyCodes,
                    selectedCurrencyIndex = selectedCurrencyIndex,
                    locale = locale,
                    showMaxButton = false,
                    showBalanceButton = false,
                    showSecondary = false,
                    showCurrencyPicker = true,
                    onCurrencyPickerSelect = { _, index -> onCurrencySelected(index) }
                )

                validationErrorRes?.let { errorRes ->
                    // The message is chosen in the ViewModel: a below-minimum rejection names itself,
                    // anything else falls back to the neutral catch-all (SwapKit's noRoutesFound
                    // can't tell too-low from temporarily-unroutable, and the entered value is in the
                    // selected display currency — so any min/max guess would be misleading). Messages
                    // without a %1$s placeholder simply ignore the coin code.
                    Text(
                        text = stringResource(errorRes, assetCurrencyCode),
                        style = MyTheme.Body2Regular,
                        color = LocalDashColors.current.red,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Empty flexible space between the amount bar and the keypad.
            Spacer(modifier = Modifier.weight(1f))

            // Numeric keypad with the Continue button in its bottom slot, flush to the bottom edge.
            NumericKeyboardCompose(
                modifier = Modifier.fillMaxWidth(),
                // Fully disabled (dimmed, not tappable) while offline — a swap can't be quoted
                // without a connection — and while the entered amount is being validated, so the
                // validated amount can't change mid-flight.
                enabled = isOnline && !isValidating,
                decimalSeparator = decimalSeparator,
                onKeyInput = onKeyInput,
                bottomSlot = {
                    DashButton(
                        text = stringResource(R.string.button_continue),
                        style = Style.FilledBlue,
                        size = Size.Large,
                        // Disabled while offline: a swap can't be quoted without a connection.
                        isEnabled = continueEnabled && isOnline,
                        onClick = onContinueClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, start = 20.dp, end = 20.dp)
                    )
                }
            )
        }

        // No-connection toast, pinned at the bottom. Not dismissable — it must stay visible for as
        // long as the device is offline, since the whole screen is disabled until connectivity returns.
        if (!isOnline) {
            Toast(
                text = stringResource(R.string.maya_no_connection_toast),
                imageResource = ToastImageResource.NoInternet.resourceId,
                onActionClick = {},
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 25.dp, vertical = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXEnterAmountScreenZeroPreview() {
    DEXEnterAmountScreenContent(
        amount = "0",
        currencyCodes = listOf("USD", DASH_CURRENCY_CODE, "BTC"),
        selectedCurrencyIndex = 0,
        continueEnabled = false,
        isValidating = false,
        isOnline = true,
        validationErrorRes = null,
        assetCurrencyCode = "BTC",
        assetDisplayCode = "BTC",
        onKeyInput = {},
        onCurrencySelected = {},
        onBackClick = {},
        onContinueClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXEnterAmountScreenEnabledPreview() {
    DEXEnterAmountScreenContent(
        amount = "125.50",
        currencyCodes = listOf("USD", DASH_CURRENCY_CODE, "BTC"),
        selectedCurrencyIndex = 0,
        continueEnabled = true,
        isValidating = false,
        isOnline = true,
        validationErrorRes = null,
        assetCurrencyCode = "BTC",
        assetDisplayCode = "BTC",
        onKeyInput = {},
        onCurrencySelected = {},
        onBackClick = {},
        onContinueClick = {}
    )
}

/** Amount rejected by the validation quote as below the route's minimum. */
@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXEnterAmountScreenBelowMinimumPreview() {
    DEXEnterAmountScreenContent(
        amount = "0.01",
        currencyCodes = listOf("USD", DASH_CURRENCY_CODE, "BTC"),
        selectedCurrencyIndex = 0,
        continueEnabled = true,
        isValidating = false,
        isOnline = true,
        validationErrorRes = R.string.dex_error_amount_too_small,
        assetCurrencyCode = "BTC",
        assetDisplayCode = "BTC",
        onKeyInput = {},
        onCurrencySelected = {},
        onBackClick = {},
        onContinueClick = {}
    )
}

/** German locale — decimal comma and the € symbol rendered after the amount ("125,50 €"). */
@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXEnterAmountScreenGermanPreview() {
    DEXEnterAmountScreenContent(
        // The amount string arrives from the ViewModel as a plain '.'-separated string;
        // the screen renders it (and the keypad's separator key) with the decimal comma.
        amount = "125.50",
        currencyCodes = listOf("EUR", DASH_CURRENCY_CODE, "BTC"),
        selectedCurrencyIndex = 0,
        continueEnabled = true,
        isValidating = false,
        isOnline = true,
        validationErrorRes = null,
        assetCurrencyCode = "BTC",
        assetDisplayCode = "BTC",
        onKeyInput = {},
        onCurrencySelected = {},
        onBackClick = {},
        onContinueClick = {},
        locale = Locale.GERMANY
    )
}
