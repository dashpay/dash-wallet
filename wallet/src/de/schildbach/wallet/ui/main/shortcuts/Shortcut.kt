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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.ui.components.MyTheme

@Composable
fun Shortcut(
    shortcutOption: ShortcutOption,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = modifier
    ) {
        Image(
            painter = painterResource(id = shortcutOption.iconResId),
            contentDescription = stringResource(id = shortcutOption.textResId),
            modifier = Modifier
                .size(42.dp)
                .padding(6.dp)
        )

        Text(
            text = stringResource(id = shortcutOption.textResId),
            style = MyTheme.Overline,
            color = MyTheme.Colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp, bottom = 6.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewShortcutPaneItem() {
    Shortcut(
        shortcutOption = ShortcutOption.RECEIVE
    )
} 