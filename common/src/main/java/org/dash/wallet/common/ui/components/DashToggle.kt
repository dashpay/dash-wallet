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

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Custom toggle switch component matching the Dash design system
 * Based on Figma design ("toggle/yes"): https://www.figma.com/design/azdJACb5WmivxYVhB5q46F/Design-system---Android?node-id=2486-6694
 * A 52x32 pill track with a 24dp white thumb inset 4dp inside it
 */
@Composable
fun DashSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val density = LocalDensity.current
    val colors = LocalDashColors.current
    
    // Dimensions based on Figma design
    val trackWidth = 52.dp
    val trackHeight = 32.dp
    val thumbInset = 4.dp
    val thumbSize = trackHeight - thumbInset * 2
    val trackCornerRadius = trackHeight / 2

    // Thumb travel distance (track width minus thumb size and both insets) in pixels
    val maxOffsetPx = with(density) { (trackWidth - thumbSize - thumbInset * 2).toPx() }
    
    // Animate thumb position
    val thumbOffsetPx by animateFloatAsState(
        targetValue = if (checked) maxOffsetPx else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "thumb_offset"
    )
    
    // Convert animated pixel value back to Dp
    val thumbOffset = with(density) { thumbOffsetPx.toDp() }
    
    // Colors based on Figma design
    val trackColor = if (checked) {
        colors.dashBlue
    } else {
        colors.gray300.copy(alpha = 0.5f)
    }
    val thumbColor = Color.White
    
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                role = Role.Switch,
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // no ripple to match Figma
                onValueChange = { onCheckedChange?.invoke(it) }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Track background
        Box(
            modifier = Modifier
                .width(trackWidth)
                .height(trackHeight)
                .background(
                    color = trackColor,
                    shape = RoundedCornerShape(trackCornerRadius)
                )
        )
        
        // Thumb container - positioned inside the track
        Box(
            modifier = Modifier
                .width(trackWidth)
                .height(trackHeight)
                .padding(horizontal = thumbInset),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .offset(x = thumbOffset)
                    .background(
                        color = thumbColor,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Preview(name = "Dash Switch Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dash Switch Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashSwitchPreview() {
    DashWalletTheme {
        DashSwitchPreviewContent()
    }
}

@Composable
private fun DashSwitchPreviewContent() {
    Box(
        modifier = Modifier.padding(16.dp)
    ) {
        // Show both states side by side
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
        ) {
            // Active state
            DashSwitch(
                checked = true,
                onCheckedChange = {}
            )

            // Inactive state
            DashSwitch(
                checked = false,
                onCheckedChange = {}
            )
        }
    }
}