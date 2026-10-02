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

package de.schildbach.wallet.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.google.common.collect.Comparators.max
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.database.dao.TransactionMetadataDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.platform.work.PublishTransactionMetadataOperation
import de.schildbach.wallet.service.platform.work.TransactionMetadataSaveQueue
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.utils.TransactionMetadataSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.Fiat
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.TransactionCategory
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toFormattedString
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.Currency
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash
import de.schildbach.wallet.util.toFormattedString

enum class TxMetadataSaveFrequency {
    afterTenTransactions,
    oncePerWeek,
    afterEveryTransaction;

    companion object {
        val defaultOption = afterTenTransactions
    }
}

/** Dates are epoch millis; 0 means "never" / "not known yet". */
data class TransactionMetadataSettingsUIState(
    /** the settings as edited on screen, not yet saved */
    val settings: TransactionMetadataSettings = TransactionMetadataSettings(),
    val lastSaveWorkId: String? = null,
    val lastSaveDate: Long = 0,
    val futureSaveDate: Long = 0,
    val hasPastTransactionsToSave: Boolean = false,
    val unsavedTxCount: Int = 0,
    val firstUnsavedTxDate: Long = 0,
    val selectedExchangeRate: ExchangeRate? = null
) {
    /**
     * The date unsaved transactions go back to, or null when none is known:
     * the unsaved-transaction scan may still be running, or the only unsaved
     * items are cached edits. Never format the 0 sentinel — it reads as 1970.
     */
    val unsavedSinceDate: Long?
        get() = listOf(lastSaveDate, firstUnsavedTxDate).firstOrNull { it > 0 }
}

interface TransactionMetadataSettingsPreviewViewModel {
    val uiState: StateFlow<TransactionMetadataSettingsUIState>
    fun updatePreferences(settings: TransactionMetadataSettings)
    fun observePublishOperation(workId: String): Flow<Resource<WorkInfo>>
}

@ExperimentalCoroutinesApi
@HiltViewModel
class TransactionMetadataSettingsViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val dashPayConfig: DashPayConfig,
    walletUIConfig: WalletUIConfig,
    exchangeRates: ExchangeRatesRepository,
    private val analyticsService: AnalyticsService,
    private val transactionMetadataDao: TransactionMetadataDao,
    private val transactionMetadataChangeCacheDao: TransactionMetadataChangeCacheDao,
    private val platformSyncService: PlatformSyncService,
    private val saveQueue: TransactionMetadataSaveQueue,
    private val publishOperation: PublishTransactionMetadataOperation
) : ViewModel(), TransactionMetadataSettingsPreviewViewModel {
    companion object {
        val CURRENT_DATA_COST = Coin.valueOf(25000) //0.00025000
        private val log = LoggerFactory.getLogger(TransactionMetadataSettingsViewModel::class.java)
    }
    private val _uiState = MutableStateFlow(TransactionMetadataSettingsUIState())
    override val uiState: StateFlow<TransactionMetadataSettingsUIState> = _uiState.asStateFlow()
    private var originalState: TransactionMetadataSettings? = null

    private var selectedCurrency: String = Constants.USD_CURRENCY
    private val _oldUnsavedTransactions = MutableStateFlow<List<org.dash.wallet.common.transactions.TxInfo>>(listOf())

    init {
        // Re-emits on every DashPayConfig write, and a save queued by an
        // earlier visit to this screen can still be writing after the user
        // has started editing here. Edits are the user's: once the draft is
        // modified, persisted settings no longer replace it. An unedited draft
        // that adopts them rebases on them too, or reversing that write back
        // to the older value would read as unmodified and disable Save.
        dashPayConfig.observeTransactionMetadataSettings()
            .distinctUntilChanged()
            .onEach { persisted ->
                if (originalState == null || !_uiState.value.settings.modified) {
                    originalState = persisted
                }
                _uiState.update { state ->
                    if (state.settings.modified) state else state.copy(settings = persisted)
                }
            }.launchIn(viewModelScope)

        // Written by PublishTransactionMetadataWorker on a complete publish;
        // this is the only thing that tells a saved wallet from an unsaved one.
        dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE)
            .onEach {
                _uiState.update { state -> state.copy(lastSaveDate = it ?: 0) }
                log.info("last save date: {}", it?.let { Date(it) })
            }
            .launchIn(viewModelScope)

        dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_SAVE_AFTER)
            .onEach {
                _uiState.update { state -> state.copy(futureSaveDate = it ?: 0) }
                log.info("future save date: {}", it?.let { Date(it) })
            }
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { selectedCurrency = it }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach { rate -> _uiState.update { it.copy(selectedExchangeRate = rate) } }
            .launchIn(viewModelScope)

        dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE)
            .flatMapLatest { startTimestamp ->
                transactionMetadataDao.observeByTimestampRange(startTimestamp ?: 0, System.currentTimeMillis())
                    .flatMapLatest {
                        // COMBINE, not a chain: "is there past metadata worth
                        // uploading?" is two independent populations, and either
                        // can change on its own.
                        //
                        //  - the change cache: pending edits
                        //  - _oldUnsavedTransactions: history never published
                        //
                        // Reading the second imperatively raced it — the scan
                        // behind it takes seconds on a large wallet, so the flow
                        // emitted "0 never-published" first and never re-emitted
                        // when the real count landed, leaving "Past" disabled on
                        // exactly the wallets that had the most to upload.
                        combine(
                            transactionMetadataChangeCacheDao
                                .observeCachedItemsBefore(System.currentTimeMillis()),
                            _oldUnsavedTransactions
                        ) { cachedItems, oldUnsaved -> cachedItems.size to oldUnsaved.size }
                    }
            }
            .onEach { (cachedCount, neverPublishedCount) ->
                val unsavedTxCount = cachedCount + neverPublishedCount
                _uiState.update {
                    it.copy(hasPastTransactionsToSave = unsavedTxCount > 0, unsavedTxCount = unsavedTxCount)
                }
                log.info(
                    "unsaved count: {} ({} cached + {} never-published)",
                    unsavedTxCount, cachedCount, neverPublishedCount
                )
            }
            .launchIn(viewModelScope)

        viewModelScope.launch(Dispatchers.IO) {
            val (oldUnsavedList, firstUnsavedDate) = platformSyncService.getUnsavedTransactions()
            log.info("old unsaved count: ${oldUnsavedList.size}")
            _uiState.update { it.copy(firstUnsavedTxDate = firstUnsavedDate) }
            _oldUnsavedTransactions.value = oldUnsavedList
        }
    }

    suspend fun saveDataToNetwork(saveToNetwork: Boolean) {
        dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_SAVE_TO_NETWORK, saveToNetwork)
        if (dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_SAVE_AFTER) == null) {
            dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_SAVE_AFTER, System.currentTimeMillis())
        }
    }

    suspend fun setTransactionMetadataInfoShown() = dashPayConfig.setTransactionMetadataInfoShown()

    private suspend fun savePreferences(settings: TransactionMetadataSettings) {
        log.info("save settings: {}", settings)
        dashPayConfig.setTransactionMetadataSettings(settings)
    }

    override fun updatePreferences(settings: TransactionMetadataSettings) {
        val modified = !settings.isEqual(originalState)
        _uiState.update { it.copy(settings = settings.copy(modified = modified)) }
        log.info("modified $modified\n  ${_uiState.value.settings}\n  $originalState")
    }

    val saveToNetwork = dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_SAVE_TO_NETWORK)

    fun getBalanceInLocalFormat(): String {
        _uiState.value.selectedExchangeRate?.fiat?.let {
            val exchangeRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, it.toDashjFiat())
            val fiatValue = exchangeRate.coinToFiat(CURRENT_DATA_COST)
            val minValue = try {
                val fractionDigits = Currency.getInstance(selectedCurrency).defaultFractionDigits
                val newValue = BigDecimal.ONE.movePointLeft(fractionDigits)
                Fiat.parseFiat(fiatValue.currencyCode, newValue.toPlainString())
            } catch (e: Exception) {
                Fiat.parseFiat(fiatValue.currencyCode, "0.01")
            }
            return max(fiatValue, minValue).toFormattedString()
        }

        return ""
    }

    private suspend fun getNextWorkId(): String {
        val newId = UUID.randomUUID().toString()
        dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_LAST_SAVE_WORK_ID, newId)
        _uiState.update { it.copy(lastSaveWorkId = newId) }
        log.info("last save work id: {}", dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_LAST_SAVE_WORK_ID))
        log.info("last save work id should be: {}", newId)
        return newId
    }

    suspend fun loadLastWorkId() {
        val workId = dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_LAST_SAVE_WORK_ID)
        _uiState.update { it.copy(lastSaveWorkId = workId) }
    }

    /**
     * save using current settings
     *
     * Queued on [TransactionMetadataSaveQueue], which runs in the application
     * scope, not the screen's: every caller pops the screen right after this
     * returns, which clears the ViewModel and cancels its scopes while the
     * DataStore writes below are still in flight — the publish was never
     * enqueued on ~1 in 3 attempts, with no sign of it. The queue also keeps a
     * save from a reopened screen from being overtaken by an earlier one, and
     * lets Reset Wallet stop it.
     */
    fun saveToNetwork(forceSave: Boolean) {
        val settings = _uiState.value.settings
        saveQueue.submit {
            val previousSettings = dashPayConfig.getTransactionMetadataSettings()
            savePreferences(settings)
            if (settings.saveToNetwork) {
                if (!previousSettings.saveToNetwork) {
                    dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_SAVE_AFTER, System.currentTimeMillis())
                }
            }
            if (forceSave || settings.savePastTxToNetwork) {
                val workId = getNextWorkId()
                commit { publishOperation.create(workId).enqueue() }
            }
        }
    }

    /**
     * Publish now, behind any save already queued, and return the publish's
     * work id.
     *
     * @throws TransactionMetadataSaveQueue.SaveDiscardedException if a wallet
     *   reset stopped it
     */
    suspend fun saveToNetworkNow(): String = saveQueue.submitAndAwait {
        val workId = getNextWorkId()
        commit { publishOperation.create(workId).enqueue() }
            ?: throw TransactionMetadataSaveQueue.SaveDiscardedException()
        workId
    }

    override fun observePublishOperation(workId: String): Flow<Resource<WorkInfo>> = PublishTransactionMetadataOperation.operationStatusFlow(
        walletApplication,
        workId,
        analyticsService
    )

    fun hasPastTransactionsToSave(): Boolean {
        // TODO: optimize this

        return false
    }
}
