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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
    toIcon: Painter? = null,
    toIconUrl: String? = null,
    toName: String? = null,
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
        horizontalAlignment = Alignment.Start
    ) {
        // Heading
        Text(
            text = heading,
            style = MyTheme.Typography.HeadlineMediumBold,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.fillMaxWidth()
        )

        // Per Figma: 4dp gap between heading and the merchant/address row.
        Spacer(modifier = Modifier.height(4.dp))

        // "to/at {address|icon+name}" line
        if (toIcon != null || toIconUrl != null || toName != null) {
            // Icon variant: "[preposition] [icon] [name]" — all textPrimary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = preposition,
                    style = MyTheme.Body2Regular,
                    color = MyTheme.Colors.textPrimary
                )
                if (toIconUrl != null) {
                    AsyncImage(
                        model = toIconUrl,
                        contentDescription = toName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else if (toIcon != null) {
                    Image(
                        painter = toIcon,
                        contentDescription = toName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
                if (toName != null) {
                    Text(
                        text = toName,
                        style = MyTheme.Body2Regular,
                        color = MyTheme.Colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else if (toAddress != null) {
            // Address variant: "to [address]" — preposition secondary, address primary
            Text(
                text = buildAnnotatedString {
                    // Per Figma node 4251:16531 the whole "to {address}" line is text/primary.
                    withStyle(MyTheme.Body2Regular.toSpanStyle().copy(color = MyTheme.Colors.textPrimary)) {
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

        // Per Figma: 4dp (spacing/4, the root column gap) between the heading block and balance row.
        Spacer(modifier = Modifier.height(4.dp))

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
 * Internal balance row: "[Dash logo] amount · fiat available  [eye icon]"
 *
 * [dashBalance] should be the formatted amount without the "ɗ" prefix — the logo replaces it.
 */
@Composable
private fun BalanceRow(
    dashBalance: String,
    fiatBalance: String?,
    isVisible: Boolean,
    onToggleClick: () -> Unit
) {
    val hiddenPlaceholder = "*****"
    val balanceLabel = stringResource(R.string.balance)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // "Balance:" — always visible
        Text(
            text = "$balanceLabel ",
            style = MyTheme.Typography.BodyMedium,
            color = MyTheme.Colors.textSecondary
        )

        // Toggleable content: icon + amounts, or placeholder
        if (isVisible) {
            // Per Figma node 4251:16531: amount, then Dash logo, then "~ fiat" —
            // the whole balance row is text/secondary.
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = dashBalance,
                    style = MyTheme.Typography.BodyMedium,
                    color = MyTheme.Colors.textSecondary
                )
                Image(
                    painter = painterResource(R.drawable.ic_dash_d_gray),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(14.dp)
                )
                if (fiatBalance != null) {
                    Text(
                        text = "~ $fiatBalance",
                        style = MyTheme.Typography.BodyMedium,
                        color = MyTheme.Colors.textSecondary
                    )
                }
            }
        } else {
            Text(
                text = hiddenPlaceholder,
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textSecondary,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        // Per Figma: spacing/8 between the balance text and the eye chip.
        Spacer(modifier = Modifier.width(8.dp))

        // Eye toggle as a btn-xs chip — rounded-12 with subtle dark tint, per Figma.
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MyTheme.Colors.primary5)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggleClick
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    if (isVisible) R.drawable.ic_show else R.drawable.ic_hide
                ),
                contentDescription = stringResource(
                    if (isVisible) R.string.hide_balance else R.string.show_balance
                ),
                tint = MyTheme.Colors.textSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
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
            dashBalance = "1.23456789",
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
            dashBalance = "1.23456789",
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
            dashBalance = "0.50000000"
        )
    }
}

@Preview(showBackground = true, widthDp = 393)
@Composable
private fun TopIntroSendIconPreview() {
    Column(
        modifier = Modifier
            .background(MyTheme.Colors.backgroundPrimary)
            .padding(vertical = 8.dp)
    ) {
        TopIntroSend(
            heading = "Buy gift card",
            preposition = "at",
            toIcon = painterResource(R.drawable.ic_dash_blue_filled),
            toName = "Amazon",
            dashBalance = "1.23456789",
            fiatBalance = "USD 45.67"
        )
    }
}