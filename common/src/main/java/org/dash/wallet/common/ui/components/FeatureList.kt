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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.dash.wallet.common.R

data class FeatureItem(
    val heading: String,
    val text: String? = null,
    val icon: ImageVector? = null,
    val number: String? = null
)

@Composable
fun FeatureItemNumber(
    number: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .background(
                color = MyTheme.Colors.dashBlue,
                shape = RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number,
            fontSize = 14.sp,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun FeatureSingleItem(
    heading: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    number: String? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .padding(top = 10.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            when {
                number != null -> {
                    FeatureItemNumber(number = number)
                }
                icon != null -> {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MyTheme.Colors.gray300
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .border(
                                width = 2.5.dp,
                                color = MyTheme.Colors.gray300,
                                shape = RoundedCornerShape(5.dp)
                            )
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = heading,
                style = MyTheme.Typeography.TitleSmallMedium,
                color = MyTheme.Colors.textPrimary
            )
            if (text != null) {
                Text(
                    text = text,
                    style = MyTheme.Typeography.BodyMedium,
                    color = MyTheme.Colors.textSecondary
                )
            }
        }
    }
}

@Composable
fun FeatureList(
    items: List<FeatureItem>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items.forEach { item ->
            FeatureSingleItem(
                heading = item.heading,
                text = item.text,
                icon = item.icon,
                number = item.number
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FeatureSingleItemPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(20.dp)
    ) {
        FeatureSingleItem(
            heading = "heading",
            text = "text"
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FeatureListPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(20.dp)
    ) {
        FeatureList(
            items = listOf(
                FeatureItem("heading", "text"),
                FeatureItem("heading", "text"),
                FeatureItem("heading", "text"),
                FeatureItem("heading", "text")
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FeatureListWithIconsPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(20.dp)
    ) {
        FeatureList(
            items = listOf(
                FeatureItem(
                    "Enhanced Security",
                    "Your wallet will use the latest encryption technology",
                    ImageVector.vectorResource(R.drawable.ic_preview)
                ),
                FeatureItem(
                    "Biometric Support",
                    "Unlock your wallet with fingerprint or face recognition",
                    ImageVector.vectorResource(R.drawable.ic_preview)
                ),
                FeatureItem(
                    "PIN Protection",
                    "Set a secure PIN to protect your funds",
                    ImageVector.vectorResource(R.drawable.ic_preview)
                ),
                FeatureItem(
                    "Recovery Options",
                    "Multiple ways to recover your wallet if needed",
                    ImageVector.vectorResource(R.drawable.ic_preview)
                )
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FeatureListWithNumbersPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(20.dp)
    ) {
        FeatureList(
            items = listOf(
                FeatureItem(
                    heading = "Create a secure PIN",
                    text = "Choose a 6-digit PIN that you'll use to unlock your wallet",
                    number = "1"
                ),
                FeatureItem(
                    heading = "Write down your recovery phrase",
                    text = "Save your 12-word recovery phrase in a safe place",
                    number = "2"
                ),
                FeatureItem(
                    heading = "Enable biometric authentication",
                    text = "Use fingerprint or face recognition for quick access",
                    number = "3"
                ),
                FeatureItem(
                    heading = "Start using your wallet",
                    text = "You're all set! Begin sending and receiving Dash",
                    number = "4"
                )
            )
        )
    }
}