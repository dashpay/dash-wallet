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

package org.dash.wallet.integrations.maya.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.R as CommonR
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.integrations.maya.R

private val MayaLogoBackground = Color(0xFF151D3F)

@Composable
fun MayaPortalScreen(
    onBackClick: () -> Unit = {},
    onConvertClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        TopNavBase(
            leadingIcon = ImageVector.vectorResource(CommonR.drawable.ic_menu_chevron),
            onLeadingClick = onBackClick,
            centralPart = false,
            trailingPart = false
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            TopIntro(
                heading = stringResource(R.string.maya_service_name),
                text = stringResource(R.string.maya_portal_subtitle)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 79.dp, height = 80.dp)
                        .background(MayaLogoBackground, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_maya_logo),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Menu {
                MenuItem(
                    title = stringResource(R.string.maya_portal_convert_title),
                    subtitle = stringResource(R.string.maya_portal_convert_subtitle),
                    icon = R.drawable.ic_maya_convert,
                    showChevron = true,
                    action = onConvertClick
                )
            }
        }
    }
}

@Composable
@Preview
private fun MayaPortalScreenPreview() {
    MayaPortalScreen()
}