/*
 * Copyright 2024 Dash Core Group.
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

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

@Composable
fun InfoPanel(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    @DrawableRes leftIconRes: Int? = null,
    @DrawableRes actionIconRes: Int? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(16.dp))
            .shadow(elevation = 20.dp, spotColor = Color(0x1AB8C1CC), ambientColor = Color(0x1AB8C1CC)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp, 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            leftIconRes?.let {
                Box(
                    modifier = Modifier
                        .size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = leftIconRes),
                        contentDescription = null,
                        tint = Color.Unspecified
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MyTheme.CaptionMedium
                )
                
                Text(
                    text = description,
                    style = MyTheme.Caption,
                    color = MyTheme.Colors.textSecondary
                )
            }

            if (actionIconRes != null && onAction != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { onAction() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = actionIconRes),
                        contentDescription = "Close",
                        tint = MyTheme.Colors.gray
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun InfoPanelPreview() {
    InfoPanel(
        title = "Customize shortcut bar",
        description = "Hold any button above to replace it with the function you need",
        leftIconRes = R.drawable.ic_dash_blue_filled,
        actionIconRes = R.drawable.ic_popup_close,
        onAction = {}
    )
} 