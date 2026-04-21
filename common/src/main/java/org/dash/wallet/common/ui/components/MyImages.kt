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

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import org.dash.wallet.common.R

object MyImages {
    val MenuChevron: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_menu_chevron)

    val NavBarClose: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_nav_bar_close)

    val Preview: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_preview)

    val Error: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_error)

    val DashDWhite: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_dash_d_white)
}