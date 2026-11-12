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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.IdentityResolution
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.service.platform.sdk.SendDestination
import de.schildbach.wallet.service.platform.sdk.SendSupport
import de.schildbach.wallet.service.platform.sdk.balanceKindOf
import de.schildbach.wallet.ui.shielded.toDisplay
import de.schildbach.wallet.ui.shielded.toDisplayString
import de.schildbach.wallet.ui.shielded.toFiatAt
import de.schildbach.wallet_test.R
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.ui.components.ActionItem
import org.dash.wallet.common.ui.components.AddressField
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.ui.enter_amount.AmountEntryPage
import org.dash.wallet.common.ui.enter_amount.AmountEntryPrimaryButton

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

/** Figma 7076:47968 / 7107:48421, through the shared [SendConfirmSheet]. */
@Composable
private fun ConfirmSheet(uiState: ExternalSendUIState, confirm: ExternalSendConfirm, actions: ExternalSendActions) {
    val request = confirm.request
    val destination = request.destination
    val toValue = when (destination) {
        is SendDestination.Identity ->
            (uiState.identityResolution as? IdentityResolution.Found)?.username
                ?: ExternalSendViewModel.shorten(destination.display)
        else -> ExternalSendViewModel.shorten(destination.display)
    }
    SendConfirmSheet(
        // What the recipient receives (a Core Max drain: the amount minus the fee; a
        // Shielded → Core Max: the amount minus the Core fee).
        amount = confirm.recipientAmount,
        rate = uiState.rate,
        fiatCode = uiState.fiatCode,
        fromValue = stringResource(balanceNameFor(request.source)),
        toValue = toValue,
        fee = confirm.fee,
        feeLoading = confirm.feeLoading,
        total = confirm.total,
        feeIncluded = confirm.feeIncluded,
        notes = externalConfirmNotes(request.source, destination),
        onDismiss = actions.onDismissConfirm,
        onConfirm = actions.onConfirm
    )
}

/**
 * Figma 7107:48425: from the Shielded balance to anywhere but Shielded, the send is not instant
 * and becomes public. An identity destination is paid into its Platform balance (AC8).
 */
internal fun externalConfirmNotes(source: SendBalance, destination: SendDestination): List<Int> = buildList {
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
