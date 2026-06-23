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
import org.dash.wallet.common.ui.components.NavBarBackTitle
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardCompose
import org.dash.wallet.integrations.maya.R
import java.util.Locale

@Composable
fun DEXEnterAmountScreen(
    viewModel: DEXEnterAmountViewModel,
    onBackClick: () -> Unit,
    onContinueClick: (amount: String, currencyCode: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DEXEnterAmountScreenContent(
        amount = uiState.amount,
        currencyCodes = uiState.currencyCodes,
        selectedCurrencyIndex = uiState.selectedCurrencyIndex,
        continueEnabled = uiState.continueEnabled,
        onKeyInput = viewModel::onKeyInput,
        onCurrencySelected = viewModel::onCurrencySelected,
        onBackClick = onBackClick,
        onContinueClick = {
            val code = uiState.currencyCodes.getOrNull(uiState.selectedCurrencyIndex) ?: uiState.fiatCurrencyCode
            onContinueClick(uiState.amount, code)
        }
    )
}

@Composable
private fun DEXEnterAmountScreenContent(
    amount: String,
    currencyCodes: List<String>,
    selectedCurrencyIndex: Int,
    continueEnabled: Boolean,
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
        NavBarBackTitle(
            title = stringResource(R.string.dex_enter_amount_title),
            onBackClick = onBackClick
        )

        // Amount input bar (design-system EnterAmount). Fiat is the primary input; DASH and
        // the asset being bought are offered as alternate display currencies in the picker.
        // No Max / balance / help text in this frame — only the amount + currency selector.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
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
        onKeyInput = {},
        onCurrencySelected = {},
        onBackClick = {},
        onContinueClick = {}
    )
}