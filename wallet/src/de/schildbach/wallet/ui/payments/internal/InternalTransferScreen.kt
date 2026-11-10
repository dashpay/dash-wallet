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

package de.schildbach.wallet.ui.payments.internal

import android.os.Build
import android.view.Window
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.service.platform.sdk.InternalTransferSupport
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.ui.payments.send.ExternalSendStatus
import de.schildbach.wallet.ui.payments.send.ResultDialog
import de.schildbach.wallet.ui.payments.send.SendConfirmSheet
import de.schildbach.wallet.ui.payments.send.SendingOverlay
import de.schildbach.wallet.ui.payments.send.SheetOverlay
import de.schildbach.wallet.ui.payments.send.balanceNameFor
import de.schildbach.wallet.ui.payments.send.iconFor
import de.schildbach.wallet.ui.payments.send.titleFor
import de.schildbach.wallet.ui.shielded.toDisplay
import de.schildbach.wallet.ui.shielded.toDisplayString
import de.schildbach.wallet.ui.shielded.toFiatAt
import de.schildbach.wallet_test.R
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.ui.components.DASH_CURRENCY_CODE
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.ui.getActivity
import org.dash.wallet.common.ui.enter_amount.AmountEntryPage
import org.dash.wallet.common.ui.enter_amount.AmountEntryPrimaryButton

/** The callbacks of [InternalTransferScreen], one per user action. */
class InternalTransferActions(
    val onKeyInput: (String) -> Unit,
    val onCurrencySelected: (dashMode: Boolean) -> Unit,
    val onMaxClick: () -> Unit,
    val onShowPicker: (InternalTransferPicker) -> Unit,
    val onDismissPicker: () -> Unit,
    val onBalancePicked: (SendBalance) -> Unit,
    val onSwap: () -> Unit,
    val onContinue: () -> Unit,
    val onDismissConfirm: () -> Unit,
    val onConfirm: () -> Unit,
    val onHideSending: () -> Unit,
    val onResultClosed: () -> Unit
)

/** Disabled picker rows, as the shared `ActionItem` dims them. */
private const val DISABLED_ROW_ALPHA = 0.4f

/**
 * The Internal tab of the Advanced payments screen (MO-1125, Figma 3342:12176) under the
 * Receive / Internal / Send picker the host draws: the amount, the From / To cards with the swap
 * button, the keypad and Continue; the "Transfer from / to" picker (3342:12177); and the shared
 * confirm sheet, sending overlay and outcome dialog of the External Send.
 */
@Composable
fun InternalTransferScreen(uiState: InternalTransferUIState, actions: InternalTransferActions) {
    val colors = LocalDashColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
    ) {
        AmountPage(uiState, actions)

        val picker = uiState.picker
        if (picker != null && uiState.formEditable) {
            AboveBottomNavigation(onBack = actions.onDismissPicker) {
                PickerSheet(uiState, picker, actions)
            }
        }

        val confirm = uiState.confirm
        if (confirm != null && uiState.status == ExternalSendStatus.Idle) {
            val request = confirm.request
            AboveBottomNavigation(onBack = actions.onDismissConfirm) {
                SendConfirmSheet(
                    amount = request.amount,
                    rate = uiState.rate,
                    fiatCode = uiState.fiatCode,
                    fromValue = stringResource(balanceNameFor(request.from)),
                    toValue = stringResource(balanceNameFor(request.to)),
                    fee = confirm.fee,
                    feeLoading = confirm.feeLoading,
                    total = confirm.total,
                    feeIncluded = false,
                    notes = internalConfirmNotes(request.from, request.to),
                    onDismiss = actions.onDismissConfirm,
                    onConfirm = actions.onConfirm
                )
            }
        }

        when (val status = uiState.status) {
            // A shielded leg's ~30 s proof can be left: Close hides the overlay (the form stays
            // disabled) and the outcome shows when it arrives. Back does what Close does, and
            // nothing while a Core / Platform transfer (seconds, not leavable) runs.
            // Its own window would sit above the lock screen: withheld while it is up.
            is ExternalSendStatus.Sending -> if (!uiState.sendingHidden && !uiState.lockScreenShown) {
                AboveBottomNavigation(onBack = if (status.shieldedLeg) actions.onHideSending else null) {
                    SendingOverlay(status.shieldedLeg, actions.onHideSending)
                }
            }
            // Its own window would sit above the lock screen: withheld (not acknowledged) until unlock.
            is ExternalSendStatus.Done -> if (!uiState.lockScreenShown) {
                ResultDialog(status.result, status.afterRestart, actions.onResultClosed)
            }
            ExternalSendStatus.Idle, ExternalSendStatus.Restoring -> {}
        }
    }
}

/**
 * Shows [content] (a full-screen scrim with a sheet or a card) in its own window, over the host's
 * tab picker and the app's bottom navigation. The Internal page is a pager page of
 * AdvancedPaymentsFragment, whose `nav_home` destination keeps MainActivity's bottom navigation
 * drawn over the bottom of the nav host: drawn in the page, the sheet's lower rows would sit under
 * it, and the scrim would leave the tab picker and the navigation tappable. The External Send has
 * no bottom navigation, so it draws the same overlays in place.
 *
 * The window covers the whole screen (`decorFitsSystemWindows = false`) and the content is inset by
 * the system bars and the display cutout, as MainActivity's `fitsSystemWindows` content is: the
 * scrim starts at the bottom of the status bar and the sheet ends at the top of the system
 * navigation, exactly where they are in the External Send. A decor-fitted dialog window is laid out
 * by the platform instead, which left an un-dimmed band under the status bar. The status and
 * navigation bars stay MainActivity's: the window draws no bar backgrounds of its own and copies
 * the activity's light/dark bar icons. The window has no dim of its own: the overlay's scrim is the
 * only one, so it looks as it does in the External Send. Back calls [onBack] (null: Back does
 * nothing while the window shows).
 *
 * Only the selected pager page (RESUMED; ViewPager2 keeps the offscreen one at STARTED) opens the
 * window: an offscreen Internal page draws the overlay in place, where it is not seen.
 */
@Composable
private fun AboveBottomNavigation(onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
        content()
        return
    }
    val hostWindow = LocalContext.current.getActivity()?.window
    Dialog(
        onDismissRequest = { onBack?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onBack != null,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let { matchHostSystemBars(it, hostWindow) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
        ) {
            content()
        }
    }
}

/**
 * The overlay [window] leaves the system bars to the [hostWindow]: no dim, no animation, transparent
 * bar backgrounds (MainActivity's show through) and the host's light/dark bar icons.
 */
private fun matchHostSystemBars(window: Window, hostWindow: Window?) {
    window.setDimAmount(0f)
    window.setWindowAnimations(0)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        // No-ops on API 35+, where the bar backgrounds are always transparent.
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    if (hostWindow != null) {
        val host = WindowCompat.getInsetsController(hostWindow, hostWindow.decorView)
        val overlay = WindowCompat.getInsetsController(window, window.decorView)
        overlay.isAppearanceLightStatusBars = host.isAppearanceLightStatusBars
        overlay.isAppearanceLightNavigationBars = host.isAppearanceLightNavigationBars
    }
}

/**
 * The confirm notes (AC11/AC13, reusing the External Send's): out of Shielded the transfer is not
 * instant and its amount becomes public; Identity → Core is a withdrawal that takes some time.
 */
internal fun internalConfirmNotes(from: SendBalance, to: SendBalance): List<Int> = buildList {
    if (from == SendBalance.SHIELDED && to != SendBalance.SHIELDED) {
        add(R.string.external_send_shielded_not_instant)
        add(
            when (to) {
                SendBalance.CORE -> R.string.external_send_shielded_public_to_core
                SendBalance.PLATFORM -> R.string.external_send_shielded_public_to_platform
                else -> R.string.external_send_shielded_public_to_identity
            }
        )
    }
    if (from == SendBalance.IDENTITY && to == SendBalance.CORE) {
        add(R.string.internal_transfer_withdrawal_not_instant)
    }
}

@Composable
private fun AmountPage(uiState: InternalTransferUIState, actions: InternalTransferActions) {
    val fiatCode = uiState.fiatCode
    val codes = if (fiatCode != null) listOf(DASH_CURRENCY_CODE, fiatCode) else listOf(DASH_CURRENCY_CODE)
    AmountEntryPage(
        amount = uiState.amountText,
        currencyCodes = codes,
        selectedCurrencyIndex = if (uiState.dashMode || fiatCode == null) 0 else 1,
        onKeyInput = actions.onKeyInput,
        // Figma 3342:12176: the content column starts 10dp under the tab picker.
        // The bottom clears MainActivity's bottom navigation, drawn over the pager (as the other
        // bottom-nav screens do, e.g. More): the keypad and Continue sit right above it.
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 10.dp, bottom = dimensionResource(R.dimen.bottom_nav_bar_height)),
        secondaryAmount = uiState.secondaryAmountText,
        showPrimaryChevron = false,
        keypadEnabled = uiState.formEditable,
        onCurrencySelected = if (fiatCode != null && uiState.rate != null) {
            { index -> actions.onCurrencySelected(index == 0) }
        } else {
            null
        },
        // Max is offered only when the service knows the max sendable (balance minus fee).
        onMaxClick = if (uiState.maxSendable != null) actions.onMaxClick else null,
        belowAmount = {
            // Figma: 10dp between the amount block and the cards group.
            Spacer(modifier = Modifier.height(10.dp))
            FromToCards(uiState, actions)
            PairStatus(uiState)
        },
        primaryButton = {
            AmountEntryPrimaryButton(
                text = stringResource(R.string.button_continue),
                enabled = uiState.canContinue,
                onClick = actions.onContinue
            )
        }
    )
}

/**
 * Figma 3342:12176 cards group: From over To with a 5dp gap, and the swap button (btn-s
 * 3325:4692) centred on that gap.
 */
@Composable
private fun FromToCards(uiState: InternalTransferUIState, actions: InternalTransferActions) {
    val colors = LocalDashColors.current
    val editable = uiState.formEditable
    val changeFrom = stringResource(R.string.internal_transfer_change_from)
    val changeTo = stringResource(R.string.internal_transfer_change_to)
    val swap = stringResource(R.string.internal_transfer_swap)
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Card {
                DirectionRow(
                    icon = iconFor(uiState.from),
                    label = stringResource(R.string.external_send_from),
                    title = stringResource(titleFor(uiState.from)),
                    modifier = Modifier
                        .clickable(enabled = editable, role = Role.Button) {
                            actions.onShowPicker(InternalTransferPicker.FROM)
                        }
                        .semantics { contentDescription = changeFrom },
                    trailing = { FromBalance(uiState) }
                )
            }
            Card {
                DirectionRow(
                    icon = iconFor(uiState.to),
                    label = stringResource(R.string.external_send_to),
                    title = stringResource(titleFor(uiState.to)),
                    modifier = Modifier
                        .clickable(enabled = editable, role = Role.Button) {
                            actions.onShowPicker(InternalTransferPicker.TO)
                        }
                        .semantics { contentDescription = changeTo },
                    trailing = { Chevron() }
                )
            }
            // btn-s: a 30dp white button, 10dp corners, ringed by a 5dp stroke in the page colour.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(colors.backgroundPrimary)
                    .clickable(enabled = editable, role = Role.Button, onClick = actions.onSwap)
                    .semantics { contentDescription = swap },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(colors.backgroundSecondary, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_swap_up_down),
                        contentDescription = null,
                        tint = colors.dashBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 5.dp.roundToPx()
        val cardConstraints = Constraints(minWidth = width, maxWidth = width)
        val fromCard = measurables[0].measure(cardConstraints)
        val toCard = measurables[1].measure(cardConstraints)
        val button = measurables[2].measure(Constraints())
        layout(width, fromCard.height + gap + toCard.height) {
            fromCard.place(0, 0)
            toCard.place(0, fromCard.height + gap)
            button.place((width - button.width) / 2, fromCard.height + gap / 2 - button.height / 2)
        }
    }
}

/** The From balance: amount with the Dash logo over its fiat value, right-aligned, then a chevron. */
@Composable
private fun FromBalance(uiState: InternalTransferUIState) {
    val colors = LocalDashColors.current
    val balance = uiState.balance
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.End) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = balance?.toDisplayString() ?: stringResource(R.string.external_send_value_unknown),
                    style = MyTheme.Typography.SubheadMedium,
                    color = colors.textPrimary
                )
                if (balance != null) {
                    Icon(
                        painter = painterResource(org.dash.wallet.common.R.drawable.ic_dash_d_black),
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.height(14.dp)
                    )
                }
            }
            balance?.toFiatAt(uiState.rate)?.let { fiat ->
                Text(text = fiat.toDisplay(), style = MyTheme.Typography.Footnote, color = colors.textSecondary)
            }
        }
        Spacer(modifier = Modifier.size(10.dp))
        Chevron()
    }
}

@Composable
private fun Chevron() {
    Icon(
        painter = painterResource(org.dash.wallet.common.R.drawable.ic_menu_row_arrow),
        contentDescription = null,
        tint = LocalDashColors.current.gray300.copy(alpha = 0.9f),
        modifier = Modifier.size(14.dp)
    )
}

/** A Figma "Menu" card: secondary background, 20dp corners, 6dp padding. */
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalDashColors.current.backgroundSecondary, RoundedCornerShape(20.dp))
            .padding(6.dp),
        content = content
    )
}

/** Figma "Action item": 10dp padding and gap, 30dp icon, label over title (0 6dp), trailing content. */
@Composable
private fun DirectionRow(
    @DrawableRes icon: Int,
    label: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    val colors = LocalDashColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(30.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
        ) {
            Text(text = label, style = MyTheme.Typography.Footnote, color = colors.textSecondary)
            Text(text = title, style = MyTheme.Typography.SubheadMedium, color = colors.textPrimary)
        }
        trailing()
    }
}

/** Under the cards: "Insufficient balance" (AC7), or why the pair cannot be transferred. */
@Composable
private fun PairStatus(uiState: InternalTransferUIState) {
    val colors = LocalDashColors.current
    when {
        uiState.support != InternalTransferSupport.SUPPORTED && uiState.shieldedEnabled != null ->
            StatusText(stringResource(R.string.internal_transfer_unavailable), colors.textSecondary)
        uiState.confirmRefused ->
            StatusText(stringResource(R.string.internal_transfer_confirm_refused), colors.red)
        uiState.insufficientBalance ->
            StatusText(stringResource(R.string.internal_transfer_insufficient_balance), colors.red)
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        text = text,
        style = MyTheme.Typography.BodySmall,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    )
}

/** Figma 3342:12177 "Transfer from" / "Transfer to": one row per balance, the selected one blue with a check. */
@Composable
private fun PickerSheet(uiState: InternalTransferUIState, picker: InternalTransferPicker, actions: InternalTransferActions) {
    val selected = if (picker == InternalTransferPicker.FROM) uiState.from else uiState.to
    SheetOverlay(onDismiss = actions.onDismissPicker) {
        TopNavBase(
            leadingPart = false,
            trailingIcon = MyImages.NavBarClose,
            trailingContentDescription = stringResource(R.string.external_send_close),
            onTrailingClick = actions.onDismissPicker,
            title = stringResource(
                if (picker == InternalTransferPicker.FROM) {
                    R.string.internal_transfer_transfer_from
                } else {
                    R.string.internal_transfer_transfer_to
                }
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        Menu {
            uiState.balances.forEach { balance ->
                PickerRow(
                    balance = balance,
                    selected = balance == selected,
                    enabled = uiState.pickable(picker, balance),
                    onClick = { actions.onBalancePicked(balance) }
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** An `ActionItem`-sized row whose title turns dashBlue, with a trailing check, when [selected]. */
@Composable
private fun PickerRow(balance: SendBalance, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalDashColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ROW_ALPHA))
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(painter = painterResource(iconFor(balance)), contentDescription = null, modifier = Modifier.size(30.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp)
        ) {
            Text(
                text = stringResource(titleFor(balance)),
                style = MyTheme.Typography.SubheadMedium,
                color = if (selected) colors.dashBlue else colors.textPrimary
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.internal_transfer_unavailable),
                    style = MyTheme.Typography.Footnote,
                    color = colors.textSecondary
                )
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(org.dash.wallet.common.R.drawable.ic_checkmark_blue),
                contentDescription = null,
                tint = colors.dashBlue,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 760)
@Composable
private fun InternalTransferPreview() {
    DashWalletTheme {
        InternalTransferScreen(
            uiState = InternalTransferUIState(
                shieldedEnabled = true,
                hasIdentity = true,
                amountText = "1",
                balance = Dash.COIN * 2L,
                maxSendable = Dash.COIN * 2L
            ),
            actions = InternalTransferActions(
                onKeyInput = {}, onCurrencySelected = {}, onMaxClick = {}, onShowPicker = {}, onDismissPicker = {},
                onBalancePicked = {}, onSwap = {}, onContinue = {}, onDismissConfirm = {}, onConfirm = {},
                onHideSending = {}, onResultClosed = {}
            )
        )
    }
}
