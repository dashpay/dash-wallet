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
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    localCurrencySymbolFlow: Flow<String>,
    onLocalCurrencyClick: () -> Unit = {},
    onRescanBlockchainClick: () -> Unit = {},
    onAboutDashClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onCoinJoinClick: () -> Unit = {},
    onTransactionMetadataClick: () -> Unit = {},
    onBatteryOptimizationClick: () -> Unit = {},
    coinJoinModeFlow: Flow<CoinJoinMode>,
    coinJoinStatusFlow: Flow<MixingStatus>,
    mixingProgressFlow: Flow<Double>,
    mixingBalanceFlow: Flow<Coin>,
    totalBalanceFlow: Flow<Coin>,
    hideBalanceFlow: Flow<Boolean>,
    isIgnoringBatteryOptimizationsFlow: Flow<Boolean>
) {
    val localCurrencySymbol by localCurrencySymbolFlow.collectAsState(org.dash.wallet.common.util.Constants.USD_CURRENCY)
    val mode by coinJoinModeFlow.collectAsState(CoinJoinMode.NONE)
    val mixingStatus by coinJoinStatusFlow.collectAsState(MixingStatus.NOT_STARTED)
    val mixingProgress by mixingProgressFlow.collectAsState(0.0)
    val mixedBalance by mixingBalanceFlow.collectAsState(Coin.ZERO)
    val totalBalance by totalBalanceFlow.collectAsState(Coin.ZERO)
    val hideBalance by hideBalanceFlow.collectAsState(false)
    val isIgnoringBatteryOptimizations by isIgnoringBatteryOptimizationsFlow.collectAsState(false)
    @StringRes val statusId: Int
    var balance: String? = null
    var balanceIcon: Int? = null
    val decimalFormat: DecimalFormat = DecimalFormat("0.000")
    if (mode == CoinJoinMode.NONE) {
        statusId = R.string.turned_off
   } else {
        if (mixingStatus == MixingStatus.FINISHED) {
            statusId = R.string.coinjoin_progress_finished
        } else {
            statusId = when(mixingStatus) {
                MixingStatus.NOT_STARTED -> R.string.coinjoin_not_started
                MixingStatus.MIXING -> R.string.coinjoin_mixing
                MixingStatus.FINISHING -> R.string.coinjoin_mixing_finishing
                MixingStatus.PAUSED -> R.string.coinjoin_paused
                else -> R.string.error
            }
            if (!hideBalance) {
                balance = stringResource(
                    R.string.coinjoin_progress_balance,
                    decimalFormat.format(mixedBalance.toBigDecimal()),
                    decimalFormat.format(totalBalance.toBigDecimal())
                )
                balanceIcon = R.drawable.ic_dash_d_black
            } else {
                balance = stringResource(R.string.coinjoin_progress_amount_hidden)
            }
        }
    }
    val coinJoinStatusText = when {
        mode != CoinJoinMode.NONE && (mixingStatus == MixingStatus.MIXING || mixingStatus == MixingStatus.FINISHING) ->
            stringResource(R.string.coinjoin_progress_status_percentage, stringResource(statusId), mixingProgress.toInt())
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
                    subtitle = localCurrencySymbol,
                    icon = R.drawable.ic_local_currency, // You'll need the correct currency icon
                    action = onLocalCurrencyClick
                )

                // Rescan Blockchain
                MenuItem(
                    title = "Rescan blockchain",
                    icon = R.drawable.ic_rescan_blockchain, // You'll need the correct rescan icon
                    action = onRescanBlockchainClick
                )

                // About Dash
                MenuItem(
                    title = "About Dash",
                    icon = R.drawable.ic_dash_blue_filled, // You'll need the correct about icon
                    action = onAboutDashClick
                )

                // Notifications
                MenuItem(
                    title = "Notifications",
                    icon = R.drawable.ic_notification, // You'll need the correct notifications icon
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
                    title = "Battery optimization",
                    subtitle = stringResource(
                        if (isIgnoringBatteryOptimizations) {
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
    SettingsScreen(
        localCurrencySymbolFlow = MutableStateFlow("USD"),
        coinJoinModeFlow = MutableStateFlow(CoinJoinMode.INTERMEDIATE),
        coinJoinStatusFlow = MutableStateFlow(MixingStatus.MIXING),
        mixingProgressFlow = MutableStateFlow(50.0),
        mixingBalanceFlow = MutableStateFlow(Coin.COIN),
        totalBalanceFlow = MutableStateFlow(Coin.COIN.multiply(2L)),
        hideBalanceFlow = MutableStateFlow(false),
        isIgnoringBatteryOptimizationsFlow = MutableStateFlow(true)
    )
}