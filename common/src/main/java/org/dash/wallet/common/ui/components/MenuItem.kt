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
import androidx.compose.foundation.border
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
    helpTextAbove: String? = null,
    subtitle: String? = null,
    subtitle2: String? = null,
    icon: Int? = null,
    showDirectionIndicator: Boolean = false,
    showInfo: Boolean = false,
    showChevron: Boolean = false,
    isToggled: (() -> Boolean)? = null,
    onToggleChanged: ((Boolean) -> Unit)? = null,
    // Balance/Amount display
    dashAmount: String? = null,
    fiatAmount: String? = null,
    // Trailing button
    trailingButtonText: String? = null,
    onTrailingButtonClick: (() -> Unit)? = null,
    action: (() -> Unit)? = null
) {
    var toggleState by remember { mutableStateOf(isToggled?.invoke() ?: false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .background(Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { action?.invoke() }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            // Icon with direction indicator
            Box(modifier = Modifier.size(26.dp)) {
                icon?.let {
                    Image(
                        painter = painterResource(id = it),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                }
                
                // Direction indicator overlay
                if (showDirectionIndicator) {
                    Box(
                        modifier = Modifier
                            .size(19.dp)
                            .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(32.dp))
                            .align(Alignment.BottomEnd)
                            .offset(x = 8.dp, y = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Small circle indicator
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(Color.Transparent, RoundedCornerShape(7.dp))
                                .border(
                                    width = 2.dp,
                                    color = MyTheme.Colors.gray300,
                                    shape = RoundedCornerShape(7.dp)
                                )
                        )
                    }
                }
            }

            // Main content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Help text above (if provided) - aligned with title
                helpTextAbove?.let {
                    Text(
                        text = it,
                        style = MyTheme.OverlineMedium,
                        color = MyTheme.Colors.textTertiary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Title row with info icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = MyTheme.Body2Medium,
                        color = MyTheme.Colors.textPrimary
                    )

                    if (showInfo) {
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_dialog_info),
                            contentDescription = "Info",
                            tint = MyTheme.Colors.textTertiary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                // Subtitle
                subtitle?.let {
                    Text(
                        text = it,
                        style = MyTheme.OverlineCaptainRegular,
                        color = MyTheme.Colors.textTertiary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Second subtitle
                subtitle2?.let {
                    Text(
                        text = it,
                        style = MyTheme.OverlineCaptainRegular,
                        color = MyTheme.Colors.textTertiary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Toggle switch
            isToggled?.let {
                Switch(
                    checked = toggleState,
                    onCheckedChange = { newState ->
                        toggleState = newState
                        onToggleChanged?.invoke(newState)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MyTheme.Colors.backgroundSecondary,
                        checkedTrackColor = MyTheme.Colors.dashBlue,
                        uncheckedThumbColor = MyTheme.Colors.backgroundSecondary,
                        uncheckedTrackColor = MyTheme.Colors.gray300
                    ),
                    modifier = Modifier.size(width = 32.dp, height = 20.dp)
                )
            }

            // Balance/Amount display
            if (dashAmount != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Dash amount with logo
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = dashAmount,
                            style = MyTheme.CaptionMedium,
                            color = MyTheme.Colors.textPrimary
                        )
                        // Dash logo (you'll need to add the actual resource)
                        Icon(
                            painter = painterResource(id = R.drawable.ic_dash_blue_filled),
                            contentDescription = "Dash",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    
                    // Fiat amount
                    fiatAmount?.let {
                        Text(
                            text = it,
                            style = MyTheme.OverlineCaptainRegular,
                            color = MyTheme.Colors.textSecondary
                        )
                    }
                }
            }

            // Trailing small button
            if (trailingButtonText != null && onTrailingButtonClick != null) {
                TextButton(
                    onClick = onTrailingButtonClick,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MyTheme.Colors.textPrimary
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(18.dp)
                ) {
                    Text(
                        text = trailingButtonText,
                        style = MyTheme.SubtitleSemibold.copy(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            if (showChevron) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_menu_row_arrow),
                    contentDescription = "Chevron",
                    tint = MyTheme.Colors.textTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
}

@Preview(showBackground = true)
@Composable
fun PreviewMenuItem() {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .background(MyTheme.Colors.backgroundPrimary),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Basic with help text above
        MenuItem(
            helpTextAbove = "help text 1",
            title = "title",
            subtitle = "help text 2",
            subtitle2 = "help text 3",
            icon = R.drawable.ic_dash_blue_filled,
            showInfo = true,
            showDirectionIndicator = true
        )

        // With toggle
        MenuItem(
            title = "Toggle Setting",
            subtitle = "Enable this feature",
            icon = R.drawable.ic_dash_blue_filled,
            isToggled = { true },
            onToggleChanged = { }
        )

        // With balance display
        MenuItem(
            title = "Wallet Balance",
            subtitle = "Available balance",
            icon = R.drawable.ic_dash_blue_filled,
            dashAmount = "0.00",
            fiatAmount = "0.00 US$"
        )

        // With trailing button
        MenuItem(
            title = "More Action Item",
            icon = R.drawable.ic_dash_blue_filled,
            onTrailingButtonClick = { },
            showChevron = true
        )

        // With trailing button
        MenuItem(
            title = "Action Item",
            subtitle = "Tap button to proceed",
            icon = R.drawable.ic_dash_blue_filled,
            trailingButtonText = "Label",
            onTrailingButtonClick = { }
        )

        // Complex example matching Figma
        MenuItem(
            helpTextAbove = "help text 1",
            title = "title",
            subtitle = "help text 2", 
            subtitle2 = "help text 3",
            icon = R.drawable.ic_dash_blue_filled,
            showDirectionIndicator = true,
            showInfo = true,
            isToggled = { true },
            onToggleChanged = { },
            dashAmount = "0.00",
            fiatAmount = "0.00 US$",
            trailingButtonText = "Label",
            onTrailingButtonClick = { }
        )
    }
}
