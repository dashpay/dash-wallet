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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBackTitle
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.common.R as CommonR

/**
 * A pre-formatted amount for the order breakdown. When [isDash] is true the screen renders the
 * Dash D glyph next to [text] (in the locale's currency-symbol position) instead of a code.
 */
data class AmountDisplay(
    val text: String = "",
    val isDash: Boolean = false
)

/**
 * UI state for the Maya order-preview screen (Figma node 24021:11223). All amounts arrive
 * pre-formatted by the host fragment, which keeps the original breakdown logic (purchase and
 * fee shown in DASH or fiat depending on how the amount was entered).
 */
data class MayaConversionPreviewUIState(
    /** "From" coin (always Dash): name, code, icon and the sold amount + its fiat value. */
    val fromName: String = "",
    val fromCode: String = "",
    val fromIconUrl: String? = null,
    val fromDashAmount: String = "",
    val fromFiatAmount: String = "",
    /** "To" coin: name, code, icon and the expected received amount ("0.0053 BTC"). */
    val toName: String = "",
    val toCode: String = "",
    val toIconUrl: String? = null,
    val toAmount: String = "",
    /** Destination address row, e.g. "Bitcoin Address" / "bc1q…". */
    val addressLabel: String = "",
    val address: String = "",
    val purchaseAmount: AmountDisplay = AmountDisplay(),
    val feeAmount: AmountDisplay = AmountDisplay(),
    val totalAmount: AmountDisplay = AmountDisplay(),
    /** Whether the Dash D glyph (or a fiat symbol) renders before the number in this locale. */
    val symbolFirst: Boolean = true,
    /** Route label shown in the Network row, e.g. "Maya" or "NEAR". */
    val networkName: String = "",
    /** Slippage disclosure below the card. */
    val slippageNotice: String = "",
    /** Route diagnostics, debug builds only; null hides the block. */
    val debugRouteInfo: String? = null,
    /**
     * Seconds left before the quote expires — shown in the Confirm button label ("Confirm (10s)").
     * Null means the quote has expired and the button becomes Refresh.
     */
    val quoteSecondsLeft: Long? = null,
    /** True while the swap order is being committed (or a fresh quote fetched). */
    val isLoading: Boolean = false,
    val isOnline: Boolean = true
)

@Composable
fun MayaConversionPreviewScreen(
    state: MayaConversionPreviewUIState,
    onBackClick: () -> Unit,
    onCancelClick: () -> Unit,
    onConfirmClick: () -> Unit,
    onFeeInfoClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalDashColors.current.backgroundPrimary)
    ) {
        NavBarBackTitle(
            title = stringResource(R.string.maya_crypto_convert_order_preview_title),
            onBackClick = onBackClick
        )

        if (!state.isOnline) {
            // Full-screen network-unavailable state (replaces the old view stub); the action
            // buttons are hidden until connectivity returns, like the view-based screen.
            NetworkUnavailable(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp)
            ) {
                // Order card (Figma `menu`): from/to coins, address, purchase/fee/network/total.
                Menu {
                    FromRow(state)
                    ToRow(state)
                    LabelValueRow(label = state.addressLabel, value = state.address)
                    LabelValueRow(label = stringResource(R.string.purchase)) {
                        AmountValue(state.purchaseAmount, state.symbolFirst, MyTheme.Body2Regular)
                    }
                    FeeRow(state, onFeeInfoClick)
                    LabelValueRow(label = stringResource(R.string.network_label), value = state.networkName)
                    TotalRow(state)
                }

                Text(
                    text = state.slippageNotice,
                    style = MyTheme.Typography.BodySmall,
                    color = LocalDashColors.current.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .padding(horizontal = 36.dp)
                )

                // Route diagnostics, debug builds only.
                if (state.debugRouteInfo != null) {
                    Text(
                        text = state.debugRouteInfo,
                        style = MyTheme.Typography.BodySmall,
                        color = LocalDashColors.current.textTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .padding(horizontal = 20.dp)
                    )
                }
            }

            // Cancel / Confirm pinned at the bottom (Figma: btn-l plain black + btn-l filled-blue).
            // The Confirm label carries the quote countdown; when it runs out the button turns
            // into a tinted Refresh that fetches a new quote.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DashButton(
                    text = stringResource(CommonR.string.button_cancel),
                    style = Style.PlainBlack,
                    size = Size.Large,
                    isEnabled = !state.isLoading,
                    onClick = onCancelClick,
                    modifier = Modifier.weight(1f)
                )

                DashButton(
                    text = if (state.quoteSecondsLeft != null) {
                        stringResource(R.string.maya_confirm_countdown, state.quoteSecondsLeft)
                    } else {
                        stringResource(R.string.button_refresh)
                    },
                    style = if (state.quoteSecondsLeft != null) Style.FilledBlue else Style.TintedBlue,
                    size = Size.Large,
                    isEnabled = !state.isLoading,
                    isLoading = state.isLoading,
                    onClick = onConfirmClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** "From Dash Wallet on this device" caption + Dash coin row with the sold amount and fiat value. */
@Composable
private fun FromRow(state: MayaConversionPreviewUIState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(CommonR.string.from_dash_wallet_on_this_device),
            style = MyTheme.Typography.Footnote,
            color = LocalDashColors.current.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .padding(horizontal = 10.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoinBadge(
                name = state.fromName,
                code = state.fromCode,
                iconUrl = state.fromIconUrl,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painter = painterResource(CommonR.drawable.ic_dash_d_black),
                        contentDescription = null,
                        tint = LocalDashColors.current.textPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = state.fromDashAmount,
                        style = MyTheme.Typography.Footnote,
                        color = LocalDashColors.current.textPrimary
                    )
                }
                Text(
                    text = state.fromFiatAmount,
                    style = MyTheme.Overline,
                    color = LocalDashColors.current.textSecondary
                )
            }
        }
    }
}

/** "To" caption + destination coin row with the expected received amount. */
@Composable
private fun ToRow(state: MayaConversionPreviewUIState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.to_title),
            style = MyTheme.Body2Regular,
            color = LocalDashColors.current.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .padding(horizontal = 10.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            CoinBadge(name = state.toName, code = state.toCode, iconUrl = state.toIconUrl)
            Text(
                text = state.toAmount,
                style = MyTheme.Body2Regular,
                color = LocalDashColors.current.textPrimary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Coin icon + name over code, as in the Figma from/to rows. */
@Composable
private fun CoinBadge(
    name: String,
    code: String,
    iconUrl: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            placeholder = painterResource(CommonR.drawable.ic_default_flag),
            error = painterResource(CommonR.drawable.ic_default_flag),
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
        )
        Column {
            Text(
                text = name,
                style = MyTheme.Typography.FootnoteMedium,
                color = LocalDashColors.current.textPrimary
            )
            Text(
                text = code,
                style = MyTheme.Overline,
                color = LocalDashColors.current.textTertiary
            )
        }
    }
}

/** Generic label/value row (Figma `List1`): medium tertiary label, regular right-aligned value. */
@Composable
private fun LabelValueRow(
    label: String,
    value: String? = null,
    valueContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 46.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = label,
            style = MyTheme.Body2Medium,
            color = LocalDashColors.current.textTertiary
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
            if (valueContent != null) {
                valueContent()
            } else {
                Text(
                    text = value.orEmpty(),
                    style = MyTheme.Body2Regular,
                    color = LocalDashColors.current.textPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Fee row with the tappable info icon opening the fee-info screen. */
@Composable
private fun FeeRow(state: MayaConversionPreviewUIState, onFeeInfoClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 46.dp)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clickable(onClick = onFeeInfoClick)
        ) {
            Text(
                text = stringResource(R.string.maya_fee),
                style = MyTheme.Body2Medium,
                color = LocalDashColors.current.textTertiary
            )
            Icon(
                painter = painterResource(R.drawable.ic_dash_info_blue_meduim),
                contentDescription = null,
                tint = LocalDashColors.current.gray,
                modifier = Modifier.size(15.dp)
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            AmountValue(state.feeAmount, state.symbolFirst, MyTheme.Body2Regular)
        }
    }
}

/** Total row (Figma `List11`): medium label + larger medium value. */
@Composable
private fun TotalRow(state: MayaConversionPreviewUIState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = stringResource(R.string.total),
            style = MyTheme.Body2Medium,
            color = LocalDashColors.current.textTertiary
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            AmountValue(state.totalAmount, state.symbolFirst, MyTheme.Typography.BodyLargeMedium)
        }
    }
}

/**
 * Renders a pre-formatted amount; DASH amounts get the Dash D glyph in the locale's
 * currency-symbol position (the Compose counterpart of the old ImageSpan rendering).
 */
@Composable
private fun AmountValue(amount: AmountDisplay, symbolFirst: Boolean, style: TextStyle) {
    if (amount.isDash) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (symbolFirst) {
                DashGlyph()
            }
            Text(text = amount.text, style = style, color = LocalDashColors.current.textPrimary)
            if (!symbolFirst) {
                DashGlyph()
            }
        }
    } else {
        Text(text = amount.text, style = style, color = LocalDashColors.current.textPrimary)
    }
}

@Composable
private fun DashGlyph() {
    Icon(
        painter = painterResource(CommonR.drawable.ic_dash_d_black),
        contentDescription = null,
        tint = LocalDashColors.current.textPrimary,
        modifier = Modifier.size(12.dp)
    )
}

/** Compose counterpart of the old network_unavailable_view stub. */
@Composable
private fun NetworkUnavailable(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(CommonR.drawable.ic_network_unavailable),
            contentDescription = null
        )
        Text(
            text = stringResource(CommonR.string.network_unavailable_title),
            style = MyTheme.H6Bold,
            color = LocalDashColors.current.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .padding(horizontal = 20.dp)
        )
        Text(
            text = stringResource(CommonR.string.network_unavailable_check_connection),
            style = MyTheme.Body2Regular,
            color = LocalDashColors.current.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
                .padding(horizontal = 20.dp)
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────────────

private val previewState = MayaConversionPreviewUIState(
    fromName = "Dash",
    fromCode = "DASH",
    fromDashAmount = "0.1",
    fromFiatAmount = "$100",
    toName = "Bitcoin",
    toCode = "BTC",
    toAmount = "0.0053 BTC",
    addressLabel = "Bitcoin Address",
    address = "XbBzWvnvSyWFbYXFtjkWwuPApbfDD263uC",
    purchaseAmount = AmountDisplay("0.1", isDash = true),
    feeAmount = AmountDisplay("0.0001", isDash = true),
    totalAmount = AmountDisplay("0.1001", isDash = true),
    networkName = "Maya",
    slippageNotice = "The final amount you receive may differ from this estimate by up to 2% due to price slippage.",
    quoteSecondsLeft = 10
)

@Preview(showBackground = true, widthDp = 393, heightDp = 800)
@Composable
private fun MayaConversionPreviewScreenPreview() {
    MayaConversionPreviewScreen(
        state = previewState,
        onBackClick = {},
        onCancelClick = {},
        onConfirmClick = {},
        onFeeInfoClick = {}
    )
}

/** Quote expired — the Confirm button becomes a tinted Refresh. */
@Preview(showBackground = true, widthDp = 393, heightDp = 800)
@Composable
private fun MayaConversionPreviewScreenExpiredPreview() {
    MayaConversionPreviewScreen(
        state = previewState.copy(quoteSecondsLeft = null),
        onBackClick = {},
        onCancelClick = {},
        onConfirmClick = {},
        onFeeInfoClick = {}
    )
}

/** Committing the order — buttons disabled, spinner in Confirm. */
@Preview(showBackground = true, widthDp = 393, heightDp = 800)
@Composable
private fun MayaConversionPreviewScreenLoadingPreview() {
    MayaConversionPreviewScreen(
        state = previewState.copy(quoteSecondsLeft = 8, isLoading = true),
        onBackClick = {},
        onCancelClick = {},
        onConfirmClick = {},
        onFeeInfoClick = {}
    )
}

/** Offline — full-screen network-unavailable state, no action buttons. */
@Preview(showBackground = true, widthDp = 393, heightDp = 800)
@Composable
private fun MayaConversionPreviewScreenOfflinePreview() {
    MayaConversionPreviewScreen(
        state = previewState.copy(isOnline = false),
        onBackClick = {},
        onCancelClick = {},
        onConfirmClick = {},
        onFeeInfoClick = {}
    )
}
