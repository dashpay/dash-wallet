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

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.google.common.collect.Comparators.max
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.TransactionMetadataDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet.service.platform.work.PublishTransactionMetadataOperation
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.utils.TransactionMetadataSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toFormattedString
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.Currency
import java.util.Date
import java.util.UUID
import javax.inject.Inject

enum class TxMetadataSaveFrequency {
    afterTenTransactions,
    oncePerWeek,
    afterEveryTransaction;

    companion object {
        val defaultOption = afterTenTransactions
    }
}

interface TransactionMetadataSettingsPreviewViewModel {
    val filterState: StateFlow<TransactionMetadataSettings>
    val hasPastTransactionsToSave: StateFlow<Boolean>
    fun updatePreferences(settings: TransactionMetadataSettings)
    val lastSaveWorkId: StateFlow<String?>
    val lastSaveDate: StateFlow<Long>
    val futureSaveDate: StateFlow<Long>
    fun publishOperationLiveData(workId: String): LiveData<Resource<WorkInfo>>
}

@ExperimentalCoroutinesApi
@HiltViewModel
class TransactionMetadataSettingsViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val dashPayConfig: DashPayConfig,
    private val blockchainIdentityConfig: BlockchainIdentityConfig,
    walletUIConfig: WalletUIConfig,
    exchangeRates: ExchangeRatesRepository,
    private val analyticsService: AnalyticsService,
    private val transactionMetadataDao: TransactionMetadataDao
) : ViewModel(), TransactionMetadataSettingsPreviewViewModel {
    companion object {
        val CURRENT_DATA_COST = Coin.valueOf(25000) //0.00025000
        private val log = LoggerFactory.getLogger(TransactionMetadataSettingsViewModel::class.java)
    }
    private val _filterState = MutableStateFlow(TransactionMetadataSettings())
    override val filterState: StateFlow<TransactionMetadataSettings> = _filterState.asStateFlow()
    private var originalState: TransactionMetadataSettings? = null
    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)
    private var _selectedExchangeRate = MutableStateFlow<ExchangeRate?>(null)
    val selectedExchangeRate = _selectedExchangeRate.asStateFlow()
    private var selectedCurrency: String = Constants.USD_CURRENCY
    //private val savePastTxToNetwork = MutableStateFlow(false)
    private val _lastSaveWorkId = MutableStateFlow<String?>(null)
    override val lastSaveWorkId = _lastSaveWorkId.asStateFlow()
    private val _lastSaveDate = MutableStateFlow<Long>(-1)
    override val lastSaveDate = _lastSaveDate.asStateFlow()
    private val _futureSaveDate = MutableStateFlow<Long>(-1)
    override val futureSaveDate = _lastSaveDate.asStateFlow()
    private val _hasPastTransactionsToSave = MutableStateFlow<Boolean>(false)
    override val hasPastTransactionsToSave = _hasPastTransactionsToSave.asStateFlow()

    private val publishOperation = PublishTransactionMetadataOperation(walletApplication)

    init {
        dashPayConfig.observeTransactionMetadataSettings()
            .onEach {
                _filterState.value = it
                if (originalState == null) {
                    originalState = it
                }
            }.launchIn(viewModelWorkerScope)

        dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE)
            .onEach {
                _lastSaveDate.value = it ?: 0
                log.info("last save date: {}", it?.let { Date(_lastSaveDate.value) })
            }
            .launchIn(viewModelScope)

        dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_SAVE_AFTER)
            .onEach {
                _futureSaveDate.value = it ?: System.currentTimeMillis()
                log.info("future save date: {}", it?.let { Date(_lastSaveDate.value) })
            }
            .launchIn(viewModelScope)

//        dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_LAST_SAVE_WORK_ID)
//            .onEach {
//                _lastSaveWorkId.value = it
//                log.info("last save work id: {}", dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_LAST_SAVE_WORK_ID))
//            }
//            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { selectedCurrency = it }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach { _selectedExchangeRate.value = it }
            .launchIn(viewModelScope)

        dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE)
            .flatMapLatest { transactionMetadataDao.observeByTimestampRange(it ?: 0, System.currentTimeMillis()) }
            .onEach { _hasPastTransactionsToSave.value = it.isNotEmpty() }
            .launchIn(viewModelWorkerScope)
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
        _filterState.value = settings.copy(modified = modified)
        log.info("modified $modified\n  ${_filterState.value}\n  $originalState")
    }

    val saveToNetwork = dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_SAVE_TO_NETWORK)

    fun getBalanceInLocalFormat(): String {
        selectedExchangeRate.value?.fiat?.let {
            val exchangeRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, it)
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
        _lastSaveWorkId.value = newId
        log.info("last save work id: {}", dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_LAST_SAVE_WORK_ID))
        log.info("last save work id should be: {}", newId)
        return newId
    }

    suspend fun loadLastWorkId() {
        _lastSaveWorkId.value = dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_LAST_SAVE_WORK_ID)
    }

    /** save using current settings */
    fun saveToNetwork(forceSave: Boolean) {
        viewModelWorkerScope.launch {
            val previousSettings = dashPayConfig.getTransactionMetadataSettings()
            val settings = filterState.value
            savePreferences(settings)
            if (settings.saveToNetwork) {
                if (!previousSettings.saveToNetwork) {
                    dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_SAVE_AFTER, System.currentTimeMillis())
                }
            }
            if (forceSave || settings.savePastTxToNetwork) {
                // TODO: save here
                publishOperation.create(
                    getNextWorkId()
                ).enqueue()
            }
        }
    }

    /** save using current settings */
    suspend fun saveToNetworkNow(): String {
        val nextId = getNextWorkId()
        publishOperation.create(
            nextId
        ).enqueue()
        return nextId
    }

    override fun publishOperationLiveData(workId: String) = PublishTransactionMetadataOperation.operationStatus(
        walletApplication,
        workId,
        analyticsService
    )

    fun hasPastTransactionsToSave(): Boolean {
        // TODO: optimize this

        return false
    }
}
