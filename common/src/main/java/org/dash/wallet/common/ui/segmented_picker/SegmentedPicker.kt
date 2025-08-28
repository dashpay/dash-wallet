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

package org.dash.wallet.common.ui.segmented_picker

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R
import org.dash.wallet.common.ui.components.MyTheme

data class SegmentedOption(
    val title: String,
    @DrawableRes val icon: Int? = null
)

enum class PickerDisplayMode {
    Horizontal,
    Vertical
}

data class SegmentedPickerStyle(
    val displayMode: PickerDisplayMode = PickerDisplayMode.Horizontal,
    val backgroundColor: Color = MyTheme.Colors.gray400.copy(alpha = 0.1f),
    val thumbColor: Color = MyTheme.Colors.backgroundSecondary,
    val cornerRadius: Float = 12f,
    val textStyle: TextStyle = MyTheme.CaptionMedium,
    val shadowElevation: Int = 2
)

@Composable
fun SegmentedPicker(
    options: List<SegmentedOption>,
    modifier: Modifier = Modifier,
    selectedIndex: Int = 0,
    style: SegmentedPickerStyle = SegmentedPickerStyle(),
    onOptionSelected: (SegmentedOption, Int) -> Unit = { _, _ -> },
) {
    if (options.isEmpty()) return
    var internalSelectedIndex by remember { mutableIntStateOf(selectedIndex) }
    var isInitialPosition by remember { mutableStateOf(true) }

    LaunchedEffect(selectedIndex) {
        internalSelectedIndex = selectedIndex
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    var containerWidth by remember { mutableIntStateOf(0) }
    var containerHeight by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(style.cornerRadius.dp))
            .background(style.backgroundColor)
            .padding(1.dp)
            .onGloballyPositioned { coordinates ->
                containerWidth = coordinates.size.width
                containerHeight = coordinates.size.height
            }
    ) {
        val isHorizontal = style.displayMode == PickerDisplayMode.Horizontal
        
        // Calculate dimensions based on orientation
        val containerSize = if (isHorizontal) containerWidth else containerHeight
        val itemSize = remember(options.size, containerSize) {
            if (options.isNotEmpty() && containerSize > 0) 1f / options.size else 1f
        }
        val optionSizePx = if (containerSize > 0) containerSize * itemSize else 0f
        val positionPx = optionSizePx * internalSelectedIndex

        // Calculate animation position
        val positionDp = with(density) { positionPx.toDp() }
        val targetPosition = if (isHorizontal && layoutDirection == LayoutDirection.Rtl) {
            // For RTL horizontal mode, calculate position from the right side
            with(density) { (containerWidth - optionSizePx - positionPx).toDp() }
        } else {
            positionDp
        }

        val animatedPosition by animateDpAsState(
            targetValue = targetPosition,
            animationSpec = if (isInitialPosition) {
                // Use immediate positioning for initial composition
                tween(durationMillis = 0)
            } else {
                // Use animation for user interactions
                tween(durationMillis = 200)
            },
            label = "thumbPosition",
            finishedListener = {
                if (isInitialPosition) {
                    isInitialPosition = false
                }
            }
        )
        
        // Draw dividers between options
        if (isHorizontal) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                options.forEachIndexed { index, _ ->
                    if (index < options.size - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(0.6.dp)
                                .padding(vertical = 12.dp)
                                .background(MyTheme.Colors.divider)
                                .align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }

        // Draw the animated selection indicator
        if (containerSize > 0) {
            Surface(
                shape = RoundedCornerShape((style.cornerRadius - 2).dp),
                color = style.thumbColor,
                shadowElevation = style.shadowElevation.dp,
                modifier = Modifier
                    .then(
                        if (isHorizontal) {
                            Modifier
                                .width(with(density) { optionSizePx.toDp() })
                                .fillMaxHeight()
                                .offset(x = animatedPosition)
                        } else {
                            Modifier
                                .height(with(density) { optionSizePx.toDp() })
                                .fillMaxWidth()
                                .offset(y = animatedPosition)
                        }
                    )
            ) { }
        }

        // Draw the options
        if (isHorizontal) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                options.forEachIndexed { index, option ->
                    OptionContent(
                        option = option,
                        isSelected = index == internalSelectedIndex,
                        textStyle = style.textStyle,
                        onSelect = {
                            isInitialPosition = false
                            internalSelectedIndex = index
                            onOptionSelected(option, index)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                options.forEachIndexed { index, option ->
                    OptionContent(
                        option = option,
                        isSelected = index == internalSelectedIndex,
                        textStyle = style.textStyle,
                        onSelect = {
                            isInitialPosition = false
                            internalSelectedIndex = index
                            onOptionSelected(option, index)
                        },
                        modifier = Modifier.weight(1f),
                        isHorizontal = false
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionContent(
    option: SegmentedOption,
    isSelected: Boolean,
    textStyle: TextStyle,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    isHorizontal: Boolean = true
) {
    Box(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onSelect
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.then(
                if (isHorizontal) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()
            )
        ) {
            option.icon?.let {
                Icon(
                    painter = painterResource(id = it),
                    contentDescription = null,
                    tint = if (isSelected) Color.Unspecified else MyTheme.Colors.textPrimary.copy(alpha = 0.4f),
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            Text(
                text = option.title,
                color = if (isSelected) MyTheme.Colors.textPrimary else MyTheme.Colors.textPrimary.copy(alpha = 0.4f),
                style = textStyle,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SegmentedPickerPreview() {
    Surface(color = colorResource(id = R.color.background_primary)) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            val options = listOf(
                SegmentedOption("Online"),
                SegmentedOption("Nearby"),
                SegmentedOption("All")
            )

            var selectedIndex by remember { mutableIntStateOf(0) }

            SegmentedPicker(
                options = options,
                selectedIndex = selectedIndex,
                onOptionSelected = { _, index ->
                    selectedIndex = index
                },
                modifier = Modifier.fillMaxWidth().height(38.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            val optionsWithIcons = listOf(
                SegmentedOption("Dash", R.drawable.ic_dash_d_black),
                SegmentedOption("BTC", R.drawable.ic_dash_d_black),
                SegmentedOption("ETH", R.drawable.ic_dash_d_black)
            )

            var selectedIconIndex by remember { mutableIntStateOf(1) }

            SegmentedPicker(
                options = optionsWithIcons,
                selectedIndex = selectedIconIndex,
                onOptionSelected = { _, index ->
                    selectedIconIndex = index
                },
                modifier = Modifier.fillMaxWidth().height(38.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            val twoOptions = listOf(
                SegmentedOption("Yes"),
                SegmentedOption("No")
            )

            var selectedTwoOptionIndex by remember { mutableIntStateOf(0) }

            SegmentedPicker(
                options = twoOptions,
                selectedIndex = selectedTwoOptionIndex,
                onOptionSelected = { _, index ->
                    selectedTwoOptionIndex = index
                },
                modifier = Modifier.fillMaxWidth().height(38.dp)
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            Text(
                text = "Vertical Picker",
                style = MyTheme.OverlineMedium.copy(textAlign = TextAlign.Center),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            val customStyle = SegmentedPickerStyle(
                displayMode = PickerDisplayMode.Vertical,
                backgroundColor = MyTheme.Colors.gray400.copy(alpha = 0.15f),
                thumbColor = MyTheme.Colors.dashBlue,
                cornerRadius = 16f
            )
            
            SegmentedPicker(
                options = options,
                selectedIndex = selectedIndex,
                style = customStyle,
                onOptionSelected = { _, index ->
                    selectedIndex = index
                },
                modifier = Modifier.width(120.dp).height(120.dp)
            )
        }
    }
}