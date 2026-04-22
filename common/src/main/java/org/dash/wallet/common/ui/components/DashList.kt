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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private val DashListShape = RoundedCornerShape(20.dp)
private val DashListShadowColor = Color(0xFFB8C1CC)

/**
 * Container for a grouped list of [ListItem] rows.
 *
 * Matches the "list" component from Figma (node 3136:58183):
 * white card, 20dp rounded corners, 6dp padding, 2dp gap between items,
 * and a subtle `#B8C1CC` drop shadow.
 */
@Composable
fun DashList(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 5.dp,
                shape = DashListShape,
                ambientColor = DashListShadowColor.copy(alpha = 0.10f),
                spotColor = DashListShadowColor.copy(alpha = 0.10f)
            )
            .clip(DashListShape)
            .background(MyTheme.Colors.backgroundSecondary)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
@Composable
private fun DashListPreview() {
    DashList {
        ListItem(label = "Original purchase", trailingText = "$50.00")
        ListItem(label = "Card number", trailingText = "6006491727005748")
        ListItem(label = "Card PIN", trailingText = "1411")
    }
}