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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Custom toggle switch component matching the Dash design system
 * Based on Figma design: https://www.figma.com/design/azdJACb5WmivxYVhB5q46F/Design-system---Android?node-id=2486-6695
 * Uses Material Switch with custom theming for better accessibility and maintainability
 */
@Composable
fun DashToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MyTheme.Colors.dashBlue,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = MyTheme.Colors.gray300,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.38f),
            disabledCheckedTrackColor = MyTheme.Colors.dashBlue.copy(alpha = 0.12f),
            disabledUncheckedThumbColor = Color.White.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = MyTheme.Colors.gray300.copy(alpha = 0.12f)
        )
    )
}

@Preview(showBackground = true)
@Composable
private fun DashTogglePreview() {
    Box(
        modifier = Modifier.padding(16.dp)
    ) {
        // Show both states side by side
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
        ) {
            // Active state
            DashToggle(
                checked = true,
                onCheckedChange = {}
            )

            // Inactive state
            DashToggle(
                checked = false,
                onCheckedChange = {}
            )
        }
    }
}