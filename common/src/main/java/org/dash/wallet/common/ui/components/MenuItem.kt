/*
 * Copyright (c) 2024 Dash Core Group
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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.dash.wallet.common.R

@Composable
fun MenuItem(
    title: String,
    subtitle: String? = null,
    details: String? = null,
    icon: Int? = null, // Assuming you use resource IDs for icons
    showInfo: Boolean = false,
    showChevron: Boolean = false,
    isToggled: (() -> Boolean)? = null, // Change to a lambda that returns a Boolean
    onToggleChanged: ((Boolean) -> Unit)? = null,
    action: (() -> Unit)? = null
) {
    var toggleState by remember { mutableStateOf(isToggled?.invoke() ?: false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .clickable { action?.invoke() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Image(
                painter = painterResource(id = it),
                contentDescription = null,
                modifier = Modifier.size(34.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Color.Black,
                    modifier = Modifier.weight(1f)
                )

                if (showInfo) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_dialog_info),
                        contentDescription = "Info",
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            subtitle?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = Color.DarkGray,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            details?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = Color.DarkGray,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        isToggled?.let {
            Switch(
                checked = toggleState,
                onCheckedChange = { newState ->
                    toggleState = newState
                    onToggleChanged?.invoke(newState)
                },
                modifier = Modifier.size(60.dp)
            )
        }

        if (showChevron) {
            Icon(
                painter = painterResource(id = R.drawable.ic_menu_row_arrow),
                contentDescription = "Chevron",
                tint = Color.Gray,
                modifier = Modifier.padding(end = 10.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewMenuItem() {
    MenuItem(
        title = "Title",
        subtitle = "Easily stake Dash and earn passive income with a few simple steps",
        icon = R.drawable.ic_dash_blue_filled,
        showInfo = true,
        isToggled = { true },
        onToggleChanged = { }
    )
}
