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
package de.schildbach.wallet.ui.main.shortcuts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.ui.components.MyTheme.Colors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortcutsPane(
    shortcuts: List<ShortcutOption>,
    onClick: (ShortcutOption) -> Unit,
    onLongClick: (ShortcutOption, Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(unbounded = true)
            .clip(RoundedCornerShape(12.dp))
            .background(Colors.backgroundSecondary)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) {
            val itemWidth = 1f / shortcuts.size
            shortcuts.forEachIndexed { index, shortcut ->
                Shortcut(
                    shortcutOption = shortcut,
                    modifier = Modifier
                        .weight(itemWidth)
                        .padding(6.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = rememberRipple(color = Colors.textPrimary, bounded = false, radius = 50.dp),
                            onClick = { onClick(shortcut) },
                            onLongClick = { onLongClick(shortcut, index) },
                        )
                )
            }
        }
    }
}
