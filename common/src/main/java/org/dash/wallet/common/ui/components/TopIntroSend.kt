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

package org.dash.wallet.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

/**
 * TopIntroSend — send-flow variant of the top-intro design-system component.
 *
 * Displays a left-aligned [heading] followed by a balance availability row that can be
 * toggled between visible and hidden states via a clickable eye icon.
 *
 * When [balanceVisible] is `true` (or not externally managed), the row shows:
 *   "[dashBalance] · [fiatBalance] available"
 * When hidden, both balance values are replaced with "*****".
 *
 * There are two usage modes:
 *
 * 1. **Internally managed** — omit [balanceVisible] and [onToggleVisibility]; the component
 *    tracks the toggle state itself via [rememberSaveable].
 *
 * 2. **Externally managed** — supply both [balanceVisible] and [onToggleVisibility] to hoist
 *    the state into the caller (e.g. a ViewModel).
 *
 * @param heading            Screen title shown above the balance row.
 * @param dashBalance        Formatted Dash amount string, e.g. "ɗ 1.23456789".
 * @param fiatBalance        Optional formatted fiat equivalent, e.g. "USD 45.67". When null the
 *                           fiat segment is omitted from the row.
 * @param balanceVisible     Whether the balance values are currently visible. Pass `null` (default)
 *                           to let the component manage its own state.
 * @param onToggleVisibility Called when the eye icon is tapped. Only used when [balanceVisible] is
 *                           not null (externally managed mode).
 * @param modifier           Modifier applied to the outer [Column].
 */
@Composable
fun TopIntroSend(
    heading: String,
    dashBalance: String,
    preposition: String = stringResource(R.string.to),
    toAddress: String? = null,
    fiatBalance: String? = null,
    balanceVisible: Boolean? = null,
    onToggleVisibility: (() -> Unit)? = null,
    modifier: Modifier = Modifier.padding(top = 10.dp, start = 20.dp, end = 20.dp, bottom = 20.dp)
) {
    // Internal state used only when the caller does not hoist the toggle.
    var internalVisible by rememberSaveable { mutableStateOf(true) }
    val isVisible = balanceVisible ?: internalVisible

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Heading
        Text(
            text = heading,
            style = MyTheme.H5Bold,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.fillMaxWidth()
        )

        // "to {address}" line
        if (toAddress != null) {
            Text(
                text = buildAnnotatedString {
                    withStyle(MyTheme.Body2Regular.toSpanStyle().copy(color = MyTheme.Colors.textSecondary)) {
                        append(preposition)
                        append(" ")
                    }
                    withStyle(MyTheme.Body2Regular.toSpanStyle().copy(color = MyTheme.Colors.textPrimary)) {
                        append(toAddress)
                    }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Balance availability row
        BalanceRow(
            dashBalance = dashBalance,
            fiatBalance = fiatBalance,
            isVisible = isVisible,
            onToggleClick = {
                if (balanceVisible != null) {
                    onToggleVisibility?.invoke()
                } else {
                    internalVisible = !internalVisible
                }
            }
        )
    }
}

/**
 * Internal balance row: "dash · fiat available  [eye icon]"
 */
@Composable
private fun BalanceRow(
    dashBalance: String,
    fiatBalance: String?,
    isVisible: Boolean,
    onToggleClick: () -> Unit
) {
    val hiddenPlaceholder = "*****"
    val availableLabel = stringResource(R.string.enter_amount_available)

    // Build the annotated balance text so primary and secondary segments share one Text.
    val balanceText = buildAnnotatedString {
        val dashSpanStyle = MyTheme.Typography.BodyMedium.toSpanStyle()
            .copy(color = MyTheme.Colors.textPrimary)
        val fiatSpanStyle = MyTheme.Typography.BodyMedium.toSpanStyle()
            .copy(color = MyTheme.Colors.textSecondary)
        val availableSpanStyle = MyTheme.Typography.BodyMedium.toSpanStyle()
            .copy(color = MyTheme.Colors.textSecondary)

        // Dash portion
        withStyle(dashSpanStyle) {
            append(if (isVisible) dashBalance else hiddenPlaceholder)
        }

        // Fiat portion (optional)
        if (fiatBalance != null) {
            withStyle(fiatSpanStyle) {
                append(" · ")
                append(if (isVisible) fiatBalance else hiddenPlaceholder)
            }
        }

        // " available" suffix
        withStyle(availableSpanStyle) {
            append(" ")
            append(availableLabel)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = balanceText,
            modifier = Modifier.weight(1f, fill = false)
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Eye toggle icon — no ripple to keep it subtle.
        val interactionSource = remember { MutableInteractionSource() }
        Icon(
            painter = painterResource(
                if (isVisible) R.drawable.ic_show else R.drawable.ic_hide
            ),
            contentDescription = if (isVisible) {
                "Hide balance"
            } else {
                "Show balance"
            },
            tint = MyTheme.Colors.textSecondary,
            modifier = Modifier
                .size(20.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggleClick
                )
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393)
@Composable
private fun TopIntroSendVisiblePreview() {
    Column(
        modifier = Modifier
            .background(MyTheme.Colors.backgroundPrimary)
            .padding(vertical = 8.dp)
    ) {
        TopIntroSend(
            heading = "Send",
            toAddress = "XqP9vKtSgMnBr7LjN3FcDwYeZh4Ao8uQ1",
            dashBalance = "ɗ 1.23456789",
            fiatBalance = "USD 45.67"
        )
    }
}

@Preview(showBackground = true, widthDp = 393)
@Composable
private fun TopIntroSendHiddenPreview() {
    Column(
        modifier = Modifier
            .background(MyTheme.Colors.backgroundPrimary)
            .padding(vertical = 8.dp)
    ) {
        TopIntroSend(
            heading = "Send",
            toAddress = "XqP9vKtSgMnBr7LjN3FcDwYeZh4Ao8uQ1",
            dashBalance = "ɗ 1.23456789",
            fiatBalance = "USD 45.67",
            balanceVisible = false,
            onToggleVisibility = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393)
@Composable
private fun TopIntroSendNoFiatPreview() {
    Column(
        modifier = Modifier
            .background(MyTheme.Colors.backgroundPrimary)
            .padding(vertical = 8.dp)
    ) {
        TopIntroSend(
            heading = "Send",
            toAddress = "XqP9vKtSgMnBr7LjN3FcDwYeZh4Ao8uQ1",
            dashBalance = "ɗ 0.50000000"
        )
    }
}