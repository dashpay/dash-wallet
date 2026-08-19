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

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

/**
 * Tint of a [SystemMessage] card. Named after the Figma `system message/bg-*` tokens rather than
 * after a severity, because that is how the design system labels them.
 *
 * Every background is deliberately **translucent** so the card tints whatever surface it sits on
 * instead of forcing a light one: over a white card [Yellow] reads as `#FFF9ED`, over the dark
 * `Black800` card as a dark amber, which is what keeps `textPrimary` legible in both themes.
 */
enum class SystemMessageStyle {
    /** Figma `system message/bg-blue` (Dash blue @5%) — the component's default. */
    Blue,

    /** Warning tint (yellow @10%), used for expiry / unsupported-network messages. */
    Yellow
}

/**
 * Figma `SystemMessage` (design system node 8378:445): an inline, non-elevated notice made of an
 * icon, an optional title, an optional description and up to two small buttons.
 *
 * Not to be confused with [InfoPanel], which is an elevated card on `backgroundSecondary`.
 *
 * Every Figma variant is reachable through the parameters:
 * - `title` / `description` — Figma's `title` and `description` booleans; pass `null` to hide either
 * - `primaryButtonText` — Figma's `buttons` boolean
 * - `secondaryButtonText` — Figma's `secondaryButton` boolean
 * - `iconRes` / `icon` — Figma's swappable `icon` instance (the default is a placeholder template)
 *
 * @param modifier applied to the root row. Use it for call-site insets, e.g. when the card sits
 * inside a [Menu] and has to be inset from the card edge.
 */
@Composable
fun SystemMessage(
    title: String? = null,
    description: String? = null,
    modifier: Modifier = Modifier,
    style: SystemMessageStyle = SystemMessageStyle.Blue,
    @DrawableRes iconRes: Int? = null,
    icon: (@Composable () -> Unit)? = null,
    primaryButtonText: String? = null,
    onPrimaryButtonClick: (() -> Unit)? = null,
    secondaryButtonText: String? = null,
    onSecondaryButtonClick: (() -> Unit)? = null
) {
    val colors = LocalDashColors.current
    val background = when (style) {
        // Exactly Figma's `system message/bg-blue` (#008DE4 @5%).
        SystemMessageStyle.Blue -> colors.dashBlue5
        SystemMessageStyle.Yellow -> colors.warningYellow
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RADIUS))
            .background(background)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (iconRes != null || icon != null) {
            Box(
                modifier = Modifier.size(30.dp),
                contentAlignment = Alignment.Center
            ) {
                if (iconRes != null) {
                    Image(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    icon?.invoke()
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (title != null || description != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                ) {
                    title?.let {
                        Text(
                            text = it,
                            // Figma: Subhead (medium), 15/20
                            style = MyTheme.Typography.SubheadMedium,
                            color = colors.textPrimary
                        )
                    }

                    description?.let {
                        Text(
                            text = it,
                            // Figma: Footnote (regular), 13/18
                            style = MyTheme.Typography.Footnote,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            if (primaryButtonText != null && onPrimaryButtonClick != null) {
                Row(
                    modifier = Modifier.padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DashButton(
                        text = primaryButtonText,
                        style = Style.FilledBlue,
                        size = Size.Small,
                        stretch = false,
                        onClick = onPrimaryButtonClick
                    )

                    if (secondaryButtonText != null && onSecondaryButtonClick != null) {
                        DashButton(
                            text = secondaryButtonText,
                            style = Style.TintedBlue,
                            size = Size.Small,
                            stretch = false,
                            onClick = onSecondaryButtonClick
                        )
                    }
                }
            }
        }
    }
}

/** Figma `system message/radius`. */
private val RADIUS = 20.dp

@Preview(name = "System Message Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "System Message Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SystemMessagePreview() {
    DashWalletTheme {
        val colors = LocalDashColors.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.backgroundPrimary)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title + description + both buttons (the Figma default)
            SystemMessage(
                title = "Title",
                description = "Description",
                iconRes = R.drawable.ic_dash_blue_filled,
                primaryButtonText = "Label",
                onPrimaryButtonClick = { },
                secondaryButtonText = "Label",
                onSecondaryButtonClick = { }
            )

            // Primary button only
            SystemMessage(
                title = "Title",
                description = "Description",
                iconRes = R.drawable.ic_dash_blue_filled,
                primaryButtonText = "Label",
                onPrimaryButtonClick = { }
            )

            // Title + description, no buttons
            SystemMessage(
                title = "Title",
                description = "Description",
                iconRes = R.drawable.ic_dash_blue_filled
            )

            // Title only
            SystemMessage(
                title = "Title",
                iconRes = R.drawable.ic_dash_blue_filled
            )

            // Description only, on a card surface, as the warning variant
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.backgroundSecondary, RoundedCornerShape(20.dp))
                    .padding(6.dp)
            ) {
                SystemMessage(
                    description = "Coinbase doesn't support USDC on the TRON network",
                    style = SystemMessageStyle.Yellow,
                    iconRes = R.drawable.ic_warning_triangle
                )
            }

            // Warning variant with a title and a custom icon slot
            SystemMessage(
                title = "This address will expire",
                description = "Send BTC within 15 minutes, otherwise the deposit address expires.",
                style = SystemMessageStyle.Yellow,
                icon = {
                    Image(
                        painter = painterResource(id = R.drawable.ic_warning_triangle),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            // No icon at all
            SystemMessage(
                title = "Title",
                description = "Description"
            )
        }
    }
}
