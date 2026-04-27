/*
 * Copyright 2025 Dash Core Group.
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Base navigation bar component (NavBar in Figma design system).
 *
 * Supports all design-system variants via the named convenience functions below.
 * Use [TopNavBase] for full control; prefer the named variants for readability.
 *
 * Layout: 64 dp tall, horizontal padding 20 dp.
 * - Leading area: icon button (34 dp circle) or text action — anchored to start.
 * - Central title: absolutely centred on the bar (225 dp wide).
 * - Trailing area: icon button (34 dp circle), bare icon, or text action — anchored to end.
 *
 * @param leadingPart       Show leading area at all (default true; ignored when [leadingText] set).
 * @param leadingIcon       ImageVector for a circle-bordered icon button on the left.
 * @param leadingText       Text for a plain-text action button on the left.
 * @param onLeadingClick    Click handler for leading area.
 * @param trailingPart      Show trailing area at all (default true; ignored when [trailingText] set).
 * @param trailingIcon      ImageVector for an icon button on the right.
 * @param trailingIconCircle When true (default) wraps [trailingIcon] in a 34 dp bordered circle
 *                          ([Template]). Set false for a bare 22 dp icon (info-style).
 * @param trailingText      Text for a blue action button on the right.
 * @param onTrailingClick   Click handler for trailing area.
 * @param centralPart       Show centred title (default true).
 * @param title             Title string shown in the centre.
 */
@Composable
fun TopNavBase(
    modifier: Modifier = Modifier,
    leadingPart: Boolean = true,
    leadingIcon: ImageVector? = null,
    leadingText: String? = null,
    leadingContentDescription: String? = null,
    onLeadingClick: (() -> Unit)? = null,
    trailingPart: Boolean = true,
    trailingIcon: ImageVector? = null,
    trailingIconCircle: Boolean = true,
    trailingText: String? = null,
    trailingContentDescription: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    centralPart: Boolean = true,
    title: String = "Label"
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 20.dp)
    ) {
        // ── Leading ───────────────────────────────────────────────────────
        when {
            leadingText != null -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .height(44.dp)
                        .then(if (onLeadingClick != null) Modifier.clickable { onLeadingClick() } else Modifier),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = leadingText,
                        style = MyTheme.CaptionMedium,
                        color = MyTheme.Colors.textPrimary
                    )
                }
            }
            leadingPart && leadingIcon != null -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Template(
                        modifier = Modifier
                            .size(34.dp)
                            .then(if (onLeadingClick != null) Modifier.clickable { onLeadingClick() } else Modifier),
                        icon = leadingIcon,
                        contentDescription = leadingContentDescription
                    )
                }
            }
        }

        // ── Central title (absolutely centred) ────────────────────────────
        if (centralPart) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(225.dp),
                contentAlignment = Alignment.Center
            ) {
                Label(text = title)
            }
        }

        // ── Trailing ──────────────────────────────────────────────────────
        when {
            trailingText != null -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .height(44.dp)
                        .then(if (onTrailingClick != null) Modifier.clickable { onTrailingClick() } else Modifier),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = trailingText,
                        style = MyTheme.CaptionMedium,
                        color = MyTheme.Colors.dashBlue
                    )
                }
            }
            trailingPart && trailingIcon != null -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (trailingIconCircle) {
                        Template(
                            modifier = Modifier
                                .size(34.dp)
                                .then(if (onTrailingClick != null) Modifier.clickable { onTrailingClick() } else Modifier),
                            icon = trailingIcon,
                            contentDescription = trailingContentDescription
                        )
                    } else {
                        // Bare icon — no circle border (e.g. info icon)
                        Icon(
                            imageVector = trailingIcon,
                            contentDescription = trailingContentDescription,
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .size(22.dp)
                                .then(if (onTrailingClick != null) Modifier.clickable { onTrailingClick() } else Modifier)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NavBar(
    modifier: Modifier = Modifier,
    leadingPart: Boolean = true,
    leadingIcon: ImageVector? = null,
    leadingText: String? = null,
    leadingContentDescription: String? = null,
    onLeadingClick: (() -> Unit)? = null,
    trailingPart: Boolean = true,
    trailingIcon: ImageVector? = null,
    trailingIconCircle: Boolean = true,
    trailingText: String? = null,
    trailingContentDescription: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    centralPart: Boolean = true,
    title: String = "Label"
) {
    TopNavBase(
        modifier, leadingPart, leadingIcon, leadingText, leadingContentDescription,
        onLeadingClick, trailingPart, trailingIcon, trailingIconCircle,
        trailingText, trailingContentDescription, onTrailingClick, centralPart,
        title
    )
}

// ── Named variant functions ────────────────────────────────────────────────────
// Each maps to a named Figma component in the NavBar playground.

/** NavBarBack — back chevron only, no title. */
@Composable
fun NavBarBack(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingIcon = MyImages.MenuChevron,
        onLeadingClick = onBackClick,
        centralPart = false,
        trailingPart = false
    )
}

/** NavBarBack — back chevron only, no title. */
@Composable
fun NavBarBackClose(
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingIcon = MyImages.MenuChevron,
        onLeadingClick = onBackClick,
        centralPart = false,
        trailingPart = true,
        trailingIcon = MyImages.NavBarClose,
        onTrailingClick = onCloseClick
    )
}

/** NavBarBackTitle — back chevron + centred title. */
@Composable
fun NavBarBackTitle(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingIcon = MyImages.MenuChevron,
        onLeadingClick = onBackClick,
        trailingPart = false,
        title = title
    )
}

/** NavBarBackTitleInfo — back chevron + centred title + bare info icon. */
@Composable
fun NavBarBackTitleInfo(
    title: String,
    onBackClick: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingIcon = MyImages.MenuChevron,
        onLeadingClick = onBackClick,
        trailingIcon = MyImages.NavBarInfo,
        trailingIconCircle = false,
        onTrailingClick = onInfoClick,
        title = title
    )
}

/** NavBarTitleClose — centred title + circle-bordered close button. */
@Composable
fun NavBarTitleClose(
    title: String,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingPart = false,
        trailingIcon = MyImages.NavBarClose,
        onTrailingClick = onCloseClick,
        title = title
    )
}

/** NavBarBackTitlePlus — back chevron + centred title + circle-bordered plus button. */
@Composable
fun NavBarBackTitlePlus(
    title: String,
    onBackClick: () -> Unit,
    onPlusClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingIcon = MyImages.MenuChevron,
        onLeadingClick = onBackClick,
        trailingIcon = Icons.Default.Add,
        onTrailingClick = onPlusClick,
        title = title
    )
}

/** NavBarBackPlus — back chevron + circle-bordered plus button, no title. */
@Composable
fun NavBarBackPlus(
    onBackClick: () -> Unit,
    onPlusClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingIcon = MyImages.MenuChevron,
        onLeadingClick = onBackClick,
        centralPart = false,
        trailingIcon = Icons.Default.Add,
        onTrailingClick = onPlusClick
    )
}

/** NavBarTitle — centred title only. */
@Composable
fun NavBarTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingPart = false,
        trailingPart = false,
        title = title
    )
}

/** NavBarClose — circle-bordered close button only, no title. */
@Composable
fun NavBarClose(
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingPart = false,
        centralPart = false,
        trailingIcon = MyImages.NavBarClose,
        onTrailingClick = onCloseClick
    )
}

/** NavBarActionTitleAction — plain text action (left) + centred title + blue text action (right). */
@Composable
fun NavBarActionTitleAction(
    title: String,
    leadingActionText: String,
    onLeadingActionClick: () -> Unit,
    trailingActionText: String,
    onTrailingActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingText = leadingActionText,
        onLeadingClick = onLeadingActionClick,
        trailingText = trailingActionText,
        onTrailingClick = onTrailingActionClick,
        title = title
    )
}

/** NavBarBackTitleAction — back chevron + centred title + blue text action. */
@Composable
fun NavBarBackTitleAction(
    title: String,
    onBackClick: () -> Unit,
    actionText: String,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingIcon = MyImages.MenuChevron,
        onLeadingClick = onBackClick,
        trailingText = actionText,
        onTrailingClick = onActionClick,
        title = title
    )
}

/** NavBarBackAction — back chevron + blue text action, no title. */
@Composable
fun NavBarBackAction(
    onBackClick: () -> Unit,
    actionText: String,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopNavBase(
        modifier = modifier,
        leadingIcon = MyImages.MenuChevron,
        onLeadingClick = onBackClick,
        centralPart = false,
        trailingText = actionText,
        onTrailingClick = onActionClick
    )
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393)
@Composable
private fun NavBarPreview() {
    Column(
        modifier = Modifier
            .background(MyTheme.Colors.backgroundPrimary)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NavBarBack(onBackClick = {})
        NavBarBackTitle(title = "Label", onBackClick = {})
        NavBarBackTitleInfo(title = "Label", onBackClick = {}, onInfoClick = {})
        NavBarTitleClose(title = "Label", onCloseClick = {})
        NavBarBackTitlePlus(title = "Label", onBackClick = {}, onPlusClick = {})
        NavBarBackPlus(onBackClick = {}, onPlusClick = {})
        NavBarTitle(title = "Title Only")
        NavBarClose(onCloseClick = {})
        NavBarActionTitleAction(
            title = "Label",
            leadingActionText = "Cancel",
            onLeadingActionClick = {},
            trailingActionText = "Apply",
            onTrailingActionClick = {}
        )
        NavBarBackTitleAction(
            title = "Label",
            onBackClick = {},
            actionText = "Quick voting",
            onActionClick = {}
        )
        NavBarBackAction(
            onBackClick = {},
            actionText = "Quick voting",
            onActionClick = {}
        )
    }
}