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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.common.R as CommonR

@Composable
fun MayaPortalScreen(
    showBuy: Boolean = true,
    onBackClick: () -> Unit = {},
    onBuyClick: () -> Unit = {},
    onSellClick: () -> Unit = {}
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
                .padding(top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Intro card: DashDEX illustration + headline + subtitle (adapter-agnostic branding)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(20.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_dash_dex_illustration),
                    contentDescription = null,
                    modifier = Modifier.size(60.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.dash_dex_title),
                        style = MyTheme.Typography.HeadlineSmallBold,
                        color = MyTheme.Colors.textPrimary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.dash_dex_subtitle),
                        style = MyTheme.Body2Regular,
                        color = MyTheme.Colors.textSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Menu {
                // Buying Dash (any crypto -> Dash Wallet) is only available on backends that
                // support it (SwapKit). Maya can only sell Dash, so the Buy row is hidden there.
                if (showBuy) {
                    MenuItem(
                        title = stringResource(R.string.dash_dex_buy_title),
                        subtitle = stringResource(R.string.dash_dex_buy_subtitle),
                        icon = R.drawable.ic_dash_dex_buy,
                        action = onBuyClick
                    )
                }
                MenuItem(
                    title = stringResource(R.string.dash_dex_sell_title),
                    subtitle = stringResource(R.string.dash_dex_sell_subtitle),
                    icon = R.drawable.ic_dash_dex_sell,
                    action = onSellClick
                )
            }
        }
    }
}

@Composable
@Preview
private fun MayaPortalScreenBuyAndSellPreview() {
    MayaPortalScreen(showBuy = true)
}

@Composable
@Preview
private fun MayaPortalScreenSellOnlyPreview() {
    MayaPortalScreen(showBuy = false)
}