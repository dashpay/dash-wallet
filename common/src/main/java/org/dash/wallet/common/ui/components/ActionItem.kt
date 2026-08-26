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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

/**
 * Opacity of a disabled [ActionItem]. The design shows the disabled row dimmed as a whole — icon,
 * title and subtitle alike — and a whole-row alpha is the only treatment that also covers the
 * icon, since those are full-colour exchange/currency logos that a content token such as
 * `contentDisabled` cannot recolour.
 */
private const val DISABLED_ROW_ALPHA = 0.4f

/**
 * Design-system "Action item" row (Figma node 8485:2778 in the Design-system file): a compact
 * (50dp min height) slot-based row, lighter than [MenuItem]'s 56dp settings row.
 *
 * Slot geometry from the Figma symbol:
 * - leading slot: 30dp, inset 12dp from the start;
 * - central slot: fills the remaining width, content vertically centred, 10dp gap from the
 *   neighbouring slots plus the 6dp start inset that the `Action item / Var` central components
 *   carry internally;
 * - trailing slot: inset 14dp from the end (14dp template icon, or a small [DashButton]).
 *
 * Central-part variants covered:
 * - `Action item / Var 1`: [title] only — `New/Text/Subhead (medium)` (15/20) in text/primary,
 *   i.e. [MyTheme.Typography.SubheadMedium];
 * - `Action item / Var 3` (per app usage, Figma 39439:35111): [title] plus a one-line [subtitle]
 *   (e.g. an exchange deposit address) that can be middle-ellipsized via [subtitleMiddleEllipsis]
 *   so both ends of an address stay checkable.
 */
@Composable
fun ActionItem(
    title: String,
    modifier: Modifier = Modifier,
    /**
     * When false the row is dimmed as a whole and nothing in it is tappable: [onClick] and
     * [onTrailingButtonClick] are both suppressed and the row no longer reports itself as a
     * button to accessibility services.
     */
    enabled: Boolean = true,
    /** One-line secondary text under [title] (`Action item / Var 3`); null renders `Var 1`. */
    subtitle: String? = null,
    /**
     * Truncates [subtitle] from the middle to fit the available width (e.g. for addresses,
     * where both the start and end need to stay checkable) instead of the standard end-ellipsis.
     * Width-measured so it stays correct at any font scale, unlike a fixed character count.
     */
    subtitleMiddleEllipsis: Boolean = false,
    /** Leading-slot drawable, rendered at the slot's 30dp size. */
    @DrawableRes icon: Int? = null,
    /** Custom leading slot (e.g. a Coil AsyncImage for coin logos); used when [icon] is null. */
    leadingContent: (@Composable () -> Unit)? = null,
    /** Small trailing [DashButton] label (e.g. "Log in"); shown when [onTrailingButtonClick] is set. */
    trailingButtonText: String? = null,
    trailingButtonStyle: Style = Style.Plain,
    onTrailingButtonClick: (() -> Unit)? = null,
    /** Trailing 14dp chevron, matching the symbol's trailing template size. */
    showChevron: Boolean = false,
    /** Custom trailing slot; used when neither the trailing button nor the chevron is requested. */
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val colors = LocalDashColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ROW_ALPHA))
            .clip(RoundedCornerShape(16.dp))
            .then(if (enabled && onClick != null) Modifier.clickable { onClick() } else Modifier)
            .semantics { if (enabled && onClick != null) role = Role.Button }
            .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Leading slot: 30dp
        if (icon != null || leadingContent != null) {
            Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                if (icon != null) {
                    Image(
                        painter = painterResource(id = icon),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp)
                    )
                } else {
                    leadingContent?.invoke()
                }
            }
        }

        // Central slot: the `Action item / Var` components carry a 6dp start inset internally
        // (16dp total from the leading icon, together with the 10dp slot gap).
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MyTheme.Typography.SubheadMedium,
                color = colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )

            subtitle?.let {
                if (subtitleMiddleEllipsis) {
                    MiddleEllipsisText(
                        text = it,
                        style = MyTheme.Typography.Footnote,
                        color = colors.textSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = it,
                        style = MyTheme.Typography.Footnote,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Trailing slot
        if (trailingButtonText != null && onTrailingButtonClick != null) {
            DashButton(
                onClick = onTrailingButtonClick,
                text = trailingButtonText,
                style = trailingButtonStyle,
                size = Size.Small,
                stretch = false,
                isEnabled = enabled
            )
        } else if (showChevron) {
            Icon(
                painter = painterResource(id = R.drawable.ic_menu_row_arrow),
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(14.dp)
            )
        } else {
            trailingContent?.invoke()
        }
    }
}

/**
 * Single-line text that keeps the start and end of [text] visible, truncating the middle
 * with "…" only as much as needed to fit the measured width. Unlike a fixed character-count
 * cut, this stays correct across screen widths, locales and font scales.
 *
 * Same approach as MenuItem's private helper; duplicated here so [ActionItem] stays
 * independent of [MenuItem].
 */
@Composable
private fun MiddleEllipsisText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val display = remember(text, maxWidthPx, style) {
            middleEllipsizeToFit(text, maxWidthPx, style, measurer)
        }
        Text(text = display, style = style, color = color, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

private fun middleEllipsizeToFit(text: String, maxWidthPx: Float, style: TextStyle, measurer: TextMeasurer): String {
    fun widthOf(s: String) = measurer.measure(text = s, style = style, softWrap = false).size.width

    if (maxWidthPx <= 0f || widthOf(text) <= maxWidthPx) return text

    var head = (text.length + 1) / 2
    var tail = text.length - head
    while (head + tail > 1) {
        val candidate = "${text.take(head)}…${text.takeLast(tail)}"
        if (widthOf(candidate) <= maxWidthPx) return candidate
        if (head >= tail) head-- else tail--
    }
    return "…"
}

@Preview(name = "ActionItem Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "ActionItem Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ActionItemPreview() {
    DashWalletTheme {
        Column(Modifier.padding(vertical = 16.dp)) {
            Menu {
                // Var 1: title only, tappable with a chevron
                ActionItem(
                    title = "Title",
                    icon = R.drawable.ic_dash_blue_filled,
                    showChevron = true,
                    onClick = { }
                )

                // Var 3: title + middle-ellipsized address subtitle
                ActionItem(
                    title = "Uphold",
                    subtitle = "XsQwPTRMtjzJmccAzYcCzNVbG1UsBGffNc",
                    subtitleMiddleEllipsis = true,
                    icon = R.drawable.ic_dash_blue_filled,
                    onClick = { }
                )

                // Var 1 with a small trailing button
                ActionItem(
                    title = "Coinbase",
                    icon = R.drawable.ic_dash_blue_filled,
                    trailingButtonText = "Log in",
                    onTrailingButtonClick = { },
                    onClick = { }
                )

                // Disabled row: dimmed as a whole and not tappable
                ActionItem(
                    title = "Coinbase",
                    icon = R.drawable.ic_dash_blue_filled,
                    enabled = false,
                    onClick = { }
                )
            }
        }
    }
}
