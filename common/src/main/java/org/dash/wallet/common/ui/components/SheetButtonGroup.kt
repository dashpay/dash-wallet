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

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Data class representing a button in the group
 */
data class SheetButton(
    val text: String,
    val style: Style,
    val leadingIcon: ImageVector? = null,
    val trailingIcon: ImageVector? = null,
    val isEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Button group component for bottom sheets and dialogs
 * Supports both vertical and horizontal layouts with primary/secondary buttons
 *
 * @param primaryButton The primary action button (required)
 * @param secondaryButton Optional secondary action button
 * @param orientation Layout orientation (Vertical or Horizontal)
 * @param modifier Modifier for the container
 * @param horizontalPadding Horizontal padding for the button group
 * @param verticalPadding Vertical padding for the button group
 * @param spacing Space between buttons
 */
@Composable
fun SheetButtonGroup(
    primaryButton: SheetButton,
    secondaryButton: SheetButton? = null,
    orientation: ButtonGroupOrientation = ButtonGroupOrientation.Vertical,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 40.dp,
    verticalPadding: Dp = 20.dp,
    spacing: Dp = 10.dp
) {
    when (orientation) {
        ButtonGroupOrientation.Vertical -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                // Primary button first in vertical layout
                DashButton(
                    text = primaryButton.text,
                    leadingIcon = primaryButton.leadingIcon,
                    trailingIcon = primaryButton.trailingIcon,
                    style = primaryButton.style,
                    size = Size.Large,
                    isEnabled = primaryButton.isEnabled,
                    isLoading = primaryButton.isLoading,
                    onClick = primaryButton.onClick
                )

                // Secondary button below
                secondaryButton?.let { button ->
                    DashButton(
                        text = button.text,
                        leadingIcon = button.leadingIcon,
                        trailingIcon = button.trailingIcon,
                        style = button.style,
                        size = Size.Large,
                        isEnabled = button.isEnabled,
                        isLoading = button.isLoading,
                        onClick = button.onClick
                    )
                }
            }
        }
        ButtonGroupOrientation.Horizontal -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                // Secondary button on left in horizontal layout
                secondaryButton?.let { button ->
                    DashButton(
                        text = button.text,
                        leadingIcon = button.leadingIcon,
                        trailingIcon = button.trailingIcon,
                        style = button.style,
                        size = Size.Large,
                        isEnabled = button.isEnabled,
                        isLoading = button.isLoading,
                        onClick = button.onClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Primary button on right
                DashButton(
                    text = primaryButton.text,
                    leadingIcon = primaryButton.leadingIcon,
                    trailingIcon = primaryButton.trailingIcon,
                    style = primaryButton.style,
                    size = Size.Large,
                    isEnabled = primaryButton.isEnabled,
                    isLoading = primaryButton.isLoading,
                    onClick = primaryButton.onClick,
                    modifier = if (secondaryButton != null) Modifier.weight(1f) else Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Orientation options for button groups
 */
enum class ButtonGroupOrientation {
    Vertical,
    Horizontal
}

// Preview examples
@Preview(showBackground = true)
@Composable
private fun SheetButtonGroupVerticalPreview() {
    SheetButtonGroup(
        primaryButton = SheetButton(
            text = "Continue",
            style = Style.FilledBlue,
            onClick = {}
        ),
        secondaryButton = SheetButton(
            text = "Cancel",
            style = Style.StrokeGray,
            onClick = {}
        ),
        orientation = ButtonGroupOrientation.Vertical
    )
}

@Preview(showBackground = true)
@Composable
private fun SheetButtonGroupHorizontalPreview() {
    SheetButtonGroup(
        primaryButton = SheetButton(
            text = "Continue",
            style = Style.FilledBlue,
            onClick = {}
        ),
        secondaryButton = SheetButton(
            text = "Cancel",
            style = Style.StrokeGray,
            onClick = {}
        ),
        orientation = ButtonGroupOrientation.Horizontal
    )
}

@Preview(showBackground = true)
@Composable
private fun SheetButtonGroupSingleButtonPreview() {
    SheetButtonGroup(
        primaryButton = SheetButton(
            text = "Got it",
            style = Style.FilledBlue,
            onClick = {}
        )
    )
}
