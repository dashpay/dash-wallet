/*
 * Copyright (c) 2026 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.ui.compose_views.Shadows.softShadow
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack

data class ExploreScreenState(
    val showFaucet: Boolean // testnet only
)

@Composable
fun ExploreScreen(
    state: ExploreScreenState,
    onBackClick: () -> Unit,
    onWhereToSpendClick: () -> Unit,
    onAtmsClick: () -> Unit,
    onFaucetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundPrimary)
    ) {
        // Fixed back chevron pinned to the top, above the scrolling content
        NavBarBack(onBackClick = onBackClick)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Title + subtitle (TopIntro pattern, no extra horizontal padding since we already padded)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 40.dp)
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.explore_dash),
                    style = MyTheme.Typography.HeadlineMediumBold,
                    color = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.explore_subtitle),
                    style = MyTheme.Body2Regular,
                    color = colors.textSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Menu card with the list of destinations
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .softShadow(16.dp)
                    .background(colors.backgroundSecondary, RoundedCornerShape(16.dp))
                    .padding(6.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (state.showFaucet) {
                        MenuItem(
                            title = stringResource(R.string.explore_get_test_dash),
                            subtitle = stringResource(R.string.explore_test_dash_text_1),
                            icon = R.drawable.ic_faucet,
                            action = onFaucetClick
                        )
                    }

                    MenuItem(
                        title = stringResource(R.string.explore_merchants_title),
                        subtitle = stringResource(R.string.explore_merchants_subtitle),
                        icon = R.drawable.ic_map,
                        action = onWhereToSpendClick
                    )

                    MenuItem(
                        title = stringResource(R.string.explore_atms_title),
                        subtitle = stringResource(R.string.explore_atms_subtitle),
                        icon = R.drawable.ic_atm,
                        action = onAtmsClick
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun ExploreScreenFullPreview() {
    ExploreScreen(
        state = ExploreScreenState(showFaucet = true),
        onBackClick = {},
        onWhereToSpendClick = {},
        onAtmsClick = {},
        onFaucetClick = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun ExploreScreenMinimalPreview() {
    ExploreScreen(
        state = ExploreScreenState(showFaucet = false),
        onBackClick = {},
        onWhereToSpendClick = {},
        onAtmsClick = {},
        onFaucetClick = {}
    )
}
