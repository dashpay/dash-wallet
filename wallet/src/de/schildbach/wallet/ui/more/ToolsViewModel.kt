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
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.transactions.TaxBitExporter
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import org.bitcoinj.crypto.DeterministicKey
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.*
import javax.inject.Inject

data class ToolsUIState(
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val hasUsername: Boolean = false
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val walletData: WalletDataProvider,
    private val clipboardManager: ClipboardManager,
    private val transactionMetadataProvider: TransactionMetadataProvider,
    blockchainStateDao: BlockchainStateDao,
    private val dashPayConfig: DashPayConfig,
    private val identityConfig: BlockchainIdentityConfig,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    sealed class ExportCsvResult {
        object Idle : ExportCsvResult()
        object Loading : ExportCsvResult()
        data class Success(val file: File) : ExportCsvResult()
        object Error : ExportCsvResult()
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
        }

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
                val file = withContext(Dispatchers.IO) {
                    val exporter = TaxBitExporter(transactionMetadataProvider, walletData.wallet!!)
                    exporter.initMetadataMap()
                    val csvContent = exporter.exportString()
                    val reportDir = File(cacheDir, "report").also { it.mkdirs() }
                    val f = File.createTempFile("transaction-history.", ".csv", reportDir)
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

    suspend fun setCreditsExplained() = dashPayConfig.set(DashPayConfig.CREDIT_INFO_SHOWN, true)

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
