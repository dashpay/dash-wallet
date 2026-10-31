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

package de.schildbach.wallet.ui.compose_views

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.ui.more.connections.AppCapability
import de.schildbach.wallet.ui.more.connections.AppCapabilityKind
import de.schildbach.wallet.ui.more.connections.formatDash
import de.schildbach.wallet_test.R
import java.text.DateFormat
import java.util.Date
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style

// Building blocks shared by the approve sheet and the connection details sheet (Figma 40033:49941,
// 40060:40438).

@Composable
internal fun capabilityText(capability: AppCapability, appName: String): String {
    val spending = capability.budget?.let { stringResource(R.string.dash_connect_spending_limit, formatDash(it)) }
        ?: stringResource(R.string.dash_connect_spending_unlimited)
    val until = capability.expiresAt?.let { formatDate(it) }.orEmpty()
    return when (capability.kind) {
        AppCapabilityKind.ACT_IN_APP -> stringResource(
            if (capability.tokens) R.string.dash_connect_capability_act_tokens else R.string.dash_connect_capability_act,
            appName, spending, until
        )
        AppCapabilityKind.EDIT_DASHPAY_PROFILE -> stringResource(R.string.dash_connect_capability_profile, spending, until)
        AppCapabilityKind.EDIT_DOCUMENTS -> stringResource(
            R.string.dash_connect_capability_documents,
            // Strip control and format characters from the app-supplied type name.
            capability.documentType.orEmpty().replace(Regex("[\\p{Cc}\\p{Cf}]"), ""),
            appName,
            spending,
            until
        )
        AppCapabilityKind.RECEIVE_ENCRYPTED -> stringResource(R.string.dash_connect_capability_encrypted, appName)
    }
}

internal fun formatDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

/** Figma "Details": a 20dp rounded card, tinted gray when [filled] and outlined otherwise. */
@Composable
internal fun ConnectCard(
    filled: Boolean = false,
    /** Overrides the gray tint of a [filled] card, e.g. the status card's green. */
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (filled) {
                    Modifier.background(tint ?: MyTheme.ColorScheme.Gray300Alpha10)
                } else {
                    Modifier.border(2.dp, MyTheme.ColorScheme.Gray300.copy(alpha = 0.15f), shape)
                }
            )
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

@Composable
internal fun CollapsibleCard(
    title: String,
    filled: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalDashColors.current
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val state = stringResource(if (expanded) R.string.dash_connect_expanded else R.string.dash_connect_collapsed)
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (filled) Modifier.background(colors.backgroundSecondary) else Modifier)
            .border(2.dp, MyTheme.ColorScheme.Gray300.copy(alpha = 0.15f), shape)
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = state }
                .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = title,
                style = MyTheme.Typography.Subhead,
                color = colors.textPrimary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            Box(modifier = Modifier.size(54.dp, 40.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MyTheme.ColorScheme.Gray300.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_connect_chevron_up),
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.rotate(if (expanded) 0f else 180f)
                    )
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }
    }
}

@Composable
internal fun LimitRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    /** Null hides the Change button, e.g. for an existing connection's fixed terms. */
    onChange: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null
) {
    val colors = LocalDashColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MyTheme.Typography.Footnote, color = colors.textSecondary)
                onInfo?.let { InfoButton(it) }
            }
            Text(value, style = MyTheme.Typography.Subhead, color = colors.textPrimary)
        }
        onChange?.let {
            DashButton(
                text = stringResource(R.string.dash_connect_change),
                style = Style.PlainBlue,
                size = Size.Small,
                stretch = false,
                isEnabled = enabled,
                onClick = it
            )
        }
    }
}

@Composable
internal fun InfoButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_connect_info), contentDescription = null)
    }
}

@Composable
internal fun PermissionRow(
    @DrawableRes icon: Int,
    text: String,
    onInfo: (() -> Unit)? = null
) {
    val colors = LocalDashColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = text,
            style = MyTheme.Typography.Subhead,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            onInfo?.let { InfoButton(it) }
        }
    }
}

/** Figma "List 14": a small gray label above its value. */
@Composable
internal fun DetailRow(
    label: String,
    value: String
) {
    val colors = LocalDashColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text = label, style = MyTheme.Typography.LabelMedium, color = colors.textSecondary)
        Text(text = value, style = MyTheme.Typography.BodyLarge, color = colors.textPrimary)
    }
}

/**
 * Truncates a long identifier in the middle, keeping [prefix] leading and [suffix] trailing
 * characters (e.g. "5DbLwAx…7zUo8"). Short strings are returned unchanged.
 */
internal fun truncateMiddle(value: String, prefix: Int = 7, suffix: Int = 5): String {
    return if (value.length <= prefix + suffix + 1) {
        value
    } else {
        "${value.take(prefix)}…${value.takeLast(suffix)}"
    }
}
