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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.dash.wallet.common.R

@Composable
fun FeatureTopText(
    heading: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    showText: Boolean = true,
    showButton: Boolean = false,
    buttonLabel: String? = null,
    buttonLeadingIcon: ImageVector? = null,
    buttonTrailingIcon: ImageVector? = null,
    onButtonClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = heading,
            style = MyTheme.Typeography.HeadlineSmallBold,
            color = MyTheme.Colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        if (showText && text != null) {
            Text(
                text = text,
                style = MyTheme.Typeography.BodyMedium,
                color = MyTheme.Colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (showButton && buttonLabel != null) {
            TextButton(
                onClick = { onButtonClick?.invoke() },
                modifier = Modifier,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (buttonLeadingIcon != null) {
                        Icon(
                            imageVector = buttonLeadingIcon,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MyTheme.Colors.dashBlue
                        )
                    }

                    Text(
                        text = buttonLabel,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MyTheme.Colors.dashBlue,
                        textAlign = TextAlign.Center
                    )

                    if (buttonTrailingIcon != null) {
                        Icon(
                            imageVector = buttonTrailingIcon,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MyTheme.Colors.dashBlue
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FeatureTopTextPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        FeatureTopText(
            heading = "heading",
            text = "text",
            showText = true,
            showButton = true,
            buttonLabel = "Label",
            buttonLeadingIcon = ImageVector.vectorResource(R.drawable.ic_preview),
            buttonTrailingIcon = ImageVector.vectorResource(R.drawable.ic_preview),
            onButtonClick = { }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FeatureTopTextNoButtonPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        FeatureTopText(
            heading = "Security Upgrade",
            text = "Your wallet security will be upgraded to a more secure encryption system",
            showText = true,
            showButton = false
        )
    }
}