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

package org.dash.wallet.common.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

/**
 * Figma: `TextField-Base` (Design system - Android, node 4111:12913; variants 4112:13707).
 *
 * General-purpose design-system text field with a floating [label] inside the field.
 * Visual state is driven by focus, content, [isError] and [enabled]:
 * - **Default** (unfocused, empty): translucent gray background; the [label] renders on the
 *   text line, acting as the placeholder.
 * - **Focused**: white background with a 1dp dash-blue border and a 3dp translucent blue
 *   focus ring; a cursor shows.
 * - **Typing** (focused, with text): as focused; the [label] shrinks to a small line above
 *   the text and a trailing clear (✕) button appears (disable with [showClearButton]).
 * - **Filled** (unfocused, with text): translucent gray background, small label above the text.
 * - **Error** ([isError]): red border on a translucent red background; the [message] below
 *   renders in red.
 * - **Disabled** ([enabled] = false): content renders at reduced opacity and input is ignored.
 *
 * Slots (matching the Figma component's properties):
 * - [label] — floating label inside the field (Figma `label`).
 * - [placeholder] — text-line placeholder when empty; only used when [label] is null.
 * - [helperTextInside] — right-aligned small text inside the field, below the text line
 *   (Figma `helpTextInside`). When null and [maxLength] is set, a `n/max` counter renders here.
 * - [message] — help text below the field (Figma `helpTextOutside`); red when [isErrorMessage]
 *   (which defaults to [isError] — pass `isErrorMessage = false` to keep an error-styled field
 *   with neutral gray help text, as some Figma error variants show).
 * - [trailingIcon] + [onTrailingIconClick] — custom trailing button (Figma `buttonIcon`).
 *   The automatic clear button takes precedence over it while typing.
 * - [maxLength] enforces a character limit.
 *
 * All user-visible strings are caller-provided so they can come from string resources.
 */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helperTextInside: String? = null,
    message: String? = null,
    isError: Boolean = false,
    isErrorMessage: Boolean = isError,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLength: Int? = null,
    showClearButton: Boolean = true,
    @DrawableRes trailingIcon: Int? = null,
    onTrailingIconClick: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    visualTransformation: VisualTransformation = VisualTransformation.None,
    // Optional: lets callers focus the field programmatically (e.g. auto-open the keyboard).
    focusRequester: FocusRequester? = null,
    // Optional: invoked when the keyboard's IME action (Done/Go/Next/Search/Send) is pressed.
    onImeAction: (() -> Unit)? = null
) {
    // Focus is owned here so the field can switch between its default/filled and focused looks.
    // The rendering lives in the stateless [TextFieldContent] so previews can force any state.
    var focused by remember { mutableStateOf(false) }

    TextFieldContent(
        value = value,
        onValueChange = onValueChange,
        focused = focused,
        onFocusChanged = { focused = it },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        helperTextInside = helperTextInside,
        message = message,
        isError = isError,
        isErrorMessage = isErrorMessage,
        enabled = enabled,
        singleLine = singleLine,
        maxLength = maxLength,
        showClearButton = showClearButton,
        trailingIcon = trailingIcon,
        onTrailingIconClick = onTrailingIconClick,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        focusRequester = focusRequester,
        onImeAction = onImeAction
    )
}

@Composable
private fun TextFieldContent(
    value: String,
    onValueChange: (String) -> Unit,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helperTextInside: String? = null,
    message: String? = null,
    isError: Boolean = false,
    isErrorMessage: Boolean = isError,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLength: Int? = null,
    showClearButton: Boolean = true,
    @DrawableRes trailingIcon: Int? = null,
    onTrailingIconClick: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    onImeAction: (() -> Unit)? = null
) {
    // Figma: default/filled = gray400 @ 10%, focused = white + dash-blue border + 3dp blue ring,
    // error = red @ 5% + red border. Error wins over focused.
    val backgroundColor = when {
        isError -> MyTheme.Colors.red5
        focused && enabled -> MyTheme.Colors.backgroundSecondary
        else -> MyTheme.Colors.gray400.copy(alpha = 0.1f)
    }
    val borderColor = when {
        isError -> MyTheme.Colors.red
        focused && enabled -> MyTheme.Colors.dashBlue
        else -> Color.Transparent
    }
    val showFocusRing = focused && enabled && !isError
    val ringColor = MyTheme.Colors.dashBlue.copy(alpha = 0.1f)
    val contentAlpha = if (enabled) 1f else 0.4f
    val shape = RoundedCornerShape(16.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                // The focus ring sits outside the field bounds (Figma `element/active` shadow,
                // 3px spread) — drawn before clipping so it isn't cut off and doesn't shift layout.
                .drawBehind {
                    if (showFocusRing) {
                        val ring = 3.dp.toPx()
                        drawRoundRect(
                            color = ringColor,
                            topLeft = Offset(-ring / 2, -ring / 2),
                            size = Size(size.width + ring, size.height + ring),
                            cornerRadius = CornerRadius(16.dp.toPx() + ring / 2),
                            style = Stroke(width = ring)
                        )
                    }
                }
                .clip(shape)
                .background(backgroundColor)
                .border(1.dp, borderColor, shape)
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Floating label: small line above the text once there's content.
                    if (label != null && value.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MyTheme.Typography.LabelMedium,
                            color = MyTheme.Colors.textSecondary.copy(alpha = contentAlpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Box {
                        BasicTextField(
                            value = value,
                            onValueChange = { newValue ->
                                onValueChange(if (maxLength != null) newValue.take(maxLength) else newValue)
                            },
                            enabled = enabled,
                            singleLine = singleLine,
                            textStyle = MyTheme.Typography.TitleSmall.copy(
                                color = MyTheme.Colors.textPrimary.copy(alpha = contentAlpha)
                            ),
                            cursorBrush = SolidColor(MyTheme.Colors.textPrimary),
                            keyboardOptions = keyboardOptions,
                            keyboardActions = if (onImeAction != null) {
                                KeyboardActions(
                                    onDone = { onImeAction() },
                                    onGo = { onImeAction() },
                                    onNext = { onImeAction() },
                                    onSearch = { onImeAction() },
                                    onSend = { onImeAction() }
                                )
                            } else {
                                KeyboardActions.Default
                            },
                            visualTransformation = visualTransformation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { onFocusChanged(it.isFocused) }
                                .then(
                                    if (focusRequester != null) {
                                        Modifier.focusRequester(focusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                        )

                        // When empty, the label renders full-size on the text line as the
                        // placeholder (Figma default/focused states).
                        if (value.isEmpty()) {
                            val overlay = label ?: placeholder
                            if (overlay != null) {
                                Text(
                                    text = overlay,
                                    style = MyTheme.Typography.TitleSmall,
                                    color = MyTheme.Colors.textSecondary.copy(alpha = contentAlpha),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Trailing button: clear (✕) while typing (focused with text), otherwise the
                // caller's custom icon. Figma `touch.area`: 30dp, 8dp radius.
                val trailing: Pair<Int, (() -> Unit)?>? = when {
                    showClearButton && value.isNotEmpty() && focused && enabled ->
                        R.drawable.ic_clear_input to { onValueChange("") }
                    trailingIcon != null -> trailingIcon to onTrailingIconClick
                    else -> null
                }

                if (trailing != null) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (trailing.second != null && enabled) {
                                    Modifier.clickable { trailing.second?.invoke() }
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(trailing.first),
                            contentDescription = null,
                            // ic_clear_input carries its own translucent styling; custom icons
                            // are tinted like other field icons.
                            tint = if (trailing.first == R.drawable.ic_clear_input) {
                                Color.Unspecified
                            } else {
                                MyTheme.Colors.textPrimary.copy(alpha = contentAlpha)
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Inside help text / character counter, right-aligned under the text line.
            val insideText = helperTextInside
                ?: maxLength?.let { "${value.length}/$it" }
            if (insideText != null) {
                Text(
                    text = insideText,
                    style = MyTheme.Typography.BodySmall,
                    color = MyTheme.Colors.textSecondary.copy(alpha = contentAlpha),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 4.dp)
                )
            }
        }

        if (message != null) {
            Text(
                text = message,
                style = MyTheme.Typography.BodySmall,
                color = if (isErrorMessage) {
                    MyTheme.Colors.red
                } else {
                    MyTheme.Colors.textSecondary.copy(alpha = contentAlpha)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .padding(horizontal = 16.dp)
            )
        }
    }
}

// Previews mirror the variant set in Figma node 4112:13707 (Design system - Android). They render
// the stateless [TextFieldContent] directly so the focus-dependent states (which need real focus
// at runtime) can be shown statically.

/** Core states — default, focused, typing, error, filled (the variant-set columns). */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
private fun TextFieldStatesPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Default — the user doesn't interact with the field; the label is the placeholder.
        TextFieldContent(
            value = "",
            onValueChange = {},
            focused = false,
            onFocusChanged = {},
            label = "Label"
        )
        // Focused — blue border + focus ring, cursor shows.
        TextFieldContent(
            value = "",
            onValueChange = {},
            focused = true,
            onFocusChanged = {},
            label = "Label"
        )
        // Typing — focused with text; small label above, clear (✕) button shows.
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = true,
            onFocusChanged = {},
            label = "Label"
        )
        // Error — red border on a translucent red background, red message below.
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = false,
            onFocusChanged = {},
            label = "Label",
            message = "This value is not valid",
            isError = true
        )
        // Filled — the user tapped outside the field (unfocused, has text, no icon).
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = false,
            onFocusChanged = {},
            label = "Label"
        )
    }
}

/** Help text slots — inside (right-aligned), outside, and the character counter. */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
private fun TextFieldHelpTextPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Help text inside the field, right-aligned under the text line.
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = true,
            onFocusChanged = {},
            label = "Label",
            helperTextInside = "Help text"
        )
        // Help text outside, below the field.
        TextFieldContent(
            value = "",
            onValueChange = {},
            focused = false,
            onFocusChanged = {},
            label = "Label",
            message = "Help text"
        )
        // Character counter in the inside slot.
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = true,
            onFocusChanged = {},
            label = "Label",
            maxLength = 25
        )
    }
}

/** Custom trailing icon and disabled states. */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
private fun TextFieldIconDisabledPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Custom trailing button (Figma buttonIcon slot).
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = false,
            onFocusChanged = {},
            label = "Label",
            trailingIcon = R.drawable.ic_scan_qr,
            onTrailingIconClick = {}
        )
        // Disabled — content at reduced opacity, input ignored.
        TextFieldContent(
            value = "",
            onValueChange = {},
            focused = false,
            onFocusChanged = {},
            label = "Label",
            enabled = false
        )
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = false,
            onFocusChanged = {},
            label = "Label",
            enabled = false
        )
    }
}

/** Combinations from the Figma grid — slots and states composed together. */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
private fun TextFieldCombinationsPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Inside + outside help text together (Figma row 4).
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = true,
            onFocusChanged = {},
            label = "Label",
            helperTextInside = "Help text",
            message = "Help text"
        )
        // Error field with neutral gray help text (Figma rows 1-4, error column).
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = false,
            onFocusChanged = {},
            label = "Label",
            message = "Help text",
            isError = true,
            isErrorMessage = false
        )
        // Error field with inside help text.
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = false,
            onFocusChanged = {},
            label = "Label",
            helperTextInside = "Help text",
            isError = true
        )
        // Custom trailing icon while focused and empty (Figma row 6) — the icon stays because
        // the clear button only appears once there's text.
        TextFieldContent(
            value = "",
            onValueChange = {},
            focused = true,
            onFocusChanged = {},
            label = "Label",
            trailingIcon = R.drawable.ic_scan_qr,
            onTrailingIconClick = {}
        )
        // Custom trailing icon in the error state (Figma row 6, error column).
        TextFieldContent(
            value = "Some text",
            onValueChange = {},
            focused = false,
            onFocusChanged = {},
            label = "Label",
            message = "Help text",
            isError = true,
            trailingIcon = R.drawable.ic_scan_qr,
            onTrailingIconClick = {}
        )
    }
}