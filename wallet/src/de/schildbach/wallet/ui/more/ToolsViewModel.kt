/*
 * Copyright 2022 Dash Core Group.
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

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.common.base.Charsets
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.DashjDiagnosticSyncState
import de.schildbach.wallet.transactions.TaxBitExporter
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.launchIn
import org.bitcoinj.crypto.DeterministicKey
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.*
import javax.inject.Inject

data class ToolsUIState(
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val hasUsername: Boolean = false
)

/**
 * DIAGNOSTIC state for the Tools "dashj sync (diagnostic)" row: the toggle
 * position plus dashj's own sync percentage and the SDK-vs-dashj parity verdict
 * (meaningful only once [percent] reaches 100). See
 * [de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.DASHJ_SYNC_DIAGNOSTIC].
 */
data class DashjDiagnosticUIState(
    val enabled: Boolean = false,
    val percent: Int = 0,
    val parity: DashjDiagnosticSyncState.Parity = DashjDiagnosticSyncState.Parity.UNKNOWN,
    /** dashj caught up, fresh parity report pending — show "Verifying". */
    val verifying: Boolean = false
)

/**
 * The "Sync from date" prompt shown each time the dashj-sync DIAGNOSTIC
 * toggle is switched ON (restore-flow style): the tester picks the date the
 * un-held dashj engine checkpoints its fresh blockstore to, instead of
 * syncing from near-genesis. [defaultDateMillis] pre-selects the wallet's
 * known creation date when it is sane (a real user-provided restore date,
 * i.e. after the [de.schildbach.wallet.Constants.EARLIEST_HD_SEED_CREATION_TIME]
 * sentinel — which also rules out epoch/pre-2014 garbage); null = no sane
 * default, the tester must pick a date or choose "sync everything".
 */
data class DashjSyncFromPrompt(
    val defaultDateMillis: Long? = null
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val walletData: WalletData,
    private val walletDataProvider: WalletDataProvider,
    private val walletApplication: WalletApplication,
    private val clipboardManager: ClipboardManager,
    private val transactionMetadataProvider: TransactionMetadataProvider,
    blockchainStateDao: BlockchainStateDao,
    private val dashPayConfig: DashPayConfig,
    private val blockchainServiceConfig: BlockchainServiceConfig,
    dashjDiagnosticSyncState: DashjDiagnosticSyncState,
    private val identityConfig: BlockchainIdentityConfig,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    sealed class ExportCsvResult {
        object Idle : ExportCsvResult()
        object Loading : ExportCsvResult()
        data class Success(val file: File) : ExportCsvResult()
        object Error : ExportCsvResult()

        /**
         * Nothing to export. Distinct from [Error]: previously an empty transaction set
         * produced a Success carrying a header-only file, so the user got a silent empty
         * export with no indication anything was wrong.
         */
        object Empty : ExportCsvResult()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ToolsViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(ToolsUIState())
    val uiState: StateFlow<ToolsUIState> = _uiState.asStateFlow()

    val xpub: String
    val xpubWithCreationDate: String

    private val _exportCsvResult = MutableStateFlow<ExportCsvResult>(ExportCsvResult.Idle)
    val exportCsvResult: StateFlow<ExportCsvResult> = _exportCsvResult.asStateFlow()

    /**
     * DIAGNOSTIC row state: the [DashPayConfig.DASHJ_SYNC_DIAGNOSTIC] toggle
     * combined with the live dashj progress + parity verdict from the isolated
     * [DashjDiagnosticSyncState] holder. Inert default when the toggle is off.
     */
    val dashjDiagnosticState: StateFlow<DashjDiagnosticUIState> = combine(
        dashPayConfig.observeDashjSyncDiagnostic(),
        dashjDiagnosticSyncState.state
    ) { enabled, snapshot ->
        DashjDiagnosticUIState(
            enabled = enabled,
            percent = snapshot.percent,
            parity = snapshot.parity,
            verifying = snapshot.verifying
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashjDiagnosticUIState())

    init {
        val extendedKey: DeterministicKey = walletData.wallet!!.watchingKey
        xpub = extendedKey.serializePubB58(Constants.NETWORK_PARAMETERS)
        xpubWithCreationDate = String.format(
            Locale.US,
            "%s?c=%d&h=bip44",
            xpub,
            extendedKey.creationTimeSeconds,
        )

        blockchainStateDao.observeState().onEach {
            _uiState.value = uiState.value.copy(isSyncing = it?.isSynced() != true)
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(hasUsername = hasUsername())
        }
    }

    fun copyXpubToClipboard() {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "Dash Wallet extended public key",
                xpub,
            ),
        )
    }

    fun exportCsv(cacheDir: File) {
        if (_exportCsvResult.value is ExportCsvResult.Loading) return
        _exportCsvResult.value = ExportCsvResult.Loading
        viewModelScope.launch {
            try {
                val transactions = withContext(Dispatchers.IO) {
                    // Cutover-aware: post-cutover this is the SDK-fed set plus any dashj-only
                    // transactions, exactly what the history screen renders. Reading the held
                    // dashj wallet directly produced a header-only CSV on any wallet restored
                    // after the cutover.
                    walletDataProvider.getTransactions()
                }
                if (transactions.isEmpty()) {
                    log.warn("CSV export: no transactions available to export")
                    _exportCsvResult.value = ExportCsvResult.Empty
                    return@launch
                }
                val file = withContext(Dispatchers.IO) {
                    val exporter = TaxBitExporter(transactionMetadataProvider, transactions)
                    exporter.initMetadataMap()
                    val csvContent = exporter.exportString()
                    val reportDir = File(cacheDir, "report").also { it.mkdirs() }
                    // Deterministic, human-readable name ENDING in .csv. The old
                    // createTempFile put random digits before the suffix
                    // (`transaction-history.867…4894.csv`), and share-sheet
                    // receivers that rename by display title dropped everything
                    // after the first dot — delivering an extensionless file
                    // (QA field report, 11.10.98). One canonical file per day,
                    // overwritten on re-export, so the cache dir cannot fill
                    // with abandoned temp files either.
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val f = File(reportDir, "dash-wallet-transactions-$stamp.csv")
                    OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8).use { it.write(csvContent) }
                    f
                }
                _exportCsvResult.value = ExportCsvResult.Success(file)
            } catch (e: Exception) {
                log.error("Failed to export CSV", e)
                _exportCsvResult.value = ExportCsvResult.Error
            }
        }
    }

    fun resetExportCsvResult() {
        _exportCsvResult.value = ExportCsvResult.Idle
    }

    /** Non-null while the "Sync from date" dialog is up (toggle switched ON, choice pending). */
    private val _dashjSyncFromPrompt = MutableStateFlow<DashjSyncFromPrompt?>(null)
    val dashjSyncFromPrompt: StateFlow<DashjSyncFromPrompt?> = _dashjSyncFromPrompt.asStateFlow()

    /**
     * Flip the dashj-sync DIAGNOSTIC toggle: OFF persists immediately and
     * bounces the blockchain service so its Phase 5d engine-start gate
     * re-resolves (re-holds the peergroup post-cutover, clears the readout).
     * ON does NOT persist yet — it raises the "Sync from date" prompt
     * ([dashjSyncFromPrompt], restore-flow style) so the tester picks where
     * the diagnostic dashj sync starts; the flag only flips once a choice is
     * confirmed ([confirmDashjSyncFromDate] / [confirmDashjSyncFromBeginning]),
     * and cancelling ([cancelDashjSyncFromPrompt]) leaves it off. Each
     * OFF→ON flip re-prompts (fresh choice every enable). Never touches the
     * cutover state or sdkOwnsL1.
     */
    fun setDashjSyncDiagnostic(enabled: Boolean) {
        if (!enabled) {
            _dashjSyncFromPrompt.value = null
            viewModelScope.launch {
                dashPayConfig.setDashjSyncDiagnostic(false)
                walletApplication.restartBlockchainService()
            }
            return
        }
        viewModelScope.launch {
            // Default the date input to the wallet's creation date when sane.
            // BlockchainServiceConfig.getWalletCreationDate() already nulls the
            // EARLIEST_HD_SEED_CREATION_TIME sentinel that restored wallets are
            // stamped with (WalletFactory.restoreWalletFromSeed always seeds at
            // the oldest possible time), so only a REAL user-provided restore
            // date survives; the wallet's own earliestKeyCreationTime is used
            // as a fallback under the same sanity rule (also excludes epoch /
            // pre-2014 values — the sentinel is 2015-03-29).
            val defaultSecs = runCatching { blockchainServiceConfig.getWalletCreationDate() }.getOrNull()
                ?: walletData.wallet?.earliestKeyCreationTime
                    ?.takeIf { it > Constants.EARLIEST_HD_SEED_CREATION_TIME }
            _dashjSyncFromPrompt.value = DashjSyncFromPrompt(defaultDateMillis = defaultSecs?.times(1000L))
        }
    }

    /**
     * "Sync from date" confirmed with a date: persist the start date FIRST
     * (the service must never see the flag ON with a stale/unsettled date),
     * then flip the flag ON and bounce the service. Syncing from a date at or
     * before the wallet's creation date still sees every wallet transaction —
     * coins cannot predate the wallet's keys — so the parity verdict stays
     * valid.
     */
    fun confirmDashjSyncFromDate(dateMillis: Long) {
        enableDashjSyncDiagnostic(fromSecs = dateMillis / 1000L)
    }

    /** "Sync everything (from the beginning)" chosen: 0 = no start date. */
    fun confirmDashjSyncFromBeginning() {
        enableDashjSyncDiagnostic(fromSecs = 0L)
    }

    /** Prompt dismissed/cancelled: the toggle stays OFF, nothing persisted. */
    fun cancelDashjSyncFromPrompt() {
        _dashjSyncFromPrompt.value = null
    }

    private fun enableDashjSyncDiagnostic(fromSecs: Long) {
        _dashjSyncFromPrompt.value = null
        viewModelScope.launch {
            dashPayConfig.setDashjSyncDiagnosticFromSecs(fromSecs)
            dashPayConfig.setDashjSyncDiagnostic(true)
            walletApplication.restartBlockchainService()
        }
    }

    suspend fun setCreditsExplained() = dashPayConfig.set(DashPayConfig.CREDIT_INFO_SHOWN, true)

    /**
     * Persist "the credits explainer has been seen" from a scope that
     * OUTLIVES the dialog. Writing it inside the sheet's own lifecycle
     * scope loses the race when the user dismisses immediately — the
     * coroutine is cancelled before the DataStore write lands and the
     * explainer re-appears on the next visit (observed on device).
     * [NonCancellable] also protects it from this ViewModel being cleared
     * as the Buy Credits screen launches.
     */
    fun markCreditsExplained() {
        viewModelScope.launch(NonCancellable) {
            try {
                setCreditsExplained()
            } catch (e: Exception) {
                log.warn("failed to persist the credits-explainer flag", e)
            }
        }
    }

    suspend fun creditsExplained() = dashPayConfig.get(DashPayConfig.CREDIT_INFO_SHOWN) ?: false

    suspend fun hasUsername(): Boolean {
        return identityConfig.get(BlockchainIdentityConfig.IDENTITY_ID) != null &&
                (IdentityCreationState.valueOf(identityConfig.get(BlockchainIdentityConfig.CREATION_STATE)
                    ?: "NONE") >= IdentityCreationState.DONE)
    }

    fun logEvent(event: String) {
        analyticsService.logEvent(event, mapOf())
    }
}
