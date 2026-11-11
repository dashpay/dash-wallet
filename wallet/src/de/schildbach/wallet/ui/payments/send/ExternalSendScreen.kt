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

package de.schildbach.wallet.ui.payments.send

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.IdentityResolution
import de.schildbach.wallet.service.platform.sdk.NotSentReason
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.service.platform.sdk.SendDestination
import de.schildbach.wallet.service.platform.sdk.SendSupport
import de.schildbach.wallet.service.platform.sdk.balanceKindOf
import de.schildbach.wallet.ui.shielded.toDisplay
import de.schildbach.wallet.ui.shielded.toDisplayString
import de.schildbach.wallet.ui.shielded.toFiatAt
import de.schildbach.wallet_test.R
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.Fiat
import org.dash.wallet.common.ui.components.ActionItem
import org.dash.wallet.common.ui.components.AddressField
import org.dash.wallet.common.ui.components.DASH_CURRENCY_CODE
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.EnterAmount
import org.dash.wallet.common.ui.components.Grabber
import org.dash.wallet.common.ui.components.ListItem1
import org.dash.wallet.common.ui.components.ListItem
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.SystemMessage
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.ui.enter_amount.AmountEntryPage
import org.dash.wallet.common.ui.enter_amount.AmountEntryPrimaryButton
import org.dash.wallet.common.ui.enter_amount.decimalSeparatorFor
import org.dash.wallet.common.ui.enter_amount.localizeAmountForDisplay
import java.util.Locale

/** The callbacks of [ExternalSendScreen], one per user action. */
class ExternalSendActions(
    val onBack: () -> Unit,
    val onAddressChanged: (String) -> Unit,
    val onScanClick: () -> Unit,
    val onShowClipboard: () -> Unit,
    val onClipboardEntrySelected: (String) -> Unit,
    val onAddressContinue: () -> Unit,
    val onKeyInput: (String) -> Unit,
    val onCurrencySelected: (dashMode: Boolean) -> Unit,
    val onMaxClick: () -> Unit,
    val onShowSourceSheet: () -> Unit,
    val onDismissSourceSheet: () -> Unit,
    val onSourceSelected: (SendBalance) -> Unit,
    val onRetryIdentity: () -> Unit,
    val onAmountContinue: () -> Unit,
    val onDismissConfirm: () -> Unit,
    val onConfirm: () -> Unit,
    val onLeave: () -> Unit,
    val onResultClosed: (ExternalSendResult) -> Unit
)

/**
 * The external send flow (MO-1121, Figma 6860:47472): the address page (7057:48055 /
 * 7062:48931 / 7062:48871), the amount page (7058:48231), the source sheet (7076:47424) and the
 * confirm sheet (7076:47968 / 7107:48421). Progress and outcome have no frames; they follow the
 * shielded flows (a dimmed progress card) and [org.dash.wallet.common.ui.components.ModalDialog].
 */
@Composable
fun ExternalSendScreen(uiState: ExternalSendUIState, actions: ExternalSendActions) {
    val colors = LocalDashColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
    ) {
        when (uiState.step) {
            ExternalSendStep.ADDRESS -> AddressPage(uiState, actions)
            ExternalSendStep.AMOUNT -> AmountPage(uiState, actions)
        }

        if (uiState.showSourceSheet && uiState.step == ExternalSendStep.AMOUNT) {
            SourceSheet(uiState, actions)
        }

        val confirm = uiState.confirm
        if (confirm != null && uiState.status == ExternalSendStatus.Idle) {
            ConfirmSheet(uiState, confirm, actions)
        }

        when (val status = uiState.status) {
            is ExternalSendStatus.Sending -> SendingOverlay(status.shieldedLeg, actions.onLeave)
            // A sent outcome with its txid opens the transaction result screen instead (the fragment).
            is ExternalSendStatus.Done -> if (uiState.openTransactionResult == null) {
                ResultOverlay(status.result, status.afterRestart) { actions.onResultClosed(status.result) }
            }
            ExternalSendStatus.Idle, ExternalSendStatus.Restoring -> {}
        }
    }
}

// ── Address page ────────────────────────────────────────────────────────────

@Composable
private fun AddressPage(uiState: ExternalSendUIState, actions: ExternalSendActions) {
    val colors = LocalDashColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        TopNavBase(
            leadingIcon = MyImages.MenuChevron,
            leadingContentDescription = stringResource(R.string.external_send_back),
            onLeadingClick = actions.onBack,
            trailingPart = false,
            title = stringResource(R.string.external_send_address_title)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 10.dp)
        ) {
            // Figma "menu" (7057:48065): 6dp card padding + 14dp content inset.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.backgroundSecondary, RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                val errorRes = when (uiState.addressParse) {
                    de.schildbach.wallet.service.platform.sdk.DestinationParse.WrongNetwork ->
                        R.string.external_send_wrong_network
                    de.schildbach.wallet.service.platform.sdk.DestinationParse.Invalid ->
                        R.string.external_send_invalid_address
                    else -> null
                }
                AddressField(
                    value = uiState.addressInput,
                    onValueChange = actions.onAddressChanged,
                    label = stringResource(R.string.external_send_address_label),
                    placeholder = stringResource(R.string.external_send_address_placeholder),
                    message = errorRes?.let { stringResource(it) },
                    isError = errorRes != null,
                    onScanClick = actions.onScanClick
                )

                if (uiState.addressInput.isEmpty() && !uiState.clipboardShown) {
                    Spacer(modifier = Modifier.height(20.dp))
                    DashButton(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        text = stringResource(R.string.external_send_show_clipboard),
                        style = Style.TintedBlue,
                        size = Size.Medium,
                        stretch = false,
                        onClick = actions.onShowClipboard
                    )
                }

                if (uiState.clipboardShown) {
                    Spacer(modifier = Modifier.height(20.dp))
                    ClipboardEntries(uiState.clipboardEntries, actions.onClipboardEntrySelected)
                }
            }
        }
        // Figma BottomBar (7057:48221): a 300dp button, 20dp above and below.
        DashButton(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 60.dp, end = 60.dp, top = 20.dp, bottom = 20.dp),
            text = stringResource(R.string.button_continue),
            style = Style.FilledBlue,
            size = Size.Large,
            isEnabled = uiState.canContinueAddress,
            onClick = actions.onAddressContinue
        )
    }
}

/** Figma 7062:48946: a hint, then each clipboard address as a tappable blue line. */
@Composable
private fun ClipboardEntries(entries: List<String>, onSelected: (String) -> Unit) {
    val colors = LocalDashColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                if (entries.isEmpty()) R.string.external_send_clipboard_empty else R.string.external_send_clipboard_hint
            ),
            style = MyTheme.Typography.BodySmall,
            color = colors.textPrimary
        )
        if (entries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
        }
        entries.forEach { entry ->
            val description = stringResource(R.string.external_send_clipboard_entry_description, entry)
            Text(
                text = entry,
                style = MyTheme.Typography.BodyMedium,
                color = colors.dashBlue,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onSelected(entry) }
                    .semantics { contentDescription = description }
            )
        }
    }
}

// ── Amount page ─────────────────────────────────────────────────────────────

/** The amount bar's currencies: Dash, then the fiat currency when there is one. */
private fun currencyCodesFor(fiatCode: String?): List<String> =
    if (fiatCode != null) listOf(DASH_CURRENCY_CODE, fiatCode) else listOf(DASH_CURRENCY_CODE)

@Composable
private fun AmountPage(uiState: ExternalSendUIState, actions: ExternalSendActions) {
    val fiatCode = uiState.fiatCode
    val codes = remember(fiatCode) { currencyCodesFor(fiatCode) }
    Column(modifier = Modifier.fillMaxSize()) {
        TopNavBase(
            leadingIcon = MyImages.MenuChevron,
            leadingContentDescription = stringResource(R.string.external_send_back),
            onLeadingClick = actions.onBack,
            trailingPart = false,
            centralPart = false
        )
        TopIntro(heading = stringResource(R.string.external_send_title))
        AmountEntryPage(
            amount = uiState.amountText,
            currencyCodes = codes,
            selectedCurrencyIndex = if (uiState.dashMode || fiatCode == null) 0 else 1,
            onKeyInput = actions.onKeyInput,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            secondaryAmount = uiState.secondaryAmountText,
            showPrimaryChevron = false,
            keypadEnabled = uiState.formEditable,
            onCurrencySelected = if (fiatCode != null && uiState.rate != null) {
                { index -> actions.onCurrencySelected(index == 0) }
            } else {
                null
            },
            // Max is offered only when the service knows the max sendable.
            onMaxClick = if (uiState.maxSendable != null) actions.onMaxClick else null,
            belowAmount = {
                Spacer(modifier = Modifier.height(20.dp))
                FromToCards(uiState, actions)
                IdentityStatus(uiState, actions.onRetryIdentity)
            },
            primaryButton = {
                AmountEntryPrimaryButton(
                    text = stringResource(R.string.button_continue),
                    enabled = uiState.canContinueAmount,
                    onClick = actions.onAmountContinue
                )
            }
        )
    }
}

/** Figma 7059:48453: the From card over the To card, a small down-arrow badge between them. */
@Composable
private fun FromToCards(uiState: ExternalSendUIState, actions: ExternalSendActions) {
    val colors = LocalDashColors.current
    val destination = uiState.destination ?: return
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SendCard {
                val balance = uiState.balance
                val changeDescription = stringResource(R.string.external_send_change_source)
                DirectionRow(
                    icon = iconFor(uiState.source),
                    label = stringResource(R.string.external_send_from),
                    title = stringResource(titleFor(uiState.source)),
                    modifier = Modifier
                        .clickable(
                            enabled = !uiState.finished,
                            role = Role.Button,
                            onClick = actions.onShowSourceSheet
                        )
                        .semantics { contentDescription = changeDescription },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.End) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = balance?.toDisplayString()
                                            ?: stringResource(R.string.external_send_value_unknown),
                                        style = MyTheme.Typography.SubheadMedium,
                                        color = colors.textPrimary
                                    )
                                    if (balance != null) {
                                        Icon(
                                            painter = painterResource(org.dash.wallet.common.R.drawable.ic_dash_d_black),
                                            contentDescription = null,
                                            tint = colors.textPrimary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                                balance?.toFiatAt(uiState.rate)?.let { fiat ->
                                    Text(
                                        text = fiat.toDisplay(),
                                        style = MyTheme.Typography.BodySmall,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.size(10.dp))
                            Icon(
                                painter = painterResource(org.dash.wallet.common.R.drawable.ic_menu_row_arrow),
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                )
            }
            SendCard {
                DirectionRow(
                    icon = iconFor(destination),
                    label = stringResource(R.string.external_send_to),
                    title = stringResource(titleFor(destination)),
                    trailing = {
                        Text(
                            text = ExternalSendViewModel.shorten(destination.display),
                            style = MyTheme.Typography.BodyMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
        // Figma btn-s (7059:48473): 30dp, centred on the 5dp gap between the cards.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 58.dp)
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.backgroundSecondary)
                .border(2.dp, colors.backgroundPrimary, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(org.dash.wallet.common.R.drawable.ic_arrow_downward_blue_24dp),
                contentDescription = null,
                tint = colors.dashBlue,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** A Figma "menu" card: secondary background, 20dp corners, 6dp padding. */
@Composable
private fun SendCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalDashColors.current.backgroundSecondary, RoundedCornerShape(20.dp))
            .padding(6.dp),
        content = content
    )
}

/** Figma "Action item / Var 6" and "list-direction": icon, label over title, trailing content. */
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
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(30.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(text = label, style = MyTheme.Typography.Footnote, color = colors.textSecondary)
            Text(text = title, style = MyTheme.Typography.SubheadMedium, color = colors.textPrimary)
        }
        trailing()
    }
}

/** The identity check (AC8) under the cards: checking, not found, or a retry. */
@Composable
private fun IdentityStatus(uiState: ExternalSendUIState, onRetry: () -> Unit) {
    if (uiState.destination !is SendDestination.Identity) return
    val colors = LocalDashColors.current
    when (uiState.identityResolution) {
        null -> StatusText(stringResource(R.string.external_send_identity_checking), colors.textSecondary)
        IdentityResolution.NotFound -> StatusText(stringResource(R.string.external_send_identity_not_found), colors.red)
        IdentityResolution.Unknown -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusText(stringResource(R.string.external_send_identity_unknown), colors.red)
            DashButton(
                text = stringResource(R.string.button_retry),
                style = Style.TintedBlue,
                size = Size.Small,
                stretch = false,
                onClick = onRetry
            )
        }
        is IdentityResolution.Found -> {}
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
            .padding(top = 10.dp, bottom = 6.dp)
    )
}

// ── Sheets ──────────────────────────────────────────────────────────────────

/** Dimmed backdrop and a bottom sheet with a grabber, as in the shielded flows. */
@Composable
private fun SheetOverlay(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x800A0B0D))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onDismiss()
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    LocalDashColors.current.backgroundPrimary,
                    RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            Grabber()
            content()
        }
    }
}

/** Figma 7076:47424 "Select source": the four balances with a radio; unsupported rows disabled. */
@Composable
private fun SourceSheet(uiState: ExternalSendUIState, actions: ExternalSendActions) {
    SheetOverlay(onDismiss = actions.onDismissSourceSheet) {
        TopNavBase(
            leadingPart = false,
            trailingIcon = MyImages.NavBarClose,
            trailingContentDescription = stringResource(R.string.external_send_close),
            onTrailingClick = actions.onDismissSourceSheet,
            title = stringResource(R.string.external_send_select_source)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Menu {
            uiState.sources.forEach { source ->
                val supported = uiState.supportOf(source) == SendSupport.SUPPORTED
                val selected = source == uiState.source
                ActionItem(
                    title = stringResource(titleFor(source)),
                    icon = iconFor(source),
                    enabled = supported,
                    subtitle = if (supported) null else stringResource(R.string.external_send_source_unavailable),
                    trailingContent = { Radio(selected) },
                    onClick = { actions.onSourceSelected(source) }
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun Radio(selected: Boolean) {
    val colors = LocalDashColors.current
    Box(
        modifier = Modifier
            .size(22.dp)
            .border(
                width = if (selected) 6.dp else 1.5.dp,
                color = if (selected) colors.dashBlue else colors.lightGray,
                shape = CircleShape
            )
    )
}

/** Figma 7076:47968 / 7107:48421: amount, From / To / Network fee / Total, info notes, buttons. */
@Composable
private fun ConfirmSheet(uiState: ExternalSendUIState, confirm: ExternalSendConfirm, actions: ExternalSendActions) {
    val request = confirm.request
    val unknown = stringResource(R.string.external_send_value_unknown)
    SheetOverlay(onDismiss = actions.onDismissConfirm) {
        TopNavBase(
            leadingPart = false,
            trailingPart = false,
            title = stringResource(R.string.external_send_confirm_title)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // What the recipient receives (a Core Max drain: the amount minus the fee).
            ConfirmAmount(confirm.recipientAmount, uiState.rate, uiState.fiatCode)
            Spacer(modifier = Modifier.height(20.dp))
            Menu {
                ListItem1(
                    label = stringResource(R.string.external_send_from),
                    value = stringResource(balanceNameFor(request.source))
                )
                val destination = request.destination
                val toValue = when (destination) {
                    is SendDestination.Identity ->
                        (uiState.identityResolution as? IdentityResolution.Found)?.username
                            ?: ExternalSendViewModel.shorten(destination.display)
                    else -> ExternalSendViewModel.shorten(destination.display)
                }
                ListItem1(label = stringResource(R.string.external_send_to), value = toValue)
                val fee = if (confirm.feeLoading) null else confirm.fee
                DashValueRow(
                    label = stringResource(R.string.external_send_network_fee),
                    value = fee?.let { approximate(it.fee.toDisplayString(), !it.exact) },
                    unknown = unknown
                )
                DashValueRow(
                    label = stringResource(R.string.external_send_total),
                    // A drain's total is the confirmed amount itself (exact); otherwise amount + fee.
                    value = confirm.total?.takeIf { fee != null || confirm.feeIncluded }?.let { total ->
                        approximate(total.toDisplayString(), !confirm.feeIncluded && fee?.exact != true)
                    },
                    unknown = unknown
                )
                ConfirmNotes(request.source, destination)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            DashButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.button_cancel),
                style = Style.TintedGray,
                size = Size.Large,
                onClick = actions.onDismissConfirm
            )
            DashButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.button_confirm),
                style = Style.FilledBlue,
                size = Size.Large,
                onClick = actions.onConfirm
            )
        }
    }
}

/** A confirm row whose value is a Dash amount: the Dash symbol before it, or [unknown] alone. */
@Composable
private fun DashValueRow(label: String, value: String?, unknown: String) {
    val colors = LocalDashColors.current
    ListItem(
        label = label,
        trailingText = value ?: unknown,
        trailingLeadingIcon = if (value != null) {
            {
                Icon(
                    painter = painterResource(org.dash.wallet.common.R.drawable.ic_dash_d_black),
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(12.dp)
                )
            }
        } else {
            null
        }
    )
}

@Composable
private fun approximate(value: String, estimate: Boolean): String =
    if (estimate) stringResource(R.string.external_send_approximately, value) else value

/** The confirm sheet's amount block (Figma "amount" 380x97): the same bar as the amount page, read-only. */
@Composable
private fun ConfirmAmount(amount: Dash, rate: Fiat?, fiatCode: String?) {
    val separator = remember { decimalSeparatorFor(Locale.getDefault()) }
    val fiat = amount.toFiatAt(rate)
    EnterAmount(
        primaryAmount = localizeAmountForDisplay(amount.toDisplayString(), separator),
        secondaryAmount = fiat?.let { localizeAmountForDisplay(it.toPlainString(), separator) } ?: "0",
        currencyCodes = remember(fiatCode) { currencyCodesFor(fiatCode) },
        selectedCurrencyIndex = 0,
        showMaxButton = false,
        showBalanceButton = false,
        showPrimaryChevron = false,
        showSecondary = fiat != null,
        showCurrencyPicker = false,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

/**
 * Figma 7107:48425: from the Shielded balance to anywhere but Shielded, the send is not instant
 * and becomes public. An identity destination is paid into its Platform balance (AC8).
 */
@Composable
private fun ConfirmNotes(source: SendBalance, destination: SendDestination) {
    val notes = buildList {
        if (source == SendBalance.SHIELDED && destination !is SendDestination.Shielded) {
            add(R.string.external_send_shielded_not_instant)
            add(
                when (destination) {
                    is SendDestination.Core -> R.string.external_send_shielded_public_to_core
                    is SendDestination.Platform -> R.string.external_send_shielded_public_to_platform
                    else -> R.string.external_send_shielded_public_to_identity
                }
            )
        }
        if (destination is SendDestination.Identity) {
            add(R.string.external_send_identity_balance_note)
        }
    }
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        notes.forEach { note ->
            SystemMessage(
                description = stringResource(note),
                iconRes = org.dash.wallet.common.R.drawable.ic_info_blue
            )
        }
    }
}

// ── Progress and outcome ────────────────────────────────────────────────────

/**
 * The send is running on the app scope. Leaving does not cancel it; the outcome shows when the
 * user opens the external send again.
 */
@Composable
private fun SendingOverlay(shieldedLeg: Boolean, onLeave: () -> Unit) {
    val colors = LocalDashColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x800A0B0D))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .background(colors.backgroundSecondary, RoundedCornerShape(20.dp))
                .padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = colors.dashBlue)
            Text(
                text = stringResource(R.string.external_send_sending_title),
                style = MyTheme.Typography.TitleMediumSemibold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(
                    if (shieldedLeg) R.string.external_send_sending_shielded_message else R.string.external_send_sending_message
                ),
                style = MyTheme.Typography.BodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )
            if (shieldedLeg) {
                DashButton(
                    text = stringResource(R.string.external_send_close),
                    style = Style.TintedGray,
                    size = Size.Large,
                    onClick = onLeave
                )
            }
        }
    }
}

/**
 * The terminal outcome. Only Not sent offers to go back and edit; nothing offers a retry.
 *
 * Drawn inside the screen, like [SheetOverlay] and [SendingOverlay], rather than as a
 * [org.dash.wallet.common.ui.components.ModalDialog]: a dialog is its own window, which stays
 * above the lock screen and could be closed without the PIN. In the screen it is covered by the
 * lock screen and shows again after unlock. The card copies that dialog's layout so it looks the
 * same. Back is handled by the
 * fragment, which acknowledges the outcome as the dialog's dismiss-on-back did.
 */
@Composable
private fun ResultOverlay(result: ExternalSendResult, afterRestart: Boolean, onClose: () -> Unit) {
    val colors = LocalDashColors.current
    val content = resultContentFor(result, afterRestart)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x800A0B0D))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onClose()
            },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(
                containerColor = colors.backgroundSecondary,
                contentColor = colors.textPrimary
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.backgroundSecondary)
                    .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(content.icon),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(content.title),
                        style = MyTheme.SubtitleSemibold,
                        textAlign = TextAlign.Center,
                        color = colors.textPrimary
                    )
                    Text(
                        text = stringResource(content.message),
                        style = MyTheme.Body2Regular,
                        textAlign = TextAlign.Center,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
                Spacer(modifier = Modifier.height(32.dp))
                DashButton(
                    text = stringResource(content.button),
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    size = Size.Medium,
                    style = Style.TintedGray
                )
            }
        }
    }
}

/** What the outcome dialog shows for [result]. */
internal data class ResultContent(
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val message: Int,
    @StringRes val button: Int
)

internal fun resultContentFor(result: ExternalSendResult, afterRestart: Boolean = false): ResultContent = when (result) {
    is ExternalSendResult.Sent -> ResultContent(
        org.dash.wallet.common.R.drawable.ic_success_green,
        R.string.external_send_sent_title,
        R.string.external_send_sent_message,
        R.string.external_send_close
    )
    is ExternalSendResult.NotSent -> ResultContent(
        org.dash.wallet.common.R.drawable.ic_error,
        R.string.external_send_not_sent_title,
        notSentMessageFor(result.reason),
        R.string.external_send_edit
    )
    ExternalSendResult.MayHaveGoneThrough -> ResultContent(
        org.dash.wallet.common.R.drawable.ic_toast_info_warning,
        R.string.external_send_ambiguous_title,
        if (afterRestart) R.string.external_send_ambiguous_after_restart_message else R.string.external_send_ambiguous_message,
        R.string.external_send_close
    )
    ExternalSendResult.InterruptedFundsReturnToShielded -> ResultContent(
        org.dash.wallet.common.R.drawable.ic_toast_info_warning,
        R.string.external_send_interrupted_title,
        R.string.external_send_interrupted_message,
        R.string.external_send_close
    )
}

@StringRes
internal fun notSentMessageFor(reason: NotSentReason): Int = when (reason) {
    NotSentReason.INSUFFICIENT_FUNDS -> R.string.external_send_not_sent_insufficient_funds
    NotSentReason.NOT_SYNCED -> R.string.external_send_not_sent_not_synced
    NotSentReason.SIGNER_LOCKED -> R.string.external_send_not_sent_signer_locked
    NotSentReason.NOT_SUPPORTED -> R.string.external_send_not_sent_not_supported
    NotSentReason.PAYMENT_STILL_CONFIRMING -> R.string.external_send_not_sent_still_confirming
    NotSentReason.IN_PROGRESS -> R.string.external_send_not_sent_in_progress
    NotSentReason.BALANCE_CHANGED -> R.string.external_send_not_sent_balance_changed
    NotSentReason.GENERIC -> R.string.external_send_not_sent_generic
}

// ── Icons and names ─────────────────────────────────────────────────────────

@DrawableRes
internal fun iconFor(source: SendBalance): Int = when (source) {
    SendBalance.CORE -> org.dash.wallet.common.R.drawable.ic_dash_blue_filled
    SendBalance.SHIELDED -> R.drawable.ic_shielded_balance
    SendBalance.PLATFORM -> R.drawable.ic_menu_credits
    SendBalance.IDENTITY -> R.drawable.ic_contact
}

@DrawableRes
internal fun iconFor(destination: SendDestination): Int = iconFor(balanceKindOf(destination))

@StringRes
internal fun titleFor(source: SendBalance): Int = when (source) {
    SendBalance.CORE -> R.string.external_send_balance_core
    SendBalance.SHIELDED -> R.string.external_send_balance_shielded
    SendBalance.PLATFORM -> R.string.external_send_balance_platform
    SendBalance.IDENTITY -> R.string.external_send_balance_identity
}

@StringRes
internal fun titleFor(destination: SendDestination): Int = titleFor(balanceKindOf(destination))

@StringRes
internal fun balanceNameFor(source: SendBalance): Int = when (source) {
    SendBalance.CORE -> R.string.external_send_balance_name_core
    SendBalance.SHIELDED -> R.string.external_send_balance_name_shielded
    SendBalance.PLATFORM -> R.string.external_send_balance_name_platform
    SendBalance.IDENTITY -> R.string.external_send_balance_name_identity
}

@Preview(showBackground = true, widthDp = 420, heightDp = 926)
@Composable
private fun ExternalSendAmountPreview() {
    DashWalletTheme {
        ExternalSendScreen(
            uiState = ExternalSendUIState(
                step = ExternalSendStep.AMOUNT,
                destination = SendDestination.Shielded(
                    "tdash1zqumn3c48sdenak7qr0f8jp3z2d8vg5kafv5snc73tjadg27ajyr7sflp726gtts4u2elrs27eg4a"
                ),
                amountText = "1",
                balance = Dash.COIN * 2L,
                maxSendable = Dash.COIN * 2L
            ),
            actions = previewActions()
        )
    }
}

private fun previewActions() = ExternalSendActions(
    onBack = {}, onAddressChanged = {}, onScanClick = {}, onShowClipboard = {}, onClipboardEntrySelected = {},
    onAddressContinue = {}, onKeyInput = {}, onCurrencySelected = {}, onMaxClick = {}, onShowSourceSheet = {},
    onDismissSourceSheet = {}, onSourceSelected = {}, onRetryIdentity = {}, onAmountContinue = {},
    onDismissConfirm = {}, onConfirm = {}, onLeave = {}, onResultClosed = {}
)
