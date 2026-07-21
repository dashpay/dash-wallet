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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

// Figma: colors/gray/gray400/gray400alpha10 — the search field background.
private val SearchFieldBackground = Color(0x1A75808A)

// Figma: colors/gray/black/black1000alpha30 — placeholder text.
private val SearchPlaceholderColor = Color(0x4D0A0B0D)

/**
 * Design-system search field.
 *
 * Mirrors the "search - states" component in the Android design system
 * (Figma node 4249-12620). Two optional affordances:
 *  - **Clear** ("x") icon inside the field — shown when [showClearButton] is true and
 *    [query] is non-empty; tapping it clears the text via [onQueryChange].
 *  - **Cancel** button to the right of the field — shown only when [onCancel] is non-null
 *    (typically while the field is focused).
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search_hint),
    showClearButton: Boolean = true,
    onCancel: (() -> Unit)? = null,
    cancelText: String = stringResource(R.string.button_cancel),
    imeAction: ImeAction = ImeAction.Search,
    onSearch: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SearchFieldBackground),
            singleLine = true,
            textStyle = MyTheme.Body2Regular.copy(color = MyTheme.Colors.textPrimary),
            cursorBrush = SolidColor(MyTheme.Colors.dashBlue),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = MyTheme.Colors.textTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MyTheme.Body2Regular,
                                color = SearchPlaceholderColor
                            )
                        }
                        innerTextField()
                    }
                    if (showClearButton && query.isNotEmpty()) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clear_input),
                            contentDescription = stringResource(R.string.button_clear),
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onQueryChange("") }
                        )
                    }
                }
            }
        )

        if (onCancel != null) {
            Text(
                text = cancelText,
                style = MyTheme.CaptionMedium,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .clickable { onCancel() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393)
@Composable
private fun SearchFieldStatesPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Empty, no Cancel
        SearchField(query = "", onQueryChange = {})
        // Empty, with Cancel (focused)
        SearchField(query = "", onQueryChange = {}, onCancel = {})
        // Filled, with clear + Cancel
        SearchField(query = "some text", onQueryChange = {}, onCancel = {})
        // Filled, no Cancel
        SearchField(query = "some text", onQueryChange = {})
    }
}