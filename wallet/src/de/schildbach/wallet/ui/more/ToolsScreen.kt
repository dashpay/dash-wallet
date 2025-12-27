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
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase

@Composable
fun ToolsScreen(
    onBackClick: () -> Unit = {},
    onImportPrivateKeyClick: () -> Unit = {},
    onNetworkMonitorClick: () -> Unit = {},
    onExtendPublicKeyClick: () -> Unit = {},
    onMasternodeKeysClick: () -> Unit = {},
    onCsvExportClick: () -> Unit = {},
    onZenLedgerExport: () -> Unit = {},
    onBuyCredits: () -> Unit = {}
) {
    val viewModel: ToolsViewModel = hiltViewModel()
    
    ToolsScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onImportPrivateKeyClick = onImportPrivateKeyClick,
        onNetworkMonitorClick = onNetworkMonitorClick,
        onExtendPublicKeyClick = onExtendPublicKeyClick,
        onMasternodeKeysClick = onMasternodeKeysClick,
        onCsvExportClick = onCsvExportClick,
        onZenLedgerExport = onZenLedgerExport,
        onBuyCredits = onBuyCredits
    )
}

@Composable
fun ToolsScreen(
    uiStateFlow: StateFlow<ToolsUIState>,
    onBackClick: () -> Unit = {},
    onImportPrivateKeyClick: () -> Unit = {},
    onNetworkMonitorClick: () -> Unit = {},
    onExtendPublicKeyClick: () -> Unit = {},
    onMasternodeKeysClick: () -> Unit = {},
    onCsvExportClick: () -> Unit = {},
    onZenLedgerExport: () -> Unit = {},
    onBuyCredits: () -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()
    
    ToolsScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onImportPrivateKeyClick = onImportPrivateKeyClick,
        onNetworkMonitorClick = onNetworkMonitorClick,
        onExtendPublicKeyClick = onExtendPublicKeyClick,
        onMasternodeKeysClick = onMasternodeKeysClick,
        onCsvExportClick = onCsvExportClick,
        onZenLedgerExport = onZenLedgerExport,
        onBuyCredits = onBuyCredits
    )
}

@Composable
private fun ToolsScreenContent(
    uiState: ToolsUIState,
    onBackClick: () -> Unit = {},
    onImportPrivateKeyClick: () -> Unit = {},
    onNetworkMonitorClick: () -> Unit = {},
    onExtendPublicKeyClick: () -> Unit = {},
    onMasternodeKeysClick: () -> Unit = {},
    onCsvExportClick: () -> Unit = {},
    onZenLedgerExport: () -> Unit = {},
    onBuyCredits: () -> Unit = {}
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

        // Tools Header
        TopIntro(
            heading = "Tools",
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
                // Import Private Key
                MenuItem(
                    title = stringResource(R.string.sweep_wallet_activity_title),
                    icon = R.drawable.ic_import_private_key,
                    action = onImportPrivateKeyClick
                )

                // Network Monitor
                MenuItem(
                    title = stringResource(R.string.network_monitor_activity_title),
                    icon = R.drawable.ic_network_monitor,
                    action = onNetworkMonitorClick
                )

                // Extend Public Key
                MenuItem(
                    title = stringResource(R.string.extended_public_key_fragment_title),
                    icon = R.drawable.ic_extended_public_key,
                    action = onExtendPublicKeyClick
                )

                // Masternode Keys
                MenuItem(
                    title = stringResource(R.string.masternode_keys_title),
                    icon = R.drawable.ic_masternode_keys,
                    action = onMasternodeKeysClick
                )

                // CSV Export
                MenuItem(
                    title = stringResource(R.string.report_transaction_history_title),
                    icon = R.drawable.ic_csv_export,
                    action = onCsvExportClick
                )
                // space?

                // CSV Export
                MenuItem(
                    title = stringResource(R.string.zenledger_export_title),
                    icon = R.drawable.ic_zenledger,
                    action = onZenLedgerExport
                )

                // CSV Export
                MenuItem(
                    title = stringResource(R.string.tools_credits_title),
                    icon = R.drawable.ic_credits,
                    action = onBuyCredits
                )
            }
        }
    }
}

@Composable
@Preview
fun ToolsScreenPreview() {
    ToolsScreenContent(uiState = ToolsUIState())
}