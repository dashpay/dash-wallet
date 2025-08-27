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

import android.media.Image
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

@Composable
fun TopNavBase(
    modifier: Modifier = Modifier,
    leadingPart: Boolean = true,
    leadingIcon: ImageVector? = null,
    leadingText: String? = null,
    leadingContentDescription: String? = null,
    onLeadingClick: (() -> Unit)? = null,
    trailingPart: Boolean = true,
    trailingIcon: ImageVector? = null,
    trailingText: String? = null,
    trailingContentDescription: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    centralPart: Boolean = true,
    title: String = "Label"
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading Touch Area
        Box(
            modifier = Modifier
                .size(44.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (leadingPart) {
                if (leadingText != null) {
                    Box(
                        modifier = if (onLeadingClick != null) {
                            Modifier.clickable { onLeadingClick() }
                        } else {
                            Modifier
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = leadingText,
                            style = MyTheme.Body2Regular,
                            color = MyTheme.Colors.textPrimary
                        )
                    }
                } else {
                    Template(
                        modifier = Modifier
                            .size(34.dp)
                            .then(
                                if (onLeadingClick != null) {
                                    Modifier.clickable { onLeadingClick() }
                                } else {
                                    Modifier
                                }
                            ),
                        icon = leadingIcon,
                        contentDescription = leadingContentDescription
                    )
                }
            }
        }

        // Central Area
        if (centralPart) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Label(text = title)
            }
        }

        // Trailing Touch Area
        Box(
            modifier = Modifier
                .size(44.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (trailingPart) {
                if (trailingText != null) {
                    Box(
                        modifier = if (onTrailingClick != null) {
                            Modifier.clickable { onTrailingClick() }
                        } else {
                            Modifier
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = trailingText,
                            style = MyTheme.Body2Regular,
                            color = MyTheme.Colors.dashBlue
                        )
                    }
                } else {
                    Template(
                        modifier = Modifier
                            .size(34.dp)
                            .then(
                                if (onTrailingClick != null) {
                                    Modifier.clickable { onTrailingClick() }
                                } else {
                                    Modifier
                                }
                            ),
                        icon = trailingIcon,
                        contentDescription = trailingContentDescription
                    )
                }
            }
        }
    }
}

@Composable
@Preview
fun TopNavBasePreview() {
    Column(
        modifier = Modifier.padding(16.dp)
            .background(MyTheme.Colors.backgroundPrimary),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Variant 1: Back button with trailing dot
        TopNavBase(
            title = "Label",
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_chevron),
            onLeadingClick = { /* handle back */ },
            trailingPart = true,
            onTrailingClick = { /* handle trailing */ }
        )

        // Variant 2: Back button only
        TopNavBase(
            title = "Label",
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_chevron),
            onLeadingClick = { /* handle back */ },
            trailingPart = false
        )

        // Variant 3: Back button with info icon
        TopNavBase(
            title = "Label",
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_chevron),
            onLeadingClick = { /* handle back */ },
            trailingIcon = Icons.Default.Info,
            onTrailingClick = { /* handle info */ }
        )

        // Variant 4: No leading, with close icon
        TopNavBase(
            title = "Label",
            leadingPart = false,
            trailingIcon = Icons.Default.Close,
            onTrailingClick = { /* handle close */ }
        )

        // Variant 5: Back button with add icon
        TopNavBase(
            title = "Label",
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_chevron),
            onLeadingClick = { /* handle back */ },
            trailingIcon = Icons.Default.Add,
            onTrailingClick = { /* handle add */ }
        )

        // Variant 6: Cancel/Apply text buttons
        TopNavBase(
            title = "Label",
            leadingText = "Cancel",
            onLeadingClick = { /* handle cancel */ },
            trailingText = "Apply",
            onTrailingClick = { /* handle apply */ }
        )

        // Variant 7: Back with text button
        TopNavBase(
            title = "Label",
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_chevron),
            onLeadingClick = { /* handle back */ },
            trailingText = "Quick voting"
        )

        // Variant 8: Title only
        TopNavBase(
            title = "Label",
            leadingPart = false,
            trailingPart = false
        )

        // Variant 9: Back button only, no title
        TopNavBase(
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_chevron),
            onLeadingClick = { /* handle back */ },
            centralPart = false,
            trailingPart = false
        )
    }
}