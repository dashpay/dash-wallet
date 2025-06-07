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

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.dash.wallet.common.R

@Composable
fun ButtonLarge(
    onClick: () -> Unit,
    modifier: Modifier,
    colors: ButtonColors,
    @StringRes
    textId: Int,
    enabled: Boolean = true
) {
    Button(
        onClick = { onClick.invoke() },
        enabled = enabled,
        modifier = modifier
            .height(46.dp), // from Button.Primary.Large
        colors = colors,
        contentPadding = PaddingValues(20.dp, 12.dp),
        shape = RoundedCornerShape(10.dp) // from PrimaryButtonTheme.Large
    ) {
        Text(
            text = stringResource(id = textId),
            style = TextStyle(
                fontSize = 15.sp,
                fontFamily = FontFamily(Font(R.font.inter_semibold))
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Preview
@Composable
fun ButtonLargePreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorResource(R.color.dash_blue))
            .padding(20.dp, 10.dp, 20.dp, 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
    ButtonLarge(
        onClick = {  },
        modifier = Modifier
            .fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(id = R.color.dash_white),
            contentColor = colorResource(id = R.color.dash_blue),
            disabledContainerColor = colorResource(id = R.color.disabled_button_bg),
            disabledContentColor = colorResource(id = R.color.dash_blue)
        ),
        R.string.button_okay
    )

        ButtonLarge(
            onClick = { },
            modifier = Modifier
                .fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(id = R.color.gray),
                contentColor = colorResource(id = R.color.dash_black),
                disabledContainerColor = colorResource(id = R.color.disabled_button_bg),
                disabledContentColor = colorResource(id = R.color.dash_blue)
            ),
            R.string.network_unavailable_balance_not_accurate
        )
}
}
