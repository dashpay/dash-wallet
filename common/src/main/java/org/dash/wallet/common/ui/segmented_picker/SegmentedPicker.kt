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

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
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
    // Null means "resolve from the active theme" — see SegmentedPicker. Hardcoding a color here
    // would freeze it to the light scheme (data-class defaults can't read LocalDashColors).
    val backgroundColor: Color? = null,
    val thumbColor: Color? = null,
    val cornerRadius: Float = 12f,
    val textStyle: TextStyle = MyTheme.Typography.FootnoteMedium,
    val shadowElevation: Int = 2,
    // Extra inset drawn around each option's text/icon, on top of the option's own weighted
    // slot. Zero by default so existing fixed-height horizontal/vertical toggles are unaffected;
    // set this for pickers whose options should have visible breathing room between them.
    val optionPaddingHorizontal: Float = 0f,
    val optionPaddingVertical: Float = 0f,
    // Inset between the container edge and the thumb/options. The defaults below keep the
    // original look (1dp inset, thumb corner = cornerRadius - 2, dividers, icon left of title).
    val containerPadding: Float = 1f,
    // Null means cornerRadius - 2, the original thumb shape.
    val thumbCornerRadius: Float? = null,
    val showDividers: Boolean = true,
    // Stack each option's icon above its title (e.g. the Advanced payments tabs, Figma 7062:49306)
    // instead of placing it to the left.
    val iconAboveTitle: Boolean = false,
    // Size each option to its own content (plus optionPaddingHorizontal) and start-align them,
    // scrolling horizontally when they overflow, instead of splitting the width equally. The
    // thumb then follows the selected option's measured bounds (e.g. the Receive balance tabs,
    // Figma 7062:49313). Horizontal mode only; off by default.
    val fitOptionsToContent: Boolean = false
)

@Composable
fun SegmentedPicker(
    options: List<SegmentedOption>,
    modifier: Modifier = Modifier,
    selectedIndex: Int = 0,
    // When false, no option is rendered as current: no thumb, uniform text color. For pickers
    // whose options are alternatives to switch to rather than a persistent selection.
    showSelection: Boolean = true,
    style: SegmentedPickerStyle = SegmentedPickerStyle(),
    onOptionSelected: (SegmentedOption, Int) -> Unit = { _, _ -> },
) {
    if (options.isEmpty()) return
    if (style.fitOptionsToContent && style.displayMode == PickerDisplayMode.Horizontal) {
        FitContentSegmentedPicker(options, modifier, selectedIndex, showSelection, style, onOptionSelected)
        return
    }
    var internalSelectedIndex by remember { mutableIntStateOf(selectedIndex) }
    var isInitialPosition by remember { mutableStateOf(true) }

    LaunchedEffect(selectedIndex) {
        internalSelectedIndex = selectedIndex
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    // Resolve theme-aware defaults so the picker tracks light/dark; explicit style colors win.
    val colors = LocalDashColors.current
    val backgroundColor = style.backgroundColor ?: colors.gray400.copy(alpha = 0.1f)
    val thumbColor = style.thumbColor ?: colors.backgroundSecondary

    var containerWidth by remember { mutableIntStateOf(0) }
    var containerHeight by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(style.cornerRadius.dp))
            .background(backgroundColor)
            .padding(style.containerPadding.dp)
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
        if (isHorizontal && style.showDividers) {
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
                                .background(colors.divider)
                                .align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }

        // Draw the animated selection indicator
        if (containerSize > 0 && showSelection) {
            SelectionThumb(
                style = style,
                color = thumbColor,
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
            )
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
                        isSelected = showSelection && index == internalSelectedIndex,
                        textStyle = style.textStyle,
                        onSelect = {
                            isInitialPosition = false
                            internalSelectedIndex = index
                            onOptionSelected(option, index)
                        },
                        modifier = Modifier.weight(1f),
                        paddingHorizontal = style.optionPaddingHorizontal.dp,
                        paddingVertical = style.optionPaddingVertical.dp,
                        iconAboveTitle = style.iconAboveTitle
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
                        isSelected = showSelection && index == internalSelectedIndex,
                        textStyle = style.textStyle,
                        onSelect = {
                            isInitialPosition = false
                            internalSelectedIndex = index
                            onOptionSelected(option, index)
                        },
                        modifier = Modifier.weight(1f),
                        isHorizontal = false,
                        paddingHorizontal = style.optionPaddingHorizontal.dp,
                        paddingVertical = style.optionPaddingVertical.dp,
                        iconAboveTitle = style.iconAboveTitle
                    )
                }
            }
        }
    }
}

/**
 * [SegmentedPicker] with [SegmentedPickerStyle.fitOptionsToContent]: options keep their own
 * width, start-aligned, and scroll horizontally when they do not fit. The thumb is drawn under
 * the selected option's measured bounds.
 */
@Composable
private fun FitContentSegmentedPicker(
    options: List<SegmentedOption>,
    modifier: Modifier,
    selectedIndex: Int,
    showSelection: Boolean,
    style: SegmentedPickerStyle,
    onOptionSelected: (SegmentedOption, Int) -> Unit
) {
    var internalSelectedIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) {
        internalSelectedIndex = selectedIndex
    }

    val density = LocalDensity.current
    val colors = LocalDashColors.current
    val backgroundColor = style.backgroundColor ?: colors.gray400.copy(alpha = 0.1f)
    val thumbColor = style.thumbColor ?: colors.backgroundSecondary

    // Each option's x offset and width inside the row, in px; filled in on layout.
    val bounds = remember(options.size) {
        mutableStateListOf<Pair<Int, Int>?>().apply { repeat(options.size) { add(null) } }
    }
    val selectedBounds = bounds.getOrNull(internalSelectedIndex)
    var hasPlacedThumb by remember { mutableStateOf(false) }
    val animationSpec = tween<Dp>(durationMillis = if (hasPlacedThumb) 200 else 0)
    val thumbOffset by animateDpAsState(
        targetValue = with(density) { (selectedBounds?.first ?: 0).toDp() },
        animationSpec = animationSpec,
        label = "fitThumbOffset",
        finishedListener = { hasPlacedThumb = true }
    )
    val thumbWidth by animateDpAsState(
        targetValue = with(density) { (selectedBounds?.second ?: 0).toDp() },
        animationSpec = animationSpec,
        label = "fitThumbWidth"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(style.cornerRadius.dp))
            .background(backgroundColor)
            .padding(style.containerPadding.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        Box(modifier = Modifier.fillMaxHeight()) {
            if (showSelection && selectedBounds != null) {
                SelectionThumb(
                    style = style,
                    color = thumbColor,
                    modifier = Modifier
                        .absoluteOffset(x = thumbOffset)
                        .width(thumbWidth)
                        .fillMaxHeight()
                )
            }
            Row(modifier = Modifier.fillMaxHeight()) {
                options.forEachIndexed { index, option ->
                    OptionContent(
                        option = option,
                        isSelected = showSelection && index == internalSelectedIndex,
                        textStyle = style.textStyle,
                        onSelect = {
                            hasPlacedThumb = true
                            internalSelectedIndex = index
                            onOptionSelected(option, index)
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .onGloballyPositioned { coordinates ->
                                val entry = coordinates.positionInParent().x.toInt() to coordinates.size.width
                                if (index < bounds.size && bounds[index] != entry) {
                                    bounds[index] = entry
                                }
                            },
                        paddingHorizontal = style.optionPaddingHorizontal.dp,
                        paddingVertical = style.optionPaddingVertical.dp,
                        iconAboveTitle = style.iconAboveTitle
                    )
                }
            }
        }
    }
}

/** The selected option's background; [modifier] sizes and places it. */
@Composable
private fun SelectionThumb(style: SegmentedPickerStyle, color: Color, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape((style.thumbCornerRadius ?: (style.cornerRadius - 2)).dp),
        color = color,
        shadowElevation = style.shadowElevation.dp,
        modifier = modifier
    ) { }
}

@Composable
private fun OptionContent(
    option: SegmentedOption,
    isSelected: Boolean,
    textStyle: TextStyle,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    isHorizontal: Boolean = true,
    paddingHorizontal: Dp = 0.dp,
    paddingVertical: Dp = 0.dp,
    iconAboveTitle: Boolean = false
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
        val colors = LocalDashColors.current
        val icon: @Composable (Modifier) -> Unit = { iconModifier ->
            option.icon?.let {
                Icon(
                    painter = painterResource(id = it),
                    contentDescription = null,
                    tint = if (isSelected) Color.Unspecified else colors.textPrimary.copy(alpha = 0.4f),
                    modifier = iconModifier
                )
            }
        }
        val title: @Composable () -> Unit = {
            Text(
                text = option.title,
                color = if (isSelected) colors.textPrimary else colors.textPrimary.copy(alpha = 0.4f),
                style = textStyle,
                textAlign = TextAlign.Center
            )
        }
        val contentModifier = Modifier
            .then(if (isHorizontal) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical)

        if (iconAboveTitle) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = contentModifier
            ) {
                icon(Modifier.padding(bottom = 6.dp))
                title()
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = contentModifier
            ) {
                icon(Modifier.padding(end = 6.dp))
                title()
            }
        }
    }
}

@Preview(name = "Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SegmentedPickerPreview() {
    DashWalletTheme {
        SegmentedPickerPreviewContent()
    }
}

@Composable
private fun SegmentedPickerPreviewContent() {
    val colors = LocalDashColors.current
    Surface(color = colors.backgroundPrimary) {
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
                backgroundColor = colors.gray400.copy(alpha = 0.15f),
                thumbColor = colors.dashBlue,
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