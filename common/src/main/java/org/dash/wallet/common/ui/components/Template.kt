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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R

@Composable
fun Template(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .background(
                color = Color.Transparent,
                shape = CircleShape
            )
            .border(
                width = 1.5.dp,
                color = MyTheme.Colors.gray300.copy(alpha = 0.30f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier,
                tint = MyTheme.Colors.textPrimary
            )
        } else {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(
                        color = MyTheme.Colors.textPrimary,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
@Preview
private fun TemplatePreview() {
    Box(Modifier
        .size(44.dp)
        .background(MyTheme.Colors.backgroundPrimary),
        contentAlignment = Alignment.Center
    ) {
        Template(
            Modifier,
            ImageVector.vectorResource(R.drawable.ic_menu_chevron)
        )
    }
}