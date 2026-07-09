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

package de.schildbach.wallet.ui.shielded

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.schildbach.wallet_test.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.EnterAmount
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardCompose

/**
 * Shielded "Send to address" flow — reached from the Send tab of the hub
 * (Figma 1684:12990). Address + amount entry for a shielded → shielded
 * transfer; layout follows the internal-transfer screen's structure since
 * the detailed send frames were not part of the fetched designs.
 */
@Composable
fun ShieldedSendScreen(
    viewModel: ShieldedSendViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onFinished: () -> Unit
) {
    ShieldedSendScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onFinished = onFinished,
        onAddressChanged = viewModel::onAddressChanged,
        onKeyInput = viewModel::onKeyInput,
        onMaxClick = viewModel::onMaxClick,
        onCurrencySelected = viewModel::onCurrencySelected,
        onContinue = viewModel::onContinue,
        onDismissConfirm = viewModel::onDismissConfirm,
        onConfirm = viewModel::onConfirm,
        onResultHandled = viewModel::onResultHandled
    )
}

@Composable
fun ShieldedSendScreen(
    uiStateFlow: StateFlow<ShieldedSendUIState>,
    onBackClick: () -> Unit = {},
    onFinished: () -> Unit = {},
    onAddressChanged: (String) -> Unit = {},
    onKeyInput: (String) -> Unit = {},
    onMaxClick: () -> Unit = {},
    onCurrencySelected: (Boolean) -> Unit = {},
    onContinue: () -> Unit = {},
    onDismissConfirm: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onResultHandled: () -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()
    ShieldedSendScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onFinished = onFinished,
        onAddressChanged = onAddressChanged,
        onKeyInput = onKeyInput,
        onMaxClick = onMaxClick,
        onCurrencySelected = onCurrencySelected,
        onContinue = onContinue,
        onDismissConfirm = onDismissConfirm,
        onConfirm = onConfirm,
        onResultHandled = onResultHandled
    )
}

@Composable
private fun ShieldedSendScreenContent(
    uiState: ShieldedSendUIState,
    onBackClick: () -> Unit = {},
    onFinished: () -> Unit = {},
    onAddressChanged: (String) -> Unit = {},
    onKeyInput: (String) -> Unit = {},
    onMaxClick: () -> Unit = {},
    onCurrencySelected: (Boolean) -> Unit = {},
    onContinue: () -> Unit = {},
    onDismissConfirm: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onResultHandled: () -> Unit = {}
) {
    val proving = uiState.submitState == ShieldedSubmitState.Proving
    BackHandler(enabled = proving) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopNavBase(
                leadingIcon = MyImages.MenuChevron,
                onLeadingClick = { if (!proving) onBackClick() },
                centralPart = false,
                trailingPart = false
            )

            TopIntro(heading = stringResource(R.string.shielded_send_title))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                AddressField(
                    address = uiState.address,
                    looksValid = uiState.address.isBlank() || uiState.addressLooksValid,
                    onAddressChanged = onAddressChanged
                )

                Spacer(modifier = Modifier.height(20.dp))

                val secondaryText = if (uiState.dashMode) {
                    uiState.amount.toFiatAt(uiState.rate)?.toPlainString() ?: "0"
                } else {
                    uiState.amount.toKeypadString()
                }
                EnterAmount(
                    primaryAmount = uiState.amountText,
                    secondaryAmount = secondaryText,
                    currencyCodes = listOf("DASH", uiState.fiatCode),
                    selectedCurrencyIndex = if (uiState.dashMode) 0 else 1,
                    showMaxButton = true,
                    showBalanceButton = false,
                    showPrimaryChevron = false,
                    showCurrencyPicker = true,
                    onMaxClick = onMaxClick,
                    onCurrencyPickerSelect = { _, index -> onCurrencySelected(index == 0) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    uiState.insufficientFunds -> Text(
                        text = stringResource(R.string.shielded_error_insufficient_funds),
                        style = MyTheme.Typography.BodyMedium,
                        color = MyTheme.Colors.red,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    uiState.submitState is ShieldedSubmitState.NotSent -> Text(
                        text = stringResource(R.string.shielded_transfer_failed),
                        style = MyTheme.Typography.BodyMedium,
                        color = MyTheme.Colors.red,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            NumericKeyboardCompose(
                modifier = Modifier.fillMaxWidth(),
                onKeyInput = onKeyInput,
                bottomSlot = {
                    Spacer(modifier = Modifier.height(8.dp))
                    DashButton(
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(org.dash.wallet.common.R.string.button_continue),
                        style = Style.FilledBlue,
                        size = Size.Large,
                        isEnabled = uiState.canContinue
                    )
                }
            )
        }

        if (uiState.readyCheckDone && !uiState.ready) {
            Toast(
                text = stringResource(R.string.shielded_error_not_ready),
                imageResource = ToastImageResource.Loading.resourceId,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 15.dp, vertical = 20.dp),
                onActionClick = {}
            )
        }

        if (uiState.showConfirm) {
            ShieldedSendConfirmSheet(
                address = uiState.address.trim(),
                amount = uiState.amount,
                rate = uiState.rate,
                onCancel = onDismissConfirm,
                onConfirm = onConfirm
            )
        }

        when (uiState.submitState) {
            ShieldedSubmitState.Proving -> ProvingOverlay()
            ShieldedSubmitState.Success -> {
                SuccessToastOverlay()
                LaunchedEffect(Unit) {
                    delay(2000)
                    onResultHandled()
                    onFinished()
                }
            }
            ShieldedSubmitState.MayHaveGoneThrough -> AmbiguousOverlay(
                onClose = {
                    onResultHandled()
                    onFinished()
                }
            )
            else -> {}
        }
    }
}

@Composable
private fun AddressField(
    address: String,
    looksValid: Boolean,
    onAddressChanged: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            text = stringResource(R.string.shielded_send_address_hint),
            style = MyTheme.Typography.BodySmall,
            color = if (looksValid) MyTheme.Colors.textTertiary else MyTheme.Colors.red
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = address,
                onValueChange = onAddressChanged,
                textStyle = MyTheme.Typography.BodyMedium.copy(color = MyTheme.Colors.textPrimary),
                maxLines = 3,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp)
            )
            DashButton(
                text = stringResource(R.string.shielded_paste),
                style = Style.TintedBlue,
                size = Size.ExtraSmall,
                stretch = false,
                onClick = {
                    clipboard.getText()?.text?.let { onAddressChanged(it.trim()) }
                }
            )
        }
    }
}

/** Simplified confirm sheet for the shielded → shielded send. */
@Composable
private fun ShieldedSendConfirmSheet(
    address: String,
    amount: Dash,
    rate: Fiat?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0x800A0B0D))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    MyTheme.Colors.backgroundPrimary,
                    RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                )
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            org.dash.wallet.common.ui.components.Grabber()
            Text(
                text = stringResource(R.string.shielded_confirm),
                style = MyTheme.Typography.TitleMediumSemibold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            Text(
                text = "${amount.toDisplayString()} Đ",
                style = MyTheme.Typography.HeadlineLargeMedium,
                color = MyTheme.Colors.textPrimary
            )
            rate?.let {
                Text(
                    text = "~ ${amount.toFiatAt(it)?.toDisplay()}",
                    style = MyTheme.Typography.BodyMedium,
                    color = MyTheme.Colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.shielded_to),
                    style = MyTheme.Typography.BodySmall,
                    color = MyTheme.Colors.textTertiary
                )
                Text(
                    text = address.ellipsizeAddress(head = 18, tail = 8),
                    style = MyTheme.Typography.BodyMedium,
                    color = MyTheme.Colors.textPrimary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashButton(
                    text = stringResource(org.dash.wallet.common.R.string.button_cancel),
                    style = Style.TintedGray,
                    size = Size.Large,
                    modifier = Modifier.weight(1f),
                    stretch = false,
                    onClick = onCancel
                )
                DashButton(
                    text = stringResource(R.string.shielded_confirm),
                    style = Style.FilledBlue,
                    size = Size.Large,
                    modifier = Modifier.weight(1f),
                    stretch = false,
                    onClick = onConfirm
                )
            }
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Send – empty")
@Composable
private fun ShieldedSendPreview() {
    ShieldedSendScreenContent(
        uiState = ShieldedSendUIState(
            ready = true,
            readyCheckDone = true,
            rate = Fiat.parseFiat("USD", "50.00"),
            shieldedBalance = Dash.parse("15.5")
        )
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Send – confirm")
@Composable
private fun ShieldedSendConfirmPreview() {
    ShieldedSendScreenContent(
        uiState = ShieldedSendUIState(
            address = "tdash1qyz3xpruchngctwfvczq5wpfg7rpytqvwka",
            amountText = "1.5",
            ready = true,
            readyCheckDone = true,
            rate = Fiat.parseFiat("USD", "50.00"),
            shieldedBalance = Dash.parse("15.5"),
            showConfirm = true
        )
    )
}
