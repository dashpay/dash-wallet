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
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.FeeEstimate
import de.schildbach.wallet.service.platform.sdk.NotSentReason
import de.schildbach.wallet.ui.shielded.toDisplayString
import de.schildbach.wallet.ui.shielded.toFiatAt
import de.schildbach.wallet_test.R
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.Fiat
import org.dash.wallet.common.ui.components.ButtonData
import org.dash.wallet.common.ui.components.DASH_CURRENCY_CODE
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.EnterAmount
import org.dash.wallet.common.ui.components.Grabber
import org.dash.wallet.common.ui.components.ListItem
import org.dash.wallet.common.ui.components.ListItem1
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.ModalDialog
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.SystemMessage
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.ui.enter_amount.decimalSeparatorFor
import org.dash.wallet.common.ui.enter_amount.localizeAmountForDisplay
import java.util.Locale

// The confirm, progress and outcome UI shared by the Advanced-mode External Send (MO-1121) and
// Internal Transfers (MO-1125): one fee + confirm sheet, one sending overlay, one result dialog.

// ── Sheets ──────────────────────────────────────────────────────────────────

/** Dimmed backdrop and a bottom sheet with a grabber, as in the shielded flows. */
@Composable
internal fun SheetOverlay(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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

/**
 * Figma 7076:47968 / 7107:48421: amount, From / To / Network fee / Total, info notes, buttons.
 *
 * @param amount what the recipient (or the own To balance) receives.
 * @param fee null with [feeLoading] false = cannot be known before the send (never fabricated).
 * @param total what leaves the balance; null while unknown.
 * @param feeIncluded the fee comes OUT of the amount (a Core → Core drain): [total] is exact.
 * @param notes the info notes under the rows, in order.
 */
@Composable
internal fun SendConfirmSheet(
    amount: Dash,
    rate: Fiat?,
    fiatCode: String?,
    fromValue: String,
    toValue: String,
    fee: FeeEstimate?,
    feeLoading: Boolean,
    total: Dash?,
    feeIncluded: Boolean,
    notes: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val unknown = stringResource(R.string.external_send_value_unknown)
    SheetOverlay(onDismiss = onDismiss) {
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
            ConfirmAmount(amount, rate, fiatCode)
            Spacer(modifier = Modifier.height(20.dp))
            Menu {
                ListItem1(label = stringResource(R.string.external_send_from), value = fromValue)
                ListItem1(label = stringResource(R.string.external_send_to), value = toValue)
                val shownFee = if (feeLoading) null else fee
                DashValueRow(
                    label = stringResource(R.string.external_send_network_fee),
                    value = shownFee?.let { approximate(it.fee.toDisplayString(), !it.exact) },
                    unknown = unknown
                )
                DashValueRow(
                    label = stringResource(R.string.external_send_total),
                    // A drain's total is the confirmed amount itself (exact); otherwise amount + fee.
                    value = total?.takeIf { shownFee != null || feeIncluded }?.let { value ->
                        approximate(value.toDisplayString(), !feeIncluded && shownFee?.exact != true)
                    },
                    unknown = unknown
                )
                ConfirmNotes(notes)
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
                onClick = onDismiss
            )
            DashButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.button_confirm),
                style = Style.FilledBlue,
                size = Size.Large,
                onClick = onConfirm
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

/** The amount bar's currencies: Dash, then the fiat currency when there is one. */
internal fun currencyCodesFor(fiatCode: String?): List<String> =
    if (fiatCode != null) listOf(DASH_CURRENCY_CODE, fiatCode) else listOf(DASH_CURRENCY_CODE)

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

/** The confirm sheet's info notes (Figma 7107:48425), in order; nothing when [notes] is empty. */
@Composable
private fun ConfirmNotes(notes: List<Int>) {
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
internal fun SendingOverlay(shieldedLeg: Boolean, onLeave: () -> Unit) {
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

/** The terminal outcome. Only Not sent offers to go back and edit; nothing offers a retry. */
@Composable
internal fun ResultDialog(result: ExternalSendResult, afterRestart: Boolean, onClose: () -> Unit) {
    val content = resultContentFor(result, afterRestart)
    ModalDialog(
        showDialog = true,
        onDismissRequest = onClose,
        icon = ImageVector.vectorResource(content.icon),
        heading = stringResource(content.title),
        textBlocks = listOf(stringResource(content.message)),
        buttons = listOf(
            ButtonData(
                label = stringResource(content.button),
                onClick = onClose,
                isPrimary = true,
                style = Style.TintedGray
            )
        )
    )
}

/**
 * The terminal outcome. Only Not sent offers to go back and edit; nothing offers a retry.
 *
 * Drawn inside the screen, like [SheetOverlay] and [SendingOverlay], rather than as a
 * [ModalDialog]: a dialog is its own window, which stays above the lock screen and could be
 * closed without the PIN. In the screen it is covered by the lock screen and shows again after
 * unlock. The card copies that dialog's layout so it looks the same. Back is handled by the
 * fragment, which acknowledges the outcome as the dialog's dismiss-on-back did.
 */
@Composable
internal fun ResultOverlay(result: ExternalSendResult, afterRestart: Boolean, onClose: () -> Unit) {
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
    ExternalSendResult.CompletesAutomatically -> ResultContent(
        org.dash.wallet.common.R.drawable.ic_toast_info_warning,
        R.string.internal_transfer_completes_automatically_title,
        R.string.internal_transfer_completes_automatically_message,
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
