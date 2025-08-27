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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TopIntro(
    heading: String,
    text: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Heading
        Text(
            text = heading,
            style = MyTheme.H5Bold,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.fillMaxWidth()
        )
        
        // Optional text
        text?.let {
            Text(
                text = it,
                style = MyTheme.Body2Regular,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
@Preview
fun TopIntroPreview() {
    Column(
        modifier = Modifier.padding(16.dp)
            .background(MyTheme.Colors.backgroundPrimary),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // With heading and text
        TopIntro(
            heading = "Heading",
            text = "Text"
        )
        
        // Heading only
        TopIntro(
            heading = "Heading Only"
        )
        
        // Longer examples
        TopIntro(
            heading = "Welcome to Dash",
            text = "Your digital cash for everyday payments"
        )
    }
}