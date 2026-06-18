/*
 * Copyright (c) 2025 Dash Core Group
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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style

data class ExploreScreenState(
    val showFaucet: Boolean, // testnet only
    val showStaking: Boolean, // has CrowdNode account
    val apy: Double, // 0.0 means hide the APY badge
    val showWithdrawalBanner: Boolean
)

@Composable
fun ExploreScreen(
    state: ExploreScreenState,
    onBackClick: () -> Unit,
    onWhereToSpendClick: () -> Unit,
    onAtmsClick: () -> Unit,
    onStakingClick: () -> Unit,
    onFaucetClick: () -> Unit,
    onWithdrawClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MyTheme.Colors.backgroundPrimary)
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
                .padding(end = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.explore_dash),
                style = MyTheme.Typography.HeadlineSmallBold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.explore_subtitle),
                style = MyTheme.Body2Regular,
                color = MyTheme.Colors.textSecondary,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Menu card with the list of destinations
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(20.dp))
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

                if (state.showStaking) {
                    StakingMenuItem(
                        apy = state.apy,
                        onClick = onStakingClick
                    )
                }
            }
        }

            // CrowdNode withdrawal banner (below the card)
            if (state.showWithdrawalBanner) {
                CrowdNodeWithdrawalBanner(onWithdrawClick = onWithdrawClick)
            }
        }
    }
}

@Composable
private fun StakingMenuItem(
    apy: Double,
    onClick: () -> Unit
) {
    if (apy == 0.0) {
        MenuItem(
            title = stringResource(R.string.staking_title),
            subtitle = stringResource(R.string.explore_staking_subtitle),
            icon = R.drawable.ic_deposit,
            action = onClick
        )
    } else {
        // MenuItem does not support an inline APY pill, so render the row layout
        // explicitly while reusing the same spacing/typography tokens.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent, RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(modifier = Modifier.size(26.dp)) {
                Image(
                    painter = painterResource(id = R.drawable.ic_deposit),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.staking_title),
                    style = MyTheme.Body2Medium,
                    color = MyTheme.Colors.textPrimary
                )
                Text(
                    text = stringResource(R.string.explore_staking_subtitle),
                    style = MyTheme.Typography.BodyMedium,
                    color = MyTheme.Colors.textSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
                ApyBadge(apy = apy)
            }
        }
    }
}

@Composable
private fun ApyBadge(apy: Double) {
    Row(
        modifier = Modifier
            .padding(top = 2.dp)
            .background(MyTheme.Colors.green.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_circle_green_percent),
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(
                R.string.explore_staking_current_apy,
                String.format("%.1f", apy)
            ),
            style = MyTheme.Typography.LabelMedium,
            color = MyTheme.Colors.green
        )
    }
}

@Composable
private fun CrowdNodeWithdrawalBanner(
    onWithdrawClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MyTheme.Colors.gray.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_warning_triangle),
                contentDescription = null,
                modifier = Modifier.size(30.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 5.dp, end = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = stringResource(R.string.crowdnode_withdrawal_reminder_title),
                        style = MyTheme.Body2Medium,
                        color = MyTheme.Colors.textPrimary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.crowdnode_withdrawal_reminder_message),
                        style = MyTheme.Body2Regular,
                        color = MyTheme.Colors.textSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                DashButton(
                    text = stringResource(R.string.crowdnode_withdraw_funds),
                    style = Style.FilledBlue,
                    size = Size.Small,
                    stretch = false,
                    onClick = onWithdrawClick
                )
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun ExploreScreenFullPreview() {
    ExploreScreen(
        state = ExploreScreenState(
            showFaucet = true,
            showStaking = true,
            apy = 5.7,
            showWithdrawalBanner = true
        ),
        onBackClick = {},
        onWhereToSpendClick = {},
        onAtmsClick = {},
        onStakingClick = {},
        onFaucetClick = {},
        onWithdrawClick = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun ExploreScreenMinimalPreview() {
    ExploreScreen(
        state = ExploreScreenState(
            showFaucet = false,
            showStaking = false,
            apy = 0.0,
            showWithdrawalBanner = false
        ),
        onBackClick = {},
        onWhereToSpendClick = {},
        onAtmsClick = {},
        onStakingClick = {},
        onFaucetClick = {},
        onWithdrawClick = {}
    )
}
