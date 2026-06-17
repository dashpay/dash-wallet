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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

/**
 * Numbered list-item variants from the design system "List" playground
 * (Figma: Design system - Android, node `7968:2076`).
 *
 * Each [ListItem1]…[ListItem11] maps 1-to-1 to the Figma `ListX` symbol of the
 * same number. They are thin, strongly-typed wrappers that share the
 * [ListItemRow] scaffold (14 dp horizontal / 12 dp vertical padding, 20 dp gap)
 * so spacing stays consistent across the whole family. Wrap one or more in a
 * [Menu] for the standard rounded white card.
 *
 * The general-purpose [ListItem] (in ListItem.kt) remains available for ad-hoc
 * combinations; prefer these numbered variants when a row matches a design
 * symbol exactly.
 */

// ── Shared scaffold ─────────────────────────────────────────────────────────

/**
 * Standard list-item row: full width, 14 dp horizontal / 12 dp vertical padding,
 * 20 dp gap, vertically centred. Used by every numbered variant below.
 */
@Composable
private fun ListItemRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        content()
    }
}

@Composable
private fun KeyLabel(text: String) {
    Text(text = text, style = MyTheme.Typography.BodyMediumMedium, color = MyTheme.Colors.textTertiary)
}

@Composable
private fun ValueText(text: String) {
    Text(text = text, style = MyTheme.Typography.BodyMedium, color = MyTheme.Colors.textPrimary)
}

@Composable
private fun Chevron() {
    Icon(
        painter = painterResource(R.drawable.ic_list_chevron_right),
        contentDescription = null,
        tint = MyTheme.Colors.textTertiary,
        modifier = Modifier.size(16.dp)
    )
}

// ── List1 — label | value ───────────────────────────────────────────────────

/**
 * List1: gray key label on the left, primary value on the right. An optional
 * [trailing] slot renders after the value (e.g. a small action button).
 */
@Composable
fun ListItem1(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        KeyLabel(label)
        Spacer(Modifier.weight(1f))
        ValueText(value)
        trailing?.invoke()
    }
}

// ── List2 — label | multi-line value ────────────────────────────────────────

/** List2: gray key label on the left, multiple right-aligned value lines. */
@Composable
fun ListItem2(
    label: String,
    valueLines: List<String>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        KeyLabel(label)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            valueLines.forEach { ValueText(it) }
        }
    }
}

// ── List3 — label | icon value ──────────────────────────────────────────────

/** List3: gray key label on the left, leading icon + value on the right. */
@Composable
fun ListItem3(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    @DrawableRes leadingIcon: Int = R.drawable.ic_dash_blue_filled,
    onClick: (() -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        KeyLabel(label)
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(leadingIcon),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(24.dp)
            )
            ValueText(value)
        }
    }
}

// ── List4 — title | gray value › ─────────────────────────────────────────────

/** List4: primary title on the left, gray value + chevron on the right (navigational). */
@Composable
fun ListItem4(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        Text(text = label, style = MyTheme.Typography.BodyMedium, color = MyTheme.Colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = value, style = MyTheme.Typography.BodyMedium, color = MyTheme.Colors.textTertiary)
            Chevron()
        }
    }
}

// ── List5 — action › ─────────────────────────────────────────────────────────

/** List5: standalone primary action label with a trailing chevron. */
@Composable
fun ListItem5(
    action: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        Text(text = action, style = MyTheme.Typography.BodyMediumMedium, color = MyTheme.Colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Chevron()
    }
}

// ── List6 — title / subtitle | value › ───────────────────────────────────────

/** List6: title over gray help text on the left, value + chevron on the right. */
@Composable
fun ListItem6(
    title: String,
    helpText: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MyTheme.Typography.BodyMedium, color = MyTheme.Colors.textPrimary)
            Text(text = helpText, style = MyTheme.Typography.BodySmall, color = MyTheme.Colors.textTertiary)
        }
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = value, style = MyTheme.Typography.BodySmall, color = MyTheme.Colors.textPrimary)
            Chevron()
        }
    }
}

// ── List7 — help text / value | copy icon ────────────────────────────────────

/** List7: gray help text over a primary value on the left, copy icon on the right. */
@Composable
fun ListItem7(
    helpText: String,
    value: String,
    modifier: Modifier = Modifier,
    @DrawableRes trailingIcon: Int = R.drawable.ic_copy,
    onTrailingIconClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = helpText, style = MyTheme.Typography.BodySmall, color = MyTheme.Colors.textTertiary)
            Text(text = value, style = MyTheme.Typography.BodyMedium, color = MyTheme.Colors.textPrimary)
        }
        Spacer(Modifier.weight(1f))
        Icon(
            painter = painterResource(trailingIcon),
            contentDescription = null,
            tint = MyTheme.Colors.textTertiary,
            modifier = Modifier
                .then(if (onTrailingIconClick != null) Modifier.clickable { onTrailingIconClick() } else Modifier)
                .size(14.dp)
        )
    }
}

// ── List8 — label | amount + Dash symbol ─────────────────────────────────────

/** List8: gray key label on the left, amount followed by the Dash symbol on the right. */
@Composable
fun ListItem8(
    label: String,
    amount: String,
    modifier: Modifier = Modifier,
    @DrawableRes amountIcon: Int = R.drawable.ic_dash_d_black,
    onClick: (() -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        KeyLabel(label)
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ValueText(amount)
            Icon(
                painter = painterResource(amountIcon),
                contentDescription = null,
                tint = MyTheme.Colors.textPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// ── List9 — version block | status block ─────────────────────────────────────

/**
 * List9: a version/identifier block on the left (primary value over two gray
 * lines) and a status block on the right (primary value over an icon + gray help
 * line). Used for node / network status rows.
 */
@Composable
fun ListItem9(
    title: String,
    subtitle1: String,
    subtitle2: String,
    trailingTitle: String,
    trailingHelpText: String,
    modifier: Modifier = Modifier,
    @DrawableRes trailingHelpIcon: Int = R.drawable.ic_left_right_arrows,
    onClick: (() -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        // Inline LabelLarge/LabelMedium (Figma List9 uses Label L/M Regular) — do NOT
        // route the title through the shared ValueText helper (List1/2/3/4/6/8 use it).
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MyTheme.Typography.LabelLarge, color = MyTheme.Colors.textPrimary)
            Text(text = subtitle1, style = MyTheme.Typography.LabelMedium, color = MyTheme.Colors.textTertiary)
            Text(text = subtitle2, style = MyTheme.Typography.LabelMedium, color = MyTheme.Colors.textTertiary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = trailingTitle, style = MyTheme.Typography.LabelLarge, color = MyTheme.Colors.textPrimary)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(trailingHelpIcon),
                    contentDescription = null,
                    tint = MyTheme.Colors.textTertiary,
                    modifier = Modifier.size(10.dp)
                )
                Text(text = trailingHelpText, style = MyTheme.Typography.LabelMedium, color = MyTheme.Colors.textTertiary)
            }
        }
    }
}

// ── List10 — secondary text / primary text ───────────────────────────────────

/** List10: a secondary-colored label stacked above a primary value. */
@Composable
fun ListItem10(
    secondaryText: String,
    primaryText: String,
    modifier: Modifier = Modifier,
    primaryColor: Color? = null,
    onClick: (() -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = secondaryText, style = MyTheme.Typography.BodyMedium, color = MyTheme.Colors.textPrimary)
            Text(text = primaryText, style = MyTheme.Typography.BodyMedium, color = primaryColor ?: MyTheme.Colors.textPrimary)
        }
    }
}

// ── List11 — label / primary text ─────────────────────────────────────────────

/**
 * List11: a tertiary gray label stacked above a primary value. [primaryMaxLines]
 * caps the value (ellipsised) — e.g. to truncate a long token.
 */
@Composable
fun ListItem11(
    label: String,
    primaryText: String,
    modifier: Modifier = Modifier,
    primaryMaxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null
) {
    ListItemRow(modifier, onClick) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = label, style = MyTheme.Typography.BodyMediumMedium, color = MyTheme.Colors.textTertiary)
            Text(
                text = primaryText,
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textPrimary,
                maxLines = primaryMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun ListItemVariantsPreview() {
    Column(
        modifier = Modifier
            .background(MyTheme.Colors.backgroundSecondary)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(text = "list1")
        ListItem1(label = "Label", value = "Text")
        Text(text = "list2")
        ListItem2(label = "Label", valueLines = listOf("Text", "Text"))
        Text(text = "list3")
        ListItem3(label = "Label", value = "Text")
        Text(text = "list4")
        ListItem4(label = "Label", value = "Text")
        Text(text = "list5")
        ListItem5(action = "Action")
        Text(text = "list6")
        ListItem6(title = "Label", helpText = "Help text", value = "Text")
        Text(text = "list7")
        ListItem7(helpText = "Help text", value = "Value")
        Text(text = "list8")
        ListItem8(label = "Label", amount = "0.00")
        Text(text = "list9")
        ListItem9(
            title = "00.000.00.00",
            subtitle1 = "/Dash Core:00.0.0/",
            subtitle2 = "protocol: 00000",
            trailingTitle = "X blocks",
            trailingHelpText = "00 ms"
        )
        Text(text = "list10")
        ListItem10(secondaryText = "Secondary text", primaryText = "Primary text")
        Text(text = "list11")
        ListItem11(label = "Label", primaryText = "Primary text")
        Spacer(Modifier.width(0.dp))
    }
}
