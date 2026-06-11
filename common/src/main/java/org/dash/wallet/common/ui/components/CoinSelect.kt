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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Visual/interaction state of a [CoinSelect] row (DashDEX "Coin select" design system).
 */
enum class CoinSelectState {
    /** Selectable. Full-colour icon; shows the trailing price/network block. */
    Active,

    /** Trading is halted for this chain — not selectable. Desaturated, with a "halted" badge. */
    HaltedChain,

    /** Not selectable for another reason. Desaturated, no trailing block. */
    Disabled
}

/**
 * A single selectable coin row used on the DashDEX "Select coin" screen.
 * Implements the design-system component (Figma node `7872:1756`) and its four states
 * (`7872:1765`): Active (single / multiple network), Halted chain, and Disabled.
 *
 * Non-active states are rendered exactly as specified by the design: a black saturation
 * overlay desaturates the whole row (so the colour logo turns grey), the logo drops to 50%
 * opacity, and the name/symbol switch to the tertiary text colour. Halted additionally shows
 * a "halted" badge; Disabled shows no trailing content. Only [CoinSelectState.Active] rows
 * are clickable.
 *
 * @param coinIcon slot for the coin logo (e.g. a Coil `AsyncImage`); sized to 30dp.
 * @param price trailing price text, shown only in the Active state.
 * @param network trailing network label (e.g. "NEAR", "Multiple"), shown only in the Active state.
 * @param haltedLabel text of the badge shown in the [CoinSelectState.HaltedChain] state.
 */
@Composable
fun CoinSelect(
    name: String,
    symbol: String,
    modifier: Modifier = Modifier,
    coinIcon: @Composable () -> Unit = { CoinSelectPlaceholderIcon() },
    state: CoinSelectState = CoinSelectState.Active,
    price: String? = null,
    network: String? = null,
    haltedLabel: String = "halted",
    onClick: (() -> Unit)? = null
) {
    val isGreyed = state != CoinSelectState.Active
    val nameColor = if (isGreyed) MyTheme.Colors.textTertiary else MyTheme.Colors.textPrimary
    val symbolColor = if (isGreyed) MyTheme.Colors.textTertiary else MyTheme.Colors.textSecondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (state == CoinSelectState.Active && onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            )
            // mix-blend-saturation with a black source desaturates everything below it,
            // turning the colour logo grey for the non-selectable states. Offscreen
            // compositing isolates the blend to this row's content.
            .then(if (isGreyed) Modifier.desaturate() else Modifier)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .then(if (isGreyed) Modifier.alpha(0.5f) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            coinIcon()
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = name,
                style = MyTheme.Typography.BodyMediumMedium,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = symbol,
                style = MyTheme.Typography.BodySmall,
                color = symbolColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        when (state) {
            CoinSelectState.Active -> {
                if (price != null || network != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        price?.let {
                            Text(
                                text = it,
                                style = MyTheme.Typography.BodyMedium,
                                color = MyTheme.Colors.textPrimary
                            )
                        }
                        network?.let {
                            Text(
                                text = it,
                                style = MyTheme.Typography.BodySmall,
                                color = MyTheme.Colors.textSecondary
                            )
                        }
                    }
                }
            }

            CoinSelectState.HaltedChain -> CoinSelectBadge(haltedLabel)

            CoinSelectState.Disabled -> Unit
        }
    }
}

/** The small pill shown in the halted state (black-8% background, secondary text). */
@Composable
private fun CoinSelectBadge(label: String) {
    Text(
        text = label,
        style = MyTheme.Typography.BodySmallMedium,
        color = MyTheme.Colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0A0B0D).copy(alpha = 0.08f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Neutral 30dp placeholder used when no [coinIcon] is supplied. */
@Composable
fun CoinSelectPlaceholderIcon() {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MyTheme.Colors.lightGray)
    )
}

/**
 * Desaturate this content (the design's black `mix-blend-saturation` overlay). Implemented
 * as a saturation-0 colour matrix applied to an offscreen layer, so only drawn pixels (the
 * colour logo, text) turn grey — transparent areas stay transparent rather than going black.
 */
private fun Modifier.desaturate(): Modifier = this.drawWithCache {
    val paint = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    onDrawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), paint)
            drawContent()
            canvas.restore()
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CoinSelectStatesPreview() {
    Column(
        modifier = Modifier
            .background(MyTheme.Colors.backgroundSecondary)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CoinSelectStateLabel("Active — single network")
        CoinSelect(
            name = "Binance coin",
            symbol = "BNB",
            coinIcon = { PreviewCoinIcon() },
            state = CoinSelectState.Active,
            price = "$0.00",
            network = "NEAR",
            onClick = {}
        )

        CoinSelectStateLabel("Active — multiple networks")
        CoinSelect(
            name = "Binance coin",
            symbol = "BNB",
            coinIcon = { PreviewCoinIcon() },
            state = CoinSelectState.Active,
            price = "$0.00",
            network = "Multiple",
            onClick = {}
        )

        CoinSelectStateLabel("Halted chain")
        CoinSelect(
            name = "Binance coin",
            symbol = "BNB",
            coinIcon = { PreviewCoinIcon() },
            state = CoinSelectState.HaltedChain,
            haltedLabel = "halted"
        )

        CoinSelectStateLabel("Disabled")
        CoinSelect(
            name = "Binance coin",
            symbol = "BNB",
            coinIcon = { PreviewCoinIcon() },
            state = CoinSelectState.Disabled
        )
    }
}

@Composable
private fun CoinSelectStateLabel(text: String) {
    Text(
        text = text,
        style = MyTheme.Typography.BodySmall,
        color = MyTheme.Colors.textTertiary,
        modifier = Modifier.padding(top = 8.dp, start = 10.dp)
    )
}

/** Colour preview icon so the desaturation in the non-active states is visible. */
@Composable
private fun PreviewCoinIcon() {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0xFFF3BA2F))
    )
}