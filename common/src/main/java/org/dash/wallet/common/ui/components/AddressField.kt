
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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

/**
 * Figma: `addressField` (Design system - Android, node 7961:1052; states 7961:1062).
 *
 * A crypto-address text field with an optional label above and an optional message below.
 * Visual state is driven by focus, content and [isError]:
 * - **Default** (unfocused, empty): translucent gray background + placeholder + QR-scan icon.
 * - **Focused**: white background with a hairline border; a cursor shows. Empty keeps the QR
 *   icon; with text the trailing icon becomes a clear (✕) button.
 * - **Filled** (unfocused, with text): translucent gray background, no trailing icon.
 * - **Error**: translucent red background and the [message] rendered in red; no trailing icon.
 *
 * The trailing icon is automatic: QR (when empty, calls [onScanClick]) or clear (when non-empty
 * and focused, resets the value via [onValueChange]). Pass [onLongPress] to support long-press
 * to paste.
 */
@Composable
fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    message: String? = null,
    isError: Boolean = false,
    showScanIcon: Boolean = true,
    enabled: Boolean = true,
    onScanClick: () -> Unit = {},
    onLongPress: (() -> Unit)? = null
) {
    // Focus is owned here so the field can switch between its default/filled and focused looks.
    // The rendering lives in the stateless [AddressFieldContent] so previews can force any state.
    var focused by remember { mutableStateOf(false) }

    AddressFieldContent(
        value = value,
        onValueChange = onValueChange,
        focused = focused,
        onFocusChanged = { focused = it },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        message = message,
        isError = isError,
        showScanIcon = showScanIcon,
        enabled = enabled,
        onScanClick = onScanClick,
        onLongPress = onLongPress
    )
}

@Composable
private fun AddressFieldContent(
    value: String,
    onValueChange: (String) -> Unit,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    message: String? = null,
    isError: Boolean = false,
    showScanIcon: Boolean = true,
    enabled: Boolean = true,
    onScanClick: () -> Unit = {},
    onLongPress: (() -> Unit)? = null
) {
    val backgroundColor = when {
        isError -> MyTheme.Colors.red.copy(alpha = 0.1f)
        focused -> MyTheme.Colors.backgroundSecondary
        else -> MyTheme.Colors.gray300.copy(alpha = 0.1f)
    }
    val borderColor = if (focused && !isError) {
        MyTheme.Colors.gray300.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MyTheme.Body2Medium,
                color = MyTheme.Colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor)
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .then(
                    if (onLongPress != null) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onLongPress = { onLongPress() })
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(start = 20.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 15.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    textStyle = MyTheme.Body2Regular.copy(color = MyTheme.Colors.textPrimary),
                    cursorBrush = SolidColor(MyTheme.Colors.textPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                )

                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MyTheme.Body2Regular,
                        color = MyTheme.Colors.textPrimary.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Trailing icon: clear (✕) when there's text and the field is focused, otherwise the
            // QR-scan affordance while empty. None in the error/filled states (matches the design).
            val trailing: Pair<Int, () -> Unit>? = when {
                isError -> null
                value.isNotEmpty() && focused -> R.drawable.ic_clear_input to { onValueChange("") }
                value.isEmpty() && showScanIcon -> R.drawable.ic_scan_qr to onScanClick
                else -> null
            }

            if (trailing != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = trailing.second),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(trailing.first),
                        contentDescription = null,
                        tint = MyTheme.Colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (message != null) {
            Text(
                text = message,
                style = MyTheme.Body2Regular,
                color = if (isError) MyTheme.Colors.red else MyTheme.Colors.textSecondary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// Previews mirror the six examples in Figma node 7961:1062 (Design system - Android). They render
// the stateless [AddressFieldContent] directly so the focused states (which depend on real focus at
// runtime) can be shown statically.

/** Default — the state where the user doesn't interact with the field. */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
private fun AddressFieldDefaultPreview() {
    AddressFieldContent(
        value = "",
        onValueChange = {},
        focused = false,
        onFocusChanged = {},
        label = "Address",
        placeholder = "Long press to paste"
    )
}

/** Pressed — the user tapped on the field (focused, empty). */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
private fun AddressFieldPressedPreview() {
    AddressFieldContent(
        value = "",
        onValueChange = {},
        focused = true,
        onFocusChanged = {},
        label = "Address",
        placeholder = "Long press to paste"
    )
}

/** Entered — the user entered something in the field (focused, short text → clear icon). */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
private fun AddressFieldEnteredShortPreview() {
    AddressFieldContent(
        value = "TJvRMiThoqM",
        onValueChange = {},
        focused = true,
        onFocusChanged = {},
        label = "Address"
    )
}

/** Entered — the user entered something in the field (focused, long text wraps). */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
private fun AddressFieldEnteredLongPreview() {
    AddressFieldContent(
        value = "TJvRMiThoqMM97PnnA4qCAx7XQo8wNxjY3",
        onValueChange = {},
        focused = true,
        onFocusChanged = {},
        label = "Address"
    )
}

/** Error — the error message appears below the field in red; the field background is tinted red. */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
private fun AddressFieldErrorPreview() {
    AddressFieldContent(
        value = "TJvRMiThoqMM97PnnA4qCAx7XQo8wNxjY3",
        onValueChange = {},
        focused = false,
        onFocusChanged = {},
        label = "Address",
        message = "BTC address is not valid",
        isError = true
    )
}

/** Filled — the user tapped outside the field area (unfocused, has text, no icon). */
@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
private fun AddressFieldFilledPreview() {
    AddressFieldContent(
        value = "TJvRMiThoqMM97PnnA4qCAx7XQo8wNxjY3",
        onValueChange = {},
        focused = false,
        onFocusChanged = {},
        label = "Address"
    )
}