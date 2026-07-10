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
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onRescanBlockchainClick: () -> Unit = {},
    onAboutDashClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
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
        onTransactionMetadataClick = onTransactionMetadataClick,
        onBatteryOptimizationClick = onBatteryOptimizationClick,
        onUseKotlinSdkL1SendChanged = viewModel::setUseKotlinSdkL1Send,
        onRunSdkSoakSend = viewModel::runSdkSoakSend
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
    onTransactionMetadataClick: () -> Unit = {},
    onBatteryOptimizationClick: () -> Unit = {},
    onUseKotlinSdkL1SendChanged: (Boolean) -> Unit = {},
    onRunSdkSoakSend: () -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()

    SettingsScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onLocalCurrencyClick = onLocalCurrencyClick,
        onRescanBlockchainClick = onRescanBlockchainClick,
        onAboutDashClick = onAboutDashClick,
        onNotificationsClick = onNotificationsClick,
        onTransactionMetadataClick = onTransactionMetadataClick,
        onBatteryOptimizationClick = onBatteryOptimizationClick,
        onUseKotlinSdkL1SendChanged = onUseKotlinSdkL1SendChanged,
        onRunSdkSoakSend = onRunSdkSoakSend
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
    onTransactionMetadataClick: () -> Unit = {},
    onBatteryOptimizationClick: () -> Unit = {},
    onUseKotlinSdkL1SendChanged: (Boolean) -> Unit = {},
    onRunSdkSoakSend: () -> Unit = {}
) {
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

                // Transaction Metadata
                if (Constants.SUPPORTS_TXMETADATA && uiState.transactionMetadataVisible) {
                    MenuItem(
                        title = stringResource(R.string.transaction_metadata_title),
                        subtitle = uiState.transactionMetadataSubtitle,
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

                // Debug-only Phase 5b soak switch: routes real L1 sends
                // through the Kotlin SDK (DashPayConfig.USE_KOTLIN_SDK_L1_SEND
                // — deliberately never debug-seeded, opt-in only).
                // BuildConfig.DEBUG is a compile-time constant, so this block
                // does not exist in release builds; strings are deliberately
                // hardcoded (never shipped, never translated).
                if (BuildConfig.DEBUG) {
                    MenuItem(
                        title = "Use Kotlin SDK for L1 sends",
                        subtitle = "Debug only: routes real sends through the new SDK engine " +
                            "instead of dashj. Leave off unless soak-testing Phase 5b.",
                        checked = uiState.useKotlinSdkL1Send,
                        onCheckedChange = onUseKotlinSdkL1SendChanged
                    )

                    // One-tap Phase 5b soak send through the routed path
                    // (SendCoinsTaskRunner's NEUTRAL overload): with the
                    // toggle above ON it exercises the SDK engine end to
                    // end, OFF it is a dashj control send. The outcome
                    // lands inline in the subtitle; re-taps are ignored
                    // while a send is in flight.
                    MenuItem(
                        title = "Run SDK soak send (0.05 to self)",
                        subtitle = uiState.soakSendStatus
                            ?: "Debug only: sends 0.05 Dash to a fresh own address via the " +
                                "routed (neutral) send path. Real coins, real fees.",
                        action = { if (!uiState.soakSendInFlight) onRunSdkSoakSend() }
                    )
                }
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
@Preview(name = "Settings populated")
fun MoreScreenPreviewPopulated() {
    val customState = SettingsUIState(
        localCurrencySymbol = "USD",
        ignoringBatteryOptimizations = true,
        transactionMetadataVisible = true,
        transactionMetadataSubtitle = "Last saved: Jan 15, 2024"
    )
    SettingsScreenContent(uiState = customState)
}