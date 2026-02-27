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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

/**
 * A unified list item component covering all design system variants.
 *
 * Layout structure:
 *   [topLabel]
 *   Row { [leadingContent]  [left content]  [trailing content] }
 *   [bottomLabel]
 *
 * **Left content** — two mutually exclusive modes:
 *  - Key-value mode: provide [label] (gray tertiary text). No title/subtitle.
 *  - Content block mode: provide [title]; optionally [helpTextAbove], [subtitle], [bottomHelpText].
 *
 * **Trailing content** — combine as needed:
 *  - [trailingText] — primary-coloured value text
 *  - [trailingTextLines] — multiple lines of value text (List6 style)
 *  - [trailingHelpText] + [trailingHelpIcon] — secondary text below the value (List16/17)
 *  - [trailingActionText] — blue action link below the value (List5)
 *  - [trailingLabel] — small outlined chip label (List7)
 *  - [trailingLeadingIcon] — icon rendered *before* the value text (List2/15/17)
 *  - [trailingTrailingIcon] — icon rendered *after* the value text (List3/4/20)
 *  - [trailingContent] — fully custom composable slot (ATM Buy/Sell buttons, List18, etc.)
 *
 * **Examples:**
 * ```
 * // List1  — label | value
 * ListItem(label = "text", trailingText = "text")
 *
 * // List2  — label | ○ value
 * ListItem(label = "text", trailingText = "text", trailingLeadingIcon = { CheckboxIcon() })
 *
 * // List5  — label | value / Action
 * ListItem(label = "text", trailingText = "value", trailingActionText = "Action")
 *
 * // List8  — standalone title
 * ListItem(title = "text")
 *
 * // List13 — multi-line left | blue icon
 * ListItem(helpTextAbove = "top", title = "title", subtitle = "text",
 *          bottomHelpText = "bottom", trailingTrailingIcon = { BlueIcon() })
 *
 * // List12 — top / ○ title/sub | value / bottom
 * ListItem(topLabel = "top", bottomLabel = "bottom",
 *          leadingContent = { CheckboxIcon() }, title = "text",
 *          subtitle = "help text", trailingText = "text")
 *
 * // ATM   — ○ name/sub | [Buy][Sell]
 * ListItem(leadingContent = { Image() }, title = "ATM name",
 *          subtitle = "help text", trailingContent = { BuySellButtons() })
 * ```
 */
@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    // ── Full-width wrapper labels (List12 / List18 style) ─────────────────
    topLabel: String? = null,
    bottomLabel: String? = null,
    // ── Leading content (List12 / Merchant / ATM style) ───────────────────
    leadingContent: (@Composable () -> Unit)? = null,
    // ── Left side: key-value label (List1–7, List10–11) ───────────────────
    label: String? = null,
    showInfoIcon: Boolean = false,
    // ── Left side: content block (List8–9, List13–14, List16, List20–23) ──
    helpTextAbove: String? = null,
    title: String? = null,
    subtitle: String? = null,
    bottomHelpText: String? = null,
    // ── Right side: text content ──────────────────────────────────────────
    trailingText: String? = null,
    trailingTextLines: List<String>? = null,
    trailingHelpText: String? = null,
    @DrawableRes trailingHelpIcon: Int? = null,
    trailingActionText: String? = null,
    trailingLabel: String? = null,
    // ── Right side: icons ─────────────────────────────────────────────────
    trailingLeadingIcon: (@Composable RowScope.() -> Unit)? = null,
    trailingTrailingIcon: (@Composable RowScope.() -> Unit)? = null,
    // ── Right side: fully custom slot ─────────────────────────────────────
    trailingContent: (@Composable () -> Unit)? = null,
    // ── Interaction ───────────────────────────────────────────────────────
    onClick: (() -> Unit)? = null
) {
    val hasContentBlock = title != null || helpTextAbove != null ||
        subtitle != null || bottomHelpText != null
    val hasTrailing = trailingContent != null || trailingText != null ||
        !trailingTextLines.isNullOrEmpty() || trailingHelpText != null ||
        trailingActionText != null || trailingLabel != null ||
        trailingLeadingIcon != null || trailingTrailingIcon != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        topLabel?.let {
            Text(
                text = it,
                style = MyTheme.Typography.BodySmall,
                color = MyTheme.Colors.textTertiary,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Leading icon / thumbnail
            leadingContent?.invoke()

            // ── Left column ──────────────────────────────────────────────
            if (hasContentBlock) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    helpTextAbove?.let {
                        Text(
                            text = it,
                            style = MyTheme.Typography.BodySmall,
                            color = MyTheme.Colors.textTertiary
                        )
                    }
                    title?.let { titleText ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = titleText,
                                style = MyTheme.Body2Medium,
                                color = MyTheme.Colors.textPrimary
                            )
                            if (showInfoIcon) {
                                Icon(
                                    painter = painterResource(android.R.drawable.ic_dialog_info),
                                    contentDescription = null,
                                    tint = MyTheme.Colors.textTertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MyTheme.Typography.BodySmall,
                            color = MyTheme.Colors.textTertiary
                        )
                    }
                    bottomHelpText?.let {
                        Text(
                            text = it,
                            style = MyTheme.Typography.BodySmall,
                            color = MyTheme.Colors.textTertiary
                        )
                    }
                }
            } else if (label != null) {
                // Key-value label (tertiary, natural width) + spacer pushes trailing right
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = label,
                        style = MyTheme.Body2Regular,
                        color = MyTheme.Colors.textTertiary
                    )
                    if (showInfoIcon) {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_dialog_info),
                            contentDescription = null,
                            tint = MyTheme.Colors.textTertiary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            }

            // ── Trailing / right column ───────────────────────────────────
            if (hasTrailing) {
                if (trailingContent != null) {
                    trailingContent()
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        // Main text row: [leadingIcon] [text/lines] [trailingIcon]
                        val hasTextRow = trailingText != null ||
                            !trailingTextLines.isNullOrEmpty() ||
                            trailingLeadingIcon != null ||
                            trailingTrailingIcon != null
                        if (hasTextRow) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                trailingLeadingIcon?.invoke(this)
                                if (!trailingTextLines.isNullOrEmpty()) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        trailingTextLines.forEach { line ->
                                            Text(
                                                text = line,
                                                style = MyTheme.Body2Regular,
                                                color = MyTheme.Colors.textPrimary
                                            )
                                        }
                                    }
                                } else {
                                    trailingText?.let {
                                        Text(
                                            text = it,
                                            style = MyTheme.Body2Regular,
                                            color = MyTheme.Colors.textPrimary
                                        )
                                    }
                                }
                                trailingTrailingIcon?.invoke(this)
                            }
                        }

                        // Help text row: [icon] help text
                        trailingHelpText?.let { helpText ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                trailingHelpIcon?.let { iconRes ->
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = null,
                                        tint = MyTheme.Colors.textTertiary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Text(
                                    text = helpText,
                                    style = MyTheme.Typography.BodySmall,
                                    color = MyTheme.Colors.textTertiary
                                )
                            }
                        }

                        // Action link (blue)
                        trailingActionText?.let {
                            Text(
                                text = it,
                                style = MyTheme.Body2Medium,
                                color = MyTheme.Colors.dashBlue
                            )
                        }

                        // Chip / badge label
                        trailingLabel?.let {
                            Text(
                                text = it,
                                style = MyTheme.Typography.BodySmall,
                                color = MyTheme.Colors.textPrimary,
                                modifier = Modifier
                                    .border(
                                        width = 1.dp,
                                        color = MyTheme.Colors.textTertiary.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        bottomLabel?.let {
            Text(
                text = it,
                style = MyTheme.Typography.BodySmall,
                color = MyTheme.Colors.textTertiary,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 4.dp)
            )
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

/**
 * Empty-list placeholder (ListEmptyState variant).
 *
 * Shows a centred icon, heading, optional body text, and optional action row.
 */
@Composable
fun ListEmptyState(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    heading: String,
    body: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        icon()
        Text(heading, style = MyTheme.Body2Medium, color = MyTheme.Colors.textPrimary)
        body?.let {
            Text(
                text = it,
                style = MyTheme.Typography.BodySmall,
                color = MyTheme.Colors.textTertiary
            )
        }
        actions?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { it() }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun ListItemPreview() {
    var checked1 by remember { mutableStateOf(false) }
    var checked2 by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .background(MyTheme.Colors.backgroundSecondary)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // List1 — label | value
        ListItem(label = "label", trailingText = "text")

        // List2 — label | ○ value
        ListItem(
            label = "label",
            trailingText = "text",
            trailingLeadingIcon = {
                CheckboxIcon(checked = checked1, onToggle = { checked1 = it })
            }
        )

        // List3 — label | value ○
        ListItem(
            label = "label",
            trailingText = "text",
            trailingTrailingIcon = {
                CheckboxIcon(checked = checked2, onToggle = { checked2 = it })
            }
        )

        // List4 — label | ○ (icon only)
        ListItem(
            label = "label",
            trailingTrailingIcon = {
                CheckboxIcon(checked = false, onToggle = {})
            }
        )

        // List5 — label | value / Action
        ListItem(
            label = "label",
            trailingText = "text",
            trailingActionText = "Action"
        )

        // List6 — label | multi-line value
        ListItem(
            label = "label",
            trailingTextLines = listOf("line 1", "line 2", "line 3", "line 4")
        )

        // List7 — label | [Label chip]
        ListItem(label = "label", trailingLabel = "Label")

        // List8 — standalone title
        ListItem(title = "text")

        // List10 — label ℹ | value
        ListItem(label = "label", showInfoIcon = true, trailingText = "text")

        // List14 / List22 — help above + title
        ListItem(helpTextAbove = "help text", title = "text")

        // List16 — title / subtitle | trailing / ↺ help
        ListItem(
            title = "text",
            subtitle = "help text",
            trailingText = "text",
            trailingHelpText = "help text",
            trailingHelpIcon = R.drawable.ic_swap_blue
        )

        // List17 — label | ○ value / help
        ListItem(
            label = "label",
            trailingText = "text",
            trailingHelpText = "help text",
            trailingLeadingIcon = {
                CheckboxIcon(checked = false, onToggle = {})
            }
        )

        // List20 — title / subtitle | trailing text ○
        ListItem(
            title = "text",
            subtitle = "help text",
            trailingText = "text trailing",
            trailingTrailingIcon = {
                CheckboxIcon(checked = false, onToggle = {})
            }
        )

        // List23 — title / subtitle
        ListItem(title = "text", subtitle = "help text")

        // List13 — helpAbove / title / subtitle / bottomHelp | icon
        ListItem(
            helpTextAbove = "top help text",
            title = "title",
            subtitle = "text",
            bottomHelpText = "bottom help text",
            trailingTrailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_dash_blue_filled),
                    contentDescription = null,
                    tint = MyTheme.Colors.dashBlue,
                    modifier = Modifier.size(32.dp)
                )
            }
        )

        // List12 — top / ○ title/sub | value / bottom
        ListItem(
            topLabel = "top text",
            bottomLabel = "bottom text",
            leadingContent = { CheckboxIcon(checked = false, onToggle = {}) },
            title = "text",
            subtitle = "help text",
            trailingText = "text"
        )

        // List18 — top / ○ title/sub | ○ value/sub / bottom
        ListItem(
            topLabel = "top text",
            bottomLabel = "bottom text",
            leadingContent = { CheckboxIcon(checked = false, onToggle = {}) },
            title = "text",
            subtitle = "help text",
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CheckboxIcon(checked = true, onToggle = {})
                    Column {
                        Text("text", style = MyTheme.Body2Regular, color = MyTheme.Colors.textPrimary)
                        Text("help text", style = MyTheme.Typography.BodySmall, color = MyTheme.Colors.textTertiary)
                    }
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ListEmptyStatePreview() {
    ListEmptyState(
        modifier = Modifier.background(MyTheme.Colors.backgroundSecondary),
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_dash_blue_filled),
                contentDescription = null,
                tint = MyTheme.Colors.dashBlue,
                modifier = Modifier.size(48.dp)
            )
        },
        heading = "Heading",
        body = "Text block",
        actions = {
            Text(
                text = "Label",
                style = MyTheme.Typography.BodySmall,
                color = MyTheme.Colors.dashBlue,
                modifier = Modifier
                    .border(1.dp, MyTheme.Colors.dashBlue, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Label",
                style = MyTheme.Typography.BodySmall,
                color = MyTheme.Colors.dashBlue,
                modifier = Modifier
                    .border(1.dp, MyTheme.Colors.dashBlue, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    )
}

// ── Internal helper ───────────────────────────────────────────────────────────

/**
 * Minimal standalone checkbox box — just the visual tick-box without any
 * surrounding row layout.  Used in [ListItem] previews; real callers can pass
 * their own content to the leading/trailing icon slots.
 */
@Composable
private fun CheckboxIcon(checked: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = 1.5.dp,
                color = if (checked) MyTheme.Colors.dashBlue else MyTheme.Colors.darkerGray50,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (checked) MyTheme.Colors.dashBlue else Color.Transparent)
            .clickable { onToggle(!checked) },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_checkmark_blue),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}