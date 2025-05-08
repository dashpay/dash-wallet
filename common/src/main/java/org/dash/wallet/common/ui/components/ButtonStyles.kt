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

import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import org.dash.wallet.common.R

object ButtonStyles {
    @Composable
    fun whiteWithBlueText() = ButtonDefaults.buttonColors(
        containerColor = colorResource(id = R.color.dash_white),
        contentColor = colorResource(id = R.color.dash_blue),
        disabledContainerColor = colorResource(id = R.color.disabled_button_bg),
        disabledContentColor = colorResource(id = R.color.dash_blue)
    )

    @Composable
    fun white10WithWhiteText() = ButtonDefaults.buttonColors(
        containerColor = colorResource(id = R.color.dash_white_0_10),
        contentColor = colorResource(id = R.color.dash_white),
        disabledContainerColor = colorResource(id = R.color.disabled_button_bg),
        disabledContentColor = colorResource(id = R.color.dash_blue)
    )
}