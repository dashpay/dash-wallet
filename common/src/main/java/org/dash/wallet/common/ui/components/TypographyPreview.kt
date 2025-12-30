/*
 * Copyright 2024 Dash Core Group.
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(showBackground = true)
@Composable
fun TypographyPreview() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundSecondary)
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Display styles
        TypographySection(
            title = "Display Styles"
        ) {
            Text(
                text = "Display large",
                style = MyTheme.Typeography.DisplayLarge,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Display large",
                style = MyTheme.Typeography.DisplayLargeBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Display medium",
                style = MyTheme.Typeography.DisplayMedium,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Display medium",
                style = MyTheme.Typeography.DisplayMediumBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Display small",
                style = MyTheme.Typeography.DisplaySmall,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Display small",
                style = MyTheme.Typeography.DisplaySmallBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Headline styles
        TypographySection(
            title = "Headline Styles"
        ) {
            Text(
                text = "Headline large",
                style = MyTheme.Typeography.HeadlineLarge,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Headline large",
                style = MyTheme.Typeography.HeadlineLargeBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Headline medium",
                style = MyTheme.Typeography.HeadlineMedium,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Headline medium",
                style = MyTheme.Typeography.HeadlineMediumBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Headline small",
                style = MyTheme.Typeography.HeadlineSmallBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Title styles
        TypographySection(
            title = "Title Styles"
        ) {
            Text(
                text = "Title large",
                style = MyTheme.Typeography.TitleLarge,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Title large",
                style = MyTheme.Typeography.TitleLargeBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Title medium",
                style = MyTheme.Typeography.TitleMedium,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Title medium",
                style = MyTheme.Typeography.TitleMediumBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Title small",
                style = MyTheme.Typeography.TitleSmall,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Title small",
                style = MyTheme.Typeography.TitleSmallBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Label styles
        TypographySection(
            title = "Label Styles"
        ) {
            Text(
                text = "Label large",
                style = MyTheme.Typeography.LabelLarge,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Label large",
                style = MyTheme.Typeography.LabelLargeBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Label medium",
                style = MyTheme.Typeography.LabelMedium,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Label medium",
                style = MyTheme.Typeography.LabelMediumBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Label small",
                style = MyTheme.Typeography.LabelSmall,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Label small",
                style = MyTheme.Typeography.LabelSmallBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Body styles
        TypographySection(
            title = "Body Styles"
        ) {
            Text(
                text = "Body large",
                style = MyTheme.Typeography.BodyLarge,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Body large",
                style = MyTheme.Typeography.BodyLargeBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Body medium",
                style = MyTheme.Typeography.BodyMedium,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Body medium",
                style = MyTheme.Typeography.BodyMediumBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Body small",
                style = MyTheme.Typeography.BodySmall,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Body small",
                style = MyTheme.Typeography.BodySmallBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TypographySection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 40.dp)
    ) {
        content()
    }
}