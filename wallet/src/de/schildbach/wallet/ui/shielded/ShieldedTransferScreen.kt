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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.StateFlow
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.EnterAmount
import org.dash.wallet.common.ui.components.Grabber
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardCompose
import org.dash.wallet.common.util.toFormattedString

private const val DASH_CODE = "DASH"

/**
 * "Internal transfer" screen — Figma 1746:18463 (Dash Wallet → Shielded)
 * and 1746:18480 (Shielded → Dash Wallet), with the confirmation overlays
 * (1689:15082 / 1746:18481), the "Transfers take different times" sheet
 * (1740:16412) and the error/ambiguous submit states.
 */
@Composable
fun ShieldedTransferScreen(
    viewModel: ShieldedTransferViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onFinished: () -> Unit,
    /**
     * Confirm-sheet action. The host overrides this to run the send-flow
     * user authentication (PIN/biometric) BEFORE [ShieldedTransferViewModel.onConfirm]
     * — the Dash Wallet → Shielded direction is a real L1 spend.
     */
    onConfirm: () -> Unit = viewModel::onConfirm,
    /**
     * Invoked on a successful transfer (Broadcast → Success) INSTEAD of
     * an in-screen overlay: the host leaves the flow and lands the user
     * on the More screen with the "Transfer completed" toast (Figma
     * 1691:15460). Defaults to [onFinished].
     */
    onSuccess: () -> Unit = onFinished
) {
    ShieldedTransferScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onFinished = onFinished,
        onSuccess = onSuccess,
        onKeyInput = viewModel::onKeyInput,
        onMaxClick = viewModel::onMaxClick,
        onCurrencySelected = viewModel::onCurrencySelected,
        onSwapDirection = viewModel::onSwapDirection,
        onContinue = viewModel::onContinue,
        onDismissConfirm = viewModel::onDismissConfirm,
        onConfirm = onConfirm,
        onResultHandled = viewModel::onResultHandled,
        onShowTimingInfo = viewModel::onShowTimingInfo,
        onTimingInfoDismissed = viewModel::onTimingInfoDismissed
    )
}

@Composable
fun ShieldedTransferScreen(
    uiStateFlow: StateFlow<ShieldedTransferUIState>,
    onBackClick: () -> Unit = {},
    onFinished: () -> Unit = {},
    onSuccess: () -> Unit = onFinished,
    onKeyInput: (String) -> Unit = {},
    onMaxClick: () -> Unit = {},
    onCurrencySelected: (Boolean) -> Unit = {},
    onSwapDirection: () -> Unit = {},
    onContinue: () -> Unit = {},
    onDismissConfirm: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onResultHandled: () -> Unit = {},
    onShowTimingInfo: () -> Unit = {},
    onTimingInfoDismissed: () -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()
    ShieldedTransferScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onFinished = onFinished,
        onSuccess = onSuccess,
        onKeyInput = onKeyInput,
        onMaxClick = onMaxClick,
        onCurrencySelected = onCurrencySelected,
        onSwapDirection = onSwapDirection,
        onContinue = onContinue,
        onDismissConfirm = onDismissConfirm,
        onConfirm = onConfirm,
        onResultHandled = onResultHandled,
        onShowTimingInfo = onShowTimingInfo,
        onTimingInfoDismissed = onTimingInfoDismissed
    )
}

@Composable
private fun ShieldedTransferScreenContent(
    uiState: ShieldedTransferUIState,
    onBackClick: () -> Unit = {},
    onFinished: () -> Unit = {},
    onSuccess: () -> Unit = onFinished,
    onKeyInput: (String) -> Unit = {},
    onMaxClick: () -> Unit = {},
    onCurrencySelected: (Boolean) -> Unit = {},
    onSwapDirection: () -> Unit = {},
    onContinue: () -> Unit = {},
    onDismissConfirm: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onResultHandled: () -> Unit = {},
    onShowTimingInfo: () -> Unit = {},
    onTimingInfoDismissed: () -> Unit = {}
) {
    val proving = uiState.submitState == ShieldedSubmitState.Proving

    // The proof cannot be cancelled once started — swallow back presses.
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
                trailingIcon = MyImages.NavBarInfo,
                // Bare info glyph (the NavBarBackTitleInfo pattern): the
                // circle Template tints the whole multi-colour vector with
                // textPrimary, which renders it as a solid filled circle.
                trailingIconCircle = false,
                onTrailingClick = onShowTimingInfo
            )

            // Heading + amount + from/to cards + hint share one weighted
            // scrollable region above the fixed keyboard panel (the
            // PurchaseGiftCardScreenV2 pattern), with compact 10dp gaps,
            // so everything fits above the keyboard on ~780dp-tall
            // devices (S22-class 2340×1080 @ 480dpi) — and scrolls rather
            // than clips anywhere smaller.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Text(
                    text = stringResource(R.string.shielded_internal_transfer),
                    style = MyTheme.Typography.HeadlineMediumBold,
                    color = MyTheme.Colors.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp, bottom = 5.dp)
                )

                val secondaryText = if (uiState.dashMode) {
                    uiState.amount.toFiatAt(uiState.rate)?.toPlainString() ?: "0"
                } else {
                    uiState.amount.toKeypadString()
                }
                EnterAmount(
                    primaryAmount = uiState.amountText,
                    secondaryAmount = secondaryText,
                    currencyCodes = listOf(DASH_CODE, uiState.fiatCode),
                    selectedCurrencyIndex = if (uiState.dashMode) 0 else 1,
                    showMaxButton = true,
                    showBalanceButton = false,
                    showPrimaryChevron = false,
                    showCurrencyPicker = true,
                    onMaxClick = onMaxClick,
                    onCurrencyPickerSelect = { _, index -> onCurrencySelected(index == 0) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                DirectionCard(
                    direction = uiState.direction,
                    walletBalance = uiState.walletBalance,
                    pendingWalletBalance = uiState.pendingWalletBalance,
                    shieldedBalance = uiState.shieldedBalance,
                    onSwapDirection = onSwapDirection
                )

                Spacer(modifier = Modifier.height(10.dp))

                TransferHintOrError(uiState = uiState)

                Spacer(modifier = Modifier.height(5.dp))
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

        // Blocked-state toast, split by the REAL reason:
        // (a) "Wait until the chain is fully synced…" (Figma 1733:16190)
        //     for a not-ready runtime or an L1 chain that is still syncing
        //     (both directions are blocked until isSynced);
        // (b) "Verifying your balance…" for a ready runtime on a synced
        //     chain whose Dash Wallet → Shielded direction is still
        //     blocked by the L1 funding-evidence gate (shadow-SPV balance
        //     parity pending) — NOT a sync problem, so it must not reuse
        //     the sync-flavored string.
        if (uiState.readyCheckDone &&
            (!uiState.ready || !uiState.chainSynced || !uiState.directionAvailable)
        ) {
            Toast(
                text = stringResource(
                    if (!uiState.ready || !uiState.chainSynced) {
                        R.string.shielded_error_not_ready
                    } else {
                        R.string.shielded_error_wallet_funding_pending
                    }
                ),
                imageResource = ToastImageResource.Loading.resourceId,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 15.dp, vertical = 20.dp),
                onActionClick = {}
            )
        }

        if (uiState.showConfirm) {
            ShieldedConfirmSheet(
                direction = uiState.direction,
                amount = uiState.amount,
                rate = uiState.rate,
                onCancel = onDismissConfirm,
                onConfirm = onConfirm
            )
        }

        if (uiState.showTimingInfo) {
            TransferTimingSheet(onDismiss = onTimingInfoDismissed)
        }

        when (uiState.submitState) {
            ShieldedSubmitState.Proving -> ProvingOverlay()
            ShieldedSubmitState.Success -> {
                // No in-screen overlay: the host leaves the flow and shows
                // the "Transfer completed" toast on the More screen (AC12).
                LaunchedEffect(Unit) {
                    onResultHandled()
                    onSuccess()
                }
            }
            ShieldedSubmitState.MayHaveGoneThrough -> AmbiguousOverlay(
                onClose = {
                    onResultHandled()
                    onFinished()
                }
            )
            ShieldedSubmitState.LockedPendingShield -> LockedPendingShieldOverlay(
                onClose = {
                    onResultHandled()
                    onFinished()
                }
            )
            else -> {}
        }
    }
}

/** From/To rows with the reverse button — Figma "Frame 14694" (1687:13623). */
@Composable
private fun DirectionCard(
    direction: ShieldedTransferDirection,
    walletBalance: Dash,
    pendingWalletBalance: Dash,
    shieldedBalance: Dash,
    onSwapDirection: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val walletRow: @Composable (String) -> Unit = { label ->
                DirectionRow(
                    label = label,
                    name = stringResource(R.string.shielded_wallet_name),
                    icon = R.drawable.ic_dash_blue_filled,
                    balanceText = walletBalance.toDisplayString(),
                    balanceIsCredits = false,
                    // "<amount> pending": the not-yet-chainlocked part of
                    // the total balance — explains why the transferable
                    // number can be smaller than the wallet's total.
                    secondaryBalanceText = if (pendingWalletBalance.isPositive) {
                        stringResource(
                            R.string.shielded_pending_funds,
                            pendingWalletBalance.toDisplayString()
                        )
                    } else {
                        null
                    }
                )
            }
            val shieldedRow: @Composable (String) -> Unit = { label ->
                DirectionRow(
                    label = label,
                    name = stringResource(R.string.shielded_balance_title),
                    icon = R.drawable.ic_shielded_balance,
                    balanceText = shieldedBalance.toCreditsString(),
                    balanceIsCredits = true
                )
            }
            when (direction) {
                ShieldedTransferDirection.ToShielded -> {
                    walletRow(stringResource(R.string.shielded_from))
                    shieldedRow(stringResource(R.string.shielded_to))
                }
                ShieldedTransferDirection.FromShielded -> {
                    shieldedRow(stringResource(R.string.shielded_from))
                    walletRow(stringResource(R.string.shielded_to))
                }
            }
        }

        // btn-reverse (Figma 1687:13660)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(34.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(MyTheme.Colors.backgroundSecondary)
                .clickable(onClick = onSwapDirection),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(org.dash.wallet.common.R.drawable.ic_swap_blue),
                contentDescription = stringResource(R.string.shielded_swap_direction),
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
private fun DirectionRow(
    label: String,
    name: String,
    icon: Int,
    balanceText: String,
    balanceIsCredits: Boolean,
    secondaryBalanceText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(34.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MyTheme.Typography.BodySmall,
                color = MyTheme.Colors.textTertiary
            )
            Text(
                text = name,
                style = MyTheme.Typography.BodyMediumMedium,
                color = MyTheme.Colors.textPrimary
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            BalanceWithSymbol(text = balanceText, isCredits = balanceIsCredits)
            secondaryBalanceText?.let {
                Text(
                    text = it,
                    style = MyTheme.Typography.BodySmall,
                    color = MyTheme.Colors.textTertiary
                )
            }
        }
    }
}

/** Amount + trailing Đ / C symbol, matching the design's trailing balances. */
@Composable
private fun BalanceWithSymbol(text: String, isCredits: Boolean, big: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = text,
            style = if (big) MyTheme.Typography.BodyMediumMedium else MyTheme.Typography.BodyMedium,
            color = MyTheme.Colors.textPrimary
        )
        if (isCredits) {
            // The design's slanted-C credits glyph, NOT the letter "C"
            // (Figma 1746:18435: 10.496×10 px vector centered on the
            // 20px amount line — both credits texts here are 14sp/20
            // line height, so the design ratio maps 1:1 to dp). The
            // string stays as the accessibility fallback.
            Icon(
                painter = painterResource(R.drawable.ic_credits_symbol),
                contentDescription = stringResource(R.string.shielded_credits_symbol),
                tint = MyTheme.Colors.textPrimary,
                modifier = Modifier.size(width = 10.5.dp, height = 10.dp)
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_dash_d_black),
                contentDescription = null,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/** "You will transfer ~ X" hint, inline errors (Figma 1689:13965 / 1750:19287). */
@Composable
private fun TransferHintOrError(uiState: ShieldedTransferUIState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            uiState.insufficientFunds -> {
                Text(
                    text = stringResource(R.string.shielded_error_insufficient_funds),
                    style = MyTheme.Typography.BodyMedium,
                    color = MyTheme.Colors.red,
                    textAlign = TextAlign.Center
                )
            }
            uiState.submitState is ShieldedSubmitState.NotSent -> {
                Text(
                    text = stringResource(R.string.shielded_transfer_failed),
                    style = MyTheme.Typography.BodyMedium,
                    color = MyTheme.Colors.red,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.shielded_transfer_failed_message),
                    style = MyTheme.Typography.BodySmall,
                    color = MyTheme.Colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            uiState.amount.isPositive -> {
                Text(
                    text = stringResource(R.string.shielded_you_will_transfer),
                    style = MyTheme.Typography.BodySmall,
                    color = MyTheme.Colors.textTertiary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(2.dp))
                // Denominated in what arrives (Figma 1746:18462/18478):
                // To Shielded lands as credits, From Shielded as Dash —
                // the "~" covers the small pool/withdraw fees.
                BalanceWithSymbol(
                    text = uiState.transferHintText,
                    isCredits = uiState.transferHintIsCredits,
                    big = true
                )
            }
        }
    }
}

// ── Overlays ────────────────────────────────────────────────────────────────

/** Shared dimmed-backdrop bottom sheet scaffold (Figma "Overlay" frames). */
@Composable
private fun OverlaySheet(
    onDismiss: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x800A0B0D))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss?.invoke() }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    MyTheme.Colors.backgroundPrimary,
                    RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* consume clicks inside the sheet */ }
        ) {
            Grabber()
            content()
        }
    }
}

/** Confirmation sheet — Figma 1689:15082 (to shielded) / 1746:18481 (from shielded). */
@Composable
private fun ShieldedConfirmSheet(
    direction: ShieldedTransferDirection,
    amount: Dash,
    rate: Fiat?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    OverlaySheet(onDismiss = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.shielded_confirm),
                style = MyTheme.Typography.TitleMediumSemibold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            // Big amount + "~ credits / fiat" line
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = amount.toKeypadString(),
                    style = MyTheme.Typography.HeadlineLargeMedium,
                    color = MyTheme.Colors.textPrimary
                )
                Image(
                    painter = painterResource(R.drawable.ic_dash_d_black),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            // Both directions move Dash (To Shielded spends the L1
            // balance via an asset lock) — only the fiat approximation
            // is shown under the amount; no credits denomination.
            amount.toFiatAt(rate)?.toFormattedString()?.let { fiat ->
                Text(
                    text = "~ $fiat",
                    style = MyTheme.Typography.BodyMedium,
                    color = MyTheme.Colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            val walletName = stringResource(R.string.shielded_wallet_name)
            val shieldedName = stringResource(R.string.shielded_balance_title)
            Menu {
                when (direction) {
                    // To Shielded spends the L1 balance (asset lock), so
                    // the total is denominated in Dash — not credits.
                    ShieldedTransferDirection.ToShielded -> {
                        ConfirmRow(stringResource(R.string.shielded_from), walletName)
                        ConfirmRow(stringResource(R.string.shielded_to), shieldedName)
                        ConfirmRow(
                            stringResource(R.string.shielded_total),
                            "${amount.toDisplayString()} Đ"
                        )
                    }
                    ShieldedTransferDirection.FromShielded -> {
                        ConfirmRow(stringResource(R.string.shielded_from), shieldedName)
                        ConfirmRow(stringResource(R.string.shielded_to), walletName)
                        ConfirmRow(
                            stringResource(R.string.shielded_total),
                            "${amount.toDisplayString()} Đ"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Tip panel (Privacy tip / Up to 10 minutes to spend)
            val tipTitle: String
            val tipMessage: String
            val tipIcon: Int
            when (direction) {
                ShieldedTransferDirection.ToShielded -> {
                    tipTitle = stringResource(R.string.shielded_privacy_tip_title)
                    tipMessage = stringResource(R.string.shielded_privacy_tip_message)
                    tipIcon = R.drawable.ic_shielded_balance
                }
                ShieldedTransferDirection.FromShielded -> {
                    tipTitle = stringResource(R.string.shielded_delay_tip_title)
                    tipMessage = stringResource(R.string.shielded_delay_tip_message)
                    tipIcon = org.dash.wallet.common.R.drawable.ic_info_blue
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(MyTheme.Colors.dashBlue5, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Image(
                    painter = painterResource(tipIcon),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = tipTitle,
                        style = MyTheme.Typography.BodyMediumMedium,
                        color = MyTheme.Colors.textPrimary
                    )
                    Text(
                        text = tipMessage,
                        style = MyTheme.Typography.BodyMedium,
                        color = MyTheme.Colors.textSecondary
                    )
                }
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

@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MyTheme.Typography.BodyMediumMedium,
            color = MyTheme.Colors.textTertiary
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MyTheme.Typography.BodyMedium,
            color = MyTheme.Colors.textPrimary
        )
    }
}

/** "Transfers take different times" — Figma 1740:16412. */
@Composable
private fun TransferTimingSheet(onDismiss: () -> Unit) {
    OverlaySheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.shielded_timing_title),
                style = MyTheme.Typography.HeadlineMediumBold,
                color = MyTheme.Colors.textPrimary
            )

            Spacer(modifier = Modifier.height(20.dp))

            TimingFeatureRow(
                icon = R.drawable.ic_transfer_instant,
                heading = stringResource(R.string.shielded_timing_to_title),
                text = stringResource(R.string.shielded_timing_to_message)
            )
            Spacer(modifier = Modifier.height(16.dp))
            TimingFeatureRow(
                icon = R.drawable.ic_transfer_stopwatch,
                heading = stringResource(R.string.shielded_timing_from_title),
                text = stringResource(R.string.shielded_timing_from_message)
            )

            Spacer(modifier = Modifier.height(30.dp))

            DashButton(
                text = stringResource(R.string.shielded_timing_got_it),
                style = Style.FilledBlue,
                size = Size.Large,
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun TimingFeatureRow(icon: Int, heading: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            // Intrinsic size: the design's glyphs are non-square (20×24 bolt,
            // 19×23 stopwatch) — forcing a square would distort them.
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Color.Unspecified
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = heading,
                style = MyTheme.Typography.TitleSmallSemibold,
                color = MyTheme.Colors.textPrimary
            )
            Text(
                text = text,
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textSecondary
            )
        }
    }
}

/** Indeterminate proving overlay — the ~30s Halo 2 proof cannot be cancelled. */
@Composable
internal fun ProvingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x800A0B0D))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* consume */ },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(20.dp))
                .padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = MyTheme.Colors.dashBlue)
            Text(
                text = stringResource(R.string.shielded_proving_title),
                style = MyTheme.Typography.TitleMediumSemibold,
                color = MyTheme.Colors.textPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.shielded_proving_message),
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** "Transfer completed" toast — Figma 1691:15460. */
@Composable
internal fun SuccessToastOverlay() {
    Box(modifier = Modifier.fillMaxSize()) {
        Toast(
            text = stringResource(R.string.shielded_transfer_completed),
            imageResource = ToastImageResource.Success.resourceId,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 15.dp, vertical = 20.dp),
            onActionClick = {}
        )
    }
}

/**
 * Terminal Ambiguous state: the spend may already be on chain, the notes
 * stay reserved and the next sync reconciles — so no retry is offered.
 */
@Composable
internal fun AmbiguousOverlay(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x800A0B0D))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* consume — must be acknowledged */ },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(20.dp))
                .padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                painter = painterResource(org.dash.wallet.common.R.drawable.ic_toast_info_warning),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = stringResource(R.string.shielded_transfer_ambiguous_title),
                style = MyTheme.Typography.TitleMediumSemibold,
                color = MyTheme.Colors.textPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.shielded_transfer_ambiguous_message),
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textSecondary,
                textAlign = TextAlign.Center
            )
            DashButton(
                text = stringResource(R.string.shielded_close),
                style = Style.TintedGray,
                size = Size.Large,
                onClick = onClose
            )
        }
    }
}

/**
 * Terminal LockedPendingShield state (Dash Wallet → Shielded): the L1
 * asset lock is out — the Dash left the spendable balance — but the
 * shield transition still needs its automatic retry. Must be
 * acknowledged; no manual retry is offered (the funds are committed).
 */
@Composable
internal fun LockedPendingShieldOverlay(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x800A0B0D))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* consume — must be acknowledged */ },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(20.dp))
                .padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                painter = painterResource(org.dash.wallet.common.R.drawable.ic_toast_info_warning),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = stringResource(R.string.shielded_locked_pending_title),
                style = MyTheme.Typography.TitleMediumSemibold,
                color = MyTheme.Colors.textPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.shielded_locked_pending_message),
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textSecondary,
                textAlign = TextAlign.Center
            )
            DashButton(
                text = stringResource(R.string.shielded_close),
                style = Style.TintedGray,
                size = Size.Large,
                onClick = onClose
            )
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────────

private fun previewState(
    direction: ShieldedTransferDirection = ShieldedTransferDirection.ToShielded,
    amountText: String = "1",
    submitState: ShieldedSubmitState = ShieldedSubmitState.Idle,
    showConfirm: Boolean = false,
    totalWalletBalance: Dash = Dash.parse("3.00")
) = ShieldedTransferUIState(
    direction = direction,
    amountText = amountText,
    dashMode = true,
    fiatCode = "USD",
    rate = Fiat.parseFiat("USD", "50.00"),
    walletBalance = Dash.parse("3.00"),
    totalWalletBalance = totalWalletBalance,
    shieldedBalance = Dash.parse("15.5"),
    ready = true,
    readyCheckDone = true,
    chainSynced = true,
    walletShieldingAvailable = true,
    showConfirm = showConfirm,
    submitState = submitState
)

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Transfer – to shielded")
@Composable
private fun TransferToShieldedPreview() {
    ShieldedTransferScreenContent(uiState = previewState())
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Transfer – from shielded")
@Composable
private fun TransferFromShieldedPreview() {
    ShieldedTransferScreenContent(
        uiState = previewState(direction = ShieldedTransferDirection.FromShielded)
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Transfer – insufficient funds")
@Composable
private fun TransferInsufficientPreview() {
    ShieldedTransferScreenContent(uiState = previewState(amountText = "100"))
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Transfer – pending funds")
@Composable
private fun TransferPendingFundsPreview() {
    ShieldedTransferScreenContent(
        uiState = previewState(totalWalletBalance = Dash.parse("4.25"))
    )
}

// S22-class portrait (2340×1080 @ 480dpi ⇒ 780×360dp): amount, both
// cards and the hint must all be visible above the keyboard (Gap 7).
@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Transfer – compact 1080p device")
@Composable
private fun TransferCompactDevicePreview() {
    ShieldedTransferScreenContent(uiState = previewState())
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Transfer – confirm sheet")
@Composable
private fun TransferConfirmPreview() {
    ShieldedTransferScreenContent(uiState = previewState(showConfirm = true))
}

// Verifies the nav-bar info glyph (bare, untinted — no black circle) and
// the timing sheet's yellow-bolt/blue-stopwatch icons + copy (Figma 1740:16412).
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Transfer – timing sheet")
@Composable
private fun TransferTimingSheetPreview() {
    ShieldedTransferScreenContent(uiState = previewState().copy(showTimingInfo = true))
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Transfer – proving")
@Composable
private fun TransferProvingPreview() {
    ShieldedTransferScreenContent(uiState = previewState(submitState = ShieldedSubmitState.Proving))
}

// Chain synced + runtime ready but the L1 funding-evidence gate hasn't
// passed: the toast must show the "Verifying your balance…" variant, not
// the sync-flavored one.
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Transfer – funding verification pending")
@Composable
private fun TransferFundingPendingPreview() {
    ShieldedTransferScreenContent(
        uiState = previewState().copy(walletShieldingAvailable = false)
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Transfer – ambiguous")
@Composable
private fun TransferAmbiguousPreview() {
    ShieldedTransferScreenContent(
        uiState = previewState(submitState = ShieldedSubmitState.MayHaveGoneThrough)
    )
}
