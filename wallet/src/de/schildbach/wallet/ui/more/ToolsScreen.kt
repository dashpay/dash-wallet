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

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.content.res.Configuration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import de.schildbach.wallet.service.DashjDiagnosticSyncState
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.ButtonData
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.ModalDialog
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.TopIntro
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ToolsScreen(
    onBackClick: () -> Unit = {},
    onAddressBookClick: () -> Unit = {},
    onImportPrivateKeyClick: () -> Unit = {},
    onNetworkMonitorClick: () -> Unit = {},
    onExtendPublicKeyClick: () -> Unit = {},
    onMasternodeKeysClick: () -> Unit = {},
    onCsvExportClick: () -> Unit = {},
    onZenLedgerExport: () -> Unit = {},
    onCreditsInfoClick: () -> Unit = {},
    onBuyCredits: () -> Unit = {}
) {
    val viewModel: ToolsViewModel = hiltViewModel()

    ToolsScreen(
        uiStateFlow = viewModel.uiState,
        dashjDiagnosticFlow = viewModel.dashjDiagnosticState,
        onDashjDiagnosticToggle = viewModel::setDashjSyncDiagnostic,
        dashjSyncFromPromptFlow = viewModel.dashjSyncFromPrompt,
        onDashjSyncFromDateConfirm = viewModel::confirmDashjSyncFromDate,
        onDashjSyncFromBeginningConfirm = viewModel::confirmDashjSyncFromBeginning,
        onDashjSyncFromCancel = viewModel::cancelDashjSyncFromPrompt,
        onBackClick = onBackClick,
        onAddressBookClick = onAddressBookClick,
        onImportPrivateKeyClick = onImportPrivateKeyClick,
        onNetworkMonitorClick = onNetworkMonitorClick,
        onExtendPublicKeyClick = onExtendPublicKeyClick,
        onMasternodeKeysClick = onMasternodeKeysClick,
        onCsvExportClick = onCsvExportClick,
        onZenLedgerExport = onZenLedgerExport,
        onCreditsInfoClick = onCreditsInfoClick,
        onBuyCredits = onBuyCredits
    )
}

@Composable
fun ToolsScreen(
    uiStateFlow: StateFlow<ToolsUIState>,
    dashjDiagnosticFlow: StateFlow<DashjDiagnosticUIState> = MutableStateFlow(DashjDiagnosticUIState()),
    onDashjDiagnosticToggle: (Boolean) -> Unit = {},
    dashjSyncFromPromptFlow: StateFlow<DashjSyncFromPrompt?> = MutableStateFlow(null),
    onDashjSyncFromDateConfirm: (Long) -> Unit = {},
    onDashjSyncFromBeginningConfirm: () -> Unit = {},
    onDashjSyncFromCancel: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onAddressBookClick: () -> Unit = {},
    onImportPrivateKeyClick: () -> Unit = {},
    onNetworkMonitorClick: () -> Unit = {},
    onExtendPublicKeyClick: () -> Unit = {},
    onMasternodeKeysClick: () -> Unit = {},
    onCsvExportClick: () -> Unit = {},
    onZenLedgerExport: () -> Unit = {},
    onCreditsInfoClick: () -> Unit = {},
    onBuyCredits: () -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()
    val dashjDiagnostic by dashjDiagnosticFlow.collectAsState()
    val dashjSyncFromPrompt by dashjSyncFromPromptFlow.collectAsState()

    ToolsScreenContent(
        uiState = uiState,
        dashjDiagnostic = dashjDiagnostic,
        onDashjDiagnosticToggle = onDashjDiagnosticToggle,
        onBackClick = onBackClick,
        onAddressBookClick = onAddressBookClick,
        onImportPrivateKeyClick = onImportPrivateKeyClick,
        onNetworkMonitorClick = onNetworkMonitorClick,
        onExtendPublicKeyClick = onExtendPublicKeyClick,
        onMasternodeKeysClick = onMasternodeKeysClick,
        onCsvExportClick = onCsvExportClick,
        onZenLedgerExport = onZenLedgerExport,
        onCreditsInfoClick = onCreditsInfoClick,
        onBuyCredits = onBuyCredits
    )

    // "Sync from date" prompt: raised each time the dashj-sync (diagnostic)
    // toggle is switched ON; the toggle itself only flips once a choice is
    // confirmed here (cancel/dismiss leaves it off). English-only, like the
    // rest of the SDK-migration debug instrumentation.
    dashjSyncFromPrompt?.let { prompt ->
        DashjSyncFromDateDialog(
            prompt = prompt,
            onConfirmDate = onDashjSyncFromDateConfirm,
            onConfirmFromBeginning = onDashjSyncFromBeginningConfirm,
            onCancel = onDashjSyncFromCancel
        )
    }
}

/**
 * The dashj-sync (diagnostic) "Sync from date" dialog — restore-flow style:
 * a date input (classic [DatePickerDialog], same EARLIEST_HD_SEED_CREATION_TIME
 * → today bounds as the restore flow's picker) defaulting to the wallet's
 * creation date when one is known, plus an explicit "sync everything" escape
 * hatch. Syncing from a date at/before the wallet's creation date still sees
 * every wallet transaction (coins cannot predate the wallet's keys), so the
 * SDK-vs-dashj parity verdict stays valid while skipping years of empty blocks.
 */
@Composable
private fun DashjSyncFromDateDialog(
    prompt: DashjSyncFromPrompt,
    onConfirmDate: (Long) -> Unit,
    onConfirmFromBeginning: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var selectedDateMillis by remember(prompt) { mutableStateOf(prompt.defaultDateMillis) }
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }

    fun showDatePicker() {
        val calendar = Calendar.getInstance()
        selectedDateMillis?.let { calendar.timeInMillis = it }
        val picker = DatePickerDialog(
            context,
            { _, year, month, day ->
                val chosen = Calendar.getInstance()
                chosen.set(year, month, day, 0, 0, 0)
                chosen.set(Calendar.MILLISECOND, 0)
                selectedDateMillis = chosen.timeInMillis
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        // Same bounds as the restore-flow date picker (RestoreWalletFromSeedActivity).
        picker.datePicker.minDate = de.schildbach.wallet.Constants.EARLIEST_HD_SEED_CREATION_TIME * 1000L
        picker.datePicker.maxDate = System.currentTimeMillis()
        picker.show()
    }

    ModalDialog(
        showDialog = true,
        onDismissRequest = onCancel,
        heading = "Sync from date",
        textBlocks = listOf(
            "Use your wallet's creation date. Transactions before this date will not be scanned."
        ),
        content = {
            Text(
                text = selectedDateMillis?.let { dateFormat.format(Date(it)) } ?: "Select date…",
                style = MyTheme.Body2Medium,
                color = MyTheme.Colors.dashBlue,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker() }
                    .padding(vertical = 10.dp)
            )
        },
        buttons = listOf(
            ButtonData(
                label = "Start sync from date",
                onClick = { selectedDateMillis?.let(onConfirmDate) },
                style = Style.FilledBlue,
                enabled = selectedDateMillis != null
            ),
            ButtonData(
                label = "Sync everything (from the beginning)",
                onClick = onConfirmFromBeginning,
                style = Style.TintedGray
            ),
            ButtonData(
                label = "Cancel",
                onClick = onCancel,
                style = Style.PlainBlue
            )
        )
    )
}

@Composable
private fun ToolsScreenContent(
    uiState: ToolsUIState,
    dashjDiagnostic: DashjDiagnosticUIState = DashjDiagnosticUIState(),
    onDashjDiagnosticToggle: (Boolean) -> Unit = {},
    onBackClick: () -> Unit = {},
    onAddressBookClick: () -> Unit = {},
    onImportPrivateKeyClick: () -> Unit = {},
    onNetworkMonitorClick: () -> Unit = {},
    onExtendPublicKeyClick: () -> Unit = {},
    onMasternodeKeysClick: () -> Unit = {},
    onCsvExportClick: () -> Unit = {},
    onZenLedgerExport: () -> Unit = {},
    onCreditsInfoClick: () -> Unit = {},
    onBuyCredits: () -> Unit = {}
) {
    val colors = LocalDashColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
    ) {
        // Top Navigation
        NavBarBack(onBackClick = onBackClick)

        // Tools Header
        TopIntro(
            heading = stringResource(R.string.tools_title),
        )

        // Scrollable Content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Menu {
                // Address Book
                MenuItem(
                    title = stringResource(R.string.tools_address_book),
                    icon = R.drawable.ic_menu_address_book,
                    action = onAddressBookClick
                )

                // Import Private Key
                MenuItem(
                    title = stringResource(R.string.tools_import_private_key),
                    icon = R.drawable.ic_menu_import_private_key,
                    action = onImportPrivateKeyClick
                )

                // Network Monitor
                MenuItem(
                    title = stringResource(R.string.tools_network_monitor),
                    icon = R.drawable.ic_menu_network_monitor,
                    action = onNetworkMonitorClick
                )

                // Extend Public Key
                MenuItem(
                    title = stringResource(R.string.tools_show_xpub),
                    icon = R.drawable.ic_menu_extended_public_key,
                    action = onExtendPublicKeyClick
                )

                // Masternode Keys
                MenuItem(
                    title = stringResource(R.string.masternode_keys_title),
                    icon = R.drawable.ic_menu_masternode_keys,
                    action = onMasternodeKeysClick
                )

                // CSV Export
                MenuItem(
                    title = stringResource(R.string.report_transaction_history_title),
                    icon = R.drawable.ic_menu_csv_export,
                    action = onCsvExportClick
                )
            }

            if (uiState.hasUsername) {
                Menu {
                    // Credits (only shown when user has a username)
                    MenuItem(
                        title = stringResource(R.string.tools_credits_title),
                        icon = R.drawable.ic_menu_credits,
                        showInfo = true,
                        onInfoClick = onCreditsInfoClick,
                        trailingButtonText = stringResource(R.string.tools_credits_buy_button),
                        trailingButtonStyle = Style.PlainBlue,
                        onTrailingButtonClick = onBuyCredits,
                        action = onBuyCredits
                    )
                }
            }

            Menu {
                // ZenLedger Export
                MenuItem(
                    title = stringResource(R.string.zenledger_export_title),
                    subtitle = stringResource(R.string.zenledger_export_subtitle),
                    icon = R.drawable.ic_zenledger,
                    action = onZenLedgerExport
                )
            }

            // dashj sync (diagnostic): un-holds the legacy dashj engine after the
            // SDK cutover so it syncs as a backup / parity check. English-only,
            // like the rest of the SDK-migration debug instrumentation.
            Menu {
                MenuItem(
                    title = "dashj sync (diagnostic)",
                    subtitle = "Run the legacy dashj engine alongside the SDK to compare",
                    icon = R.drawable.ic_menu_network_monitor,
                    checked = dashjDiagnostic.enabled,
                    onCheckedChange = onDashjDiagnosticToggle
                )
                if (dashjDiagnostic.enabled) {
                    val (label, color) = dashjDiagnosticReadout(dashjDiagnostic)
                    Text(
                        text = label,
                        style = MyTheme.CaptionMedium,
                        color = color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * The diagnostic percentage readout + its colour: neutral while dashj is still
 * syncing, a neutral "Verifying" once dashj has caught up but the fresh parity
 * report is still being computed, then GREEN once fully matching the SDK,
 * PURPLE when the balances match exactly but the tx counts differ, RED on a
 * balance mismatch.
 */
@Composable
private fun dashjDiagnosticReadout(state: DashjDiagnosticUIState): Pair<String, Color> = when {
    // dashj is done; the parity check against the caught-up state is running.
    state.verifying ->
        "dashj synced — verifying parity…" to MyTheme.Colors.textSecondary
    state.percent < 100 ->
        "dashj syncing ${state.percent}%" to MyTheme.Colors.textSecondary
    state.parity == DashjDiagnosticSyncState.Parity.MATCH ->
        "dashj 100% — matches SDK" to MyTheme.Colors.green
    state.parity == DashjDiagnosticSyncState.Parity.BALANCE_MATCH ->
        "dashj 100% — balances match SDK, tx counts differ" to DiagnosticPurple
    state.parity == DashjDiagnosticSyncState.Parity.MISMATCH ->
        "dashj 100% — MISMATCH vs SDK" to MyTheme.Colors.red
    else ->
        "dashj 100% — comparing to SDK…" to MyTheme.Colors.textSecondary
}

/** Material purple 500 — no purple in the app palette; diagnostic readout only. */
private val DiagnosticPurple = Color(0xFF9C27B0)

@Composable
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
fun ToolsScreenPreview() {
    DashWalletTheme {
        ToolsScreenContent(uiState = ToolsUIState(false, false, true))
    }
}