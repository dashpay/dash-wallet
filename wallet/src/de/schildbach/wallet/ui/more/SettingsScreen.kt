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

package de.schildbach.wallet.ui.more

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.StateFlow
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Coin
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.util.toBigDecimal
import java.text.DecimalFormat

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onRescanBlockchainClick: () -> Unit = {},
    onAboutDashClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onCoinJoinClick: () -> Unit = {},
    onTransactionMetadataClick: () -> Unit = {},
    onBatteryOptimizationClick: () -> Unit = {}
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    
    SettingsScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onLocalCurrencyClick = onLocalCurrencyClick,
        onRescanBlockchainClick = onRescanBlockchainClick,
        onAboutDashClick = onAboutDashClick,
        onNotificationsClick = onNotificationsClick,
        onCoinJoinClick = onCoinJoinClick,
        onTransactionMetadataClick = onTransactionMetadataClick,
        onBatteryOptimizationClick = onBatteryOptimizationClick
    )
}

@Composable
fun SettingsScreen(
    uiStateFlow: StateFlow<SettingsUIState>,
    onBackClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onRescanBlockchainClick: () -> Unit = {},
    onAboutDashClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onCoinJoinClick: () -> Unit = {},
    onTransactionMetadataClick: () -> Unit = {},
    onBatteryOptimizationClick: () -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()
    
    SettingsScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onLocalCurrencyClick = onLocalCurrencyClick,
        onRescanBlockchainClick = onRescanBlockchainClick,
        onAboutDashClick = onAboutDashClick,
        onNotificationsClick = onNotificationsClick,
        onCoinJoinClick = onCoinJoinClick,
        onTransactionMetadataClick = onTransactionMetadataClick,
        onBatteryOptimizationClick = onBatteryOptimizationClick
    )
}

@Composable
private fun SettingsScreenContent(
    uiState: SettingsUIState,
    onBackClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onRescanBlockchainClick: () -> Unit = {},
    onAboutDashClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onCoinJoinClick: () -> Unit = {},
    onTransactionMetadataClick: () -> Unit = {},
    onBatteryOptimizationClick: () -> Unit = {}
) {
    @StringRes val statusId: Int
    var balance: String? = null
    var balanceIcon: Int? = null
    val decimalFormat = DecimalFormat("0.000")
    
    if (uiState.coinJoinMixingMode == CoinJoinMode.NONE && uiState.coinJoinMixingStatus != MixingStatus.FINISHING) {
        statusId = R.string.turned_off
   } else {
        if (uiState.coinJoinMixingStatus == MixingStatus.FINISHED) {
            statusId = R.string.coinjoin_progress_finished
        } else {
            statusId = when(uiState.coinJoinMixingStatus) {
                MixingStatus.NOT_STARTED -> R.string.coinjoin_not_started
                MixingStatus.MIXING -> R.string.coinjoin_mixing
                MixingStatus.FINISHING -> R.string.coinjoin_mixing_finishing
                MixingStatus.PAUSED -> R.string.coinjoin_paused
                else -> R.string.error
            }
            if (!uiState.hideBalance) {
                balance = stringResource(
                    R.string.coinjoin_progress_balance,
                    decimalFormat.format(uiState.mixedBalance.toBigDecimal()),
                    decimalFormat.format(uiState.totalBalance.toBigDecimal())
                )
                balanceIcon = R.drawable.ic_dash_d_black
            } else {
                balance = stringResource(R.string.coinjoin_progress_amount_hidden)
            }
        }
    }
    val coinJoinStatusText = when {
        uiState.coinJoinMixingMode != CoinJoinMode.NONE && (uiState.coinJoinMixingStatus == MixingStatus.MIXING || uiState.coinJoinMixingStatus == MixingStatus.FINISHING) ->
            stringResource(R.string.coinjoin_progress_status_percentage, stringResource(statusId), uiState.mixingProgress.toInt())
        else -> stringResource(statusId)
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        // Top Navigation
        TopNavBase(
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_menu_chevron),
            onLeadingClick = onBackClick,
            centralPart = false,
            trailingPart = false
        )

        // Settings Header
        TopIntro(
            heading = stringResource(R.string.settings_title),
            modifier = Modifier.padding(top = 10.dp, start = 20.dp, end = 20.dp, bottom = 20.dp)
        )

        // Scrollable Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Menu {
                // Local Currency
                MenuItem(
                    title = stringResource(R.string.menu_local_currency),
                    subtitle = uiState.localCurrencySymbol,
                    icon = R.drawable.ic_local_currency,
                    action = onLocalCurrencyClick
                )

                // Rescan Blockchain
                MenuItem(
                    title = stringResource(R.string.menu_rescan_blockchain),
                    icon = R.drawable.ic_rescan_blockchain,
                    action = onRescanBlockchainClick
                )

                // About Dash
                MenuItem(
                    title = stringResource(R.string.about_dash_title),
                    icon = R.drawable.ic_dash_blue_filled,
                    action = onAboutDashClick
                )

                // Notifications
                MenuItem(
                    title = stringResource(R.string.notifications_title),
                    icon = R.drawable.ic_notification,
                    action = onNotificationsClick
                )

                // CoinJoin
                MenuItem(
                    title = stringResource(R.string.coinjoin),
                    subtitle = coinJoinStatusText,
                    icon = R.drawable.ic_mixing,
                    action = onCoinJoinClick,
                    dashAmount = balance,
                    dashIcon = balanceIcon
                )

                // Transaction Metadata
                if (Constants.SUPPORTS_TXMETADATA) {
                    MenuItem(
                        title = "Transaction metadata",
                        icon = R.drawable.transaction_metadata,
                        action = onTransactionMetadataClick
                    )
                }

                // Battery Optimization
                MenuItem(
                    title = stringResource(R.string.battery_optimization_title),
                    subtitle = stringResource(
                        if (uiState.ignoringBatteryOptimizations) {
                            R.string.battery_optimization_subtitle_unrestricted
                        } else {
                            R.string.battery_optimization_subtitle_optimized
                        },
                    ),
                    icon = R.drawable.ic_battery,
                    action = onBatteryOptimizationClick
                )
            }
        }
    }
}

@Composable
@Preview
fun MoreScreenPreview() {
    SettingsScreenContent(uiState = SettingsUIState())
}

@Composable
@Preview(name = "Settings with CoinJoin Active")
fun MoreScreenPreviewWithCoinJoin() {
    val customState = SettingsUIState(
        localCurrencySymbol = "USD",
        coinJoinMixingMode = CoinJoinMode.INTERMEDIATE,
        coinJoinMixingStatus = MixingStatus.MIXING,
        mixingProgress = 50.0,
        mixedBalance = Coin.COIN,
        totalBalance = Coin.COIN.multiply(2L),
        hideBalance = false,
        ignoringBatteryOptimizations = true
    )
    SettingsScreenContent(uiState = customState)
}