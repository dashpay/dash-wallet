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

package org.dash.wallet.common.ui.enter_amount

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R
import org.dash.wallet.common.ui.components.MyTheme

/**
 * Updates an amount string based on a keyboard key press.
 *
 * Keys: "0"–"9" append a digit; "." appends the decimal separator (guarded against duplicates
 * and exceeding [maxDecimalPlaces]); "back" removes the last character (floor to "0"); "back_long"
 * resets to "0".
 */
fun processAmountKeyInput(current: String, key: String, maxDecimalPlaces: Int = 2): String {
    return when (key) {
        "back" -> if (current.length > 1) current.dropLast(1) else "0"
        "back_long" -> "0"
        "." -> if (current.contains('.')) current else "$current."
        else -> {
            val result = if (current == "0") key else current + key
            val dotIndex = result.indexOf('.')
            if (dotIndex != -1 && result.length - dotIndex - 1 > maxDecimalPlaces) current else result
        }
    }
}

/** 4×3 numeric keyboard for amount entry — fires [onKeyInput] with "0"–"9", ".", "back", or "back_long". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NumericKeyboardCompose(
    modifier: Modifier = Modifier,
    onKeyInput: (String) -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "back")
    )

    Column(
        modifier = modifier
            .background(color = MyTheme.Colors.backgroundSecondary, shape = RoundedCornerShape(32.dp))
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { key ->
                    val isBack = key == "back"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .background(
                                color = MyTheme.Colors.backgroundSecondary,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .combinedClickable(
                                onClick = { onKeyInput(key) },
                                onLongClick = if (isBack) {
                                    { onKeyInput("back_long") }
                                } else null
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_backward),
                                contentDescription = null,
                                tint = MyTheme.Colors.textPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Text(
                                text = key,
                                style = MyTheme.Typography.TitleLarge,
                                color = MyTheme.Colors.textPrimary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Preview
private fun NumericKeyboardPreview() {
    Box(modifier = Modifier.background(MyTheme.Colors.backgroundPrimary)) {
        Column(
            modifier = Modifier
                .width(393.dp)
                .height(336.dp)
                .background(color = MyTheme.Colors.dashBlue)
                .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Top),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NumericKeyboardCompose { }
        }
    }
}