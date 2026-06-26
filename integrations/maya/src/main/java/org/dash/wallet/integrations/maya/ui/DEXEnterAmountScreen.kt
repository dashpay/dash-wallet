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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dash.wallet.common.ui.components.DASH_CURRENCY_CODE
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.EnterAmount
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.TopIntroSend
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardCompose
import org.dash.wallet.integrations.maya.R
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
        validationError = uiState.validationError,
        coinName = uiState.assetCurrencyCode,
        coinIconUrl = uiState.coinIconUrl,
        showBalance = uiState.showBalance,
        dashBalance = uiState.dashBalance,
        fiatBalance = uiState.fiatBalance,
        onToggleBalance = viewModel::onToggleBalance,
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
    validationError: String?,
    coinName: String?,
    coinIconUrl: String?,
    showBalance: Boolean,
    dashBalance: String,
    fiatBalance: String?,
    onToggleBalance: () -> Unit,
    onKeyInput: (String) -> Unit,
    onCurrencySelected: (Int) -> Unit,
    onBackClick: () -> Unit,
    onContinueClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        NavBarBack(
            onBackClick = onBackClick
        )

        // Amount input bar (design-system EnterAmount). Fiat is the primary input; DASH and
        // the asset being bought are offered as alternate display currencies in the picker.
        // No Max / balance / help text in this frame — only the amount + currency selector.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                //.height(110.dp)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopIntroSend(
                heading = stringResource(R.string.dex_enter_amount_title),
                balanceVisible = showBalance,
                onToggleVisibility = onToggleBalance,
                dashBalance = dashBalance,
                preposition = stringResource(org.dash.wallet.common.R.string.preposition_at),
                toIconUrl = coinIconUrl,
                // toName = coinName,
                fiatBalance = fiatBalance,
                modifier = Modifier
            )
            EnterAmount(
                primaryAmount = amount,
                currencyCodes = currencyCodes,
                selectedCurrencyIndex = selectedCurrencyIndex,
                locale = Locale.getDefault(),
                showMaxButton = false,
                showBalanceButton = false,
                showSecondary = false,
                showCurrencyPicker = true,
                onCurrencyPickerSelect = { _, index -> onCurrencySelected(index) }
            )

            // Buy-quote validation status for the entered amount: a muted "checking" line while a
            // quote is in flight, or the rejection reason in red (e.g. below the route minimum).
            if (isValidating) {
                Text(
                    text = stringResource(R.string.dex_enter_amount_checking),
                    style = MyTheme.Body2Regular,
                    color = MyTheme.Colors.textSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else if (validationError != null) {
                Text(
                    text = validationError.ifBlank { stringResource(R.string.dex_enter_amount_invalid) },
                    style = MyTheme.Body2Regular,
                    color = MyTheme.Colors.red,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Empty flexible space between the amount bar and the keypad.
        Spacer(modifier = Modifier.weight(1f))

        // Numeric keypad with the Continue button in its bottom slot, flush to the bottom edge.
        NumericKeyboardCompose(
            modifier = Modifier.fillMaxWidth(),
            onKeyInput = onKeyInput,
            bottomSlot = {
                DashButton(
                    text = stringResource(R.string.button_continue),
                    style = Style.FilledBlue,
                    size = Size.Large,
                    isEnabled = continueEnabled,
                    onClick = onContinueClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, start = 20.dp, end = 20.dp)
                )
            }
        )
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
        validationError = null,
        coinName = "BTC",
        coinIconUrl = null,
        showBalance = true,
        dashBalance = "1.23456789",
        fiatBalance = "USD 45.67",
        onToggleBalance = {},
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
        validationError = null,
        coinName = "BTC",
        coinIconUrl = null,
        showBalance = true,
        dashBalance = "1.23456789",
        fiatBalance = "USD 45.67",
        onToggleBalance = {},
        onKeyInput = {},
        onCurrencySelected = {},
        onBackClick = {},
        onContinueClick = {}
    )
}