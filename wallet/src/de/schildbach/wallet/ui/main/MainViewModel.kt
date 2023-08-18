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

package de.schildbach.wallet.ui.main

import android.os.Build
import android.os.LocaleList
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.transactions.TxDirectionFilter
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.ui.transactions.TransactionRowView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.CurrencyInfo
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperComparator
import org.dash.wallet.common.util.Constants.HTTP_CLIENT
import org.dash.wallet.common.util.head
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import org.slf4j.LoggerFactory
import java.util.Currency
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val analytics: AnalyticsService,
    private val config: Configuration,
    private val walletUIConfig: WalletUIConfig,
    exchangeRatesProvider: ExchangeRatesProvider,
    val walletData: WalletDataProvider,
    private val savedStateHandle: SavedStateHandle,
    private val metadataProvider: TransactionMetadataProvider,
    private val blockchainStateProvider: BlockchainStateProvider,
    val biometricHelper: BiometricHelper,
    private val telephonyManager: TelephonyManager
) : ViewModel() {
    companion object {
        private const val THROTTLE_DURATION = 500L
        private const val DIRECTION_KEY = "tx_direction"
        private val log = LoggerFactory.getLogger(MainViewModel::class.java)
    }

    private val workerJob = SupervisorJob()
    @VisibleForTesting
    val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    val balanceDashFormat: MonetaryFormat = config.format.noCode().minDecimals(0)
    val fiatFormat: MonetaryFormat = Constants.LOCAL_FORMAT.minDecimals(0).optionalDecimals(0, 2)

    private val _transactions = MutableLiveData<List<TransactionRowView>>()
    val transactions: LiveData<List<TransactionRowView>>
        get() = _transactions

    private val _transactionsDirection = MutableStateFlow(TxFilterType.ALL)
    var transactionsDirection: TxFilterType
        get() = _transactionsDirection.value
        set(value) {
            _transactionsDirection.value = value
            savedStateHandle[DIRECTION_KEY] = value
        }

    private val _isBlockchainSynced = MutableLiveData<Boolean>()
    val isBlockchainSynced: LiveData<Boolean>
        get() = _isBlockchainSynced

    private val _isBlockchainSyncFailed = MutableLiveData<Boolean>()
    val isBlockchainSyncFailed: LiveData<Boolean>
        get() = _isBlockchainSyncFailed

    private val _blockchainSyncPercentage = MutableLiveData<Int>()
    val blockchainSyncPercentage: LiveData<Int>
        get() = _blockchainSyncPercentage

    private val _exchangeRate = MutableLiveData<ExchangeRate>()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _balance = MutableLiveData<Coin>()
    val balance: LiveData<Coin>
        get() = _balance

    private val _mostRecentTransaction = MutableLiveData<Transaction>()
    val mostRecentTransaction: LiveData<Transaction>
        get() = _mostRecentTransaction

    private val _temporaryHideBalance = MutableStateFlow<Boolean?>(null)
    val hideBalance = walletUIConfig.observe(WalletUIConfig.AUTO_HIDE_BALANCE)
        .combine(_temporaryHideBalance) { autoHide, temporaryHide ->
            temporaryHide ?: autoHide ?: false
        }
        .asLiveData()

    val showTapToHideHint = walletUIConfig.observe(WalletUIConfig.SHOW_TAP_TO_HIDE_HINT).asLiveData()

    val isPassphraseVerified: Boolean
        get() = !config.remindBackupSeed

    private val _stakingAPY = MutableLiveData<Double>()
    val stakingAPY: LiveData<Double>
        get() = _stakingAPY

    val currencyChangeDetected = SingleLiveEvent<Pair<String, String>>()

    init {
        transactionsDirection = savedStateHandle[DIRECTION_KEY] ?: TxFilterType.ALL

        _transactionsDirection
            .flatMapLatest { direction ->
                metadataProvider.observePresentableMetadata()
                    .flatMapLatest { metadata ->
                        val filter = TxDirectionFilter(direction, walletData.wallet!!)
                        refreshTransactions(filter, metadata)
                        walletData.observeWalletChanged()
                            .debounce(THROTTLE_DURATION)
                            .onEach { refreshTransactions(filter, metadata) }
                    }
            }
            .catch { analytics.logError(it, "is wallet null: ${walletData.wallet == null}") }
            .launchIn(viewModelWorkerScope)

        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach { state ->
                updateSyncStatus(state)
                updatePercentage(state)
            }
            .launchIn(viewModelWorkerScope)

        walletData.observeBalance()
            .onEach(_balance::postValue)
            .launchIn(viewModelScope)

        walletData.observeMostRecentTransaction()
            .onEach(_mostRecentTransaction::postValue)
            .launchIn(viewModelScope)

        walletUIConfig
            .observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeExchangeRate(code)
                    .filterNotNull()
            }
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    fun logError(ex: Exception, details: String) {
        analytics.logError(ex, details)
    }

    fun triggerHideBalance() {
        val isHiding = hideBalance.value ?: false
        _temporaryHideBalance.value = !isHiding

        if (_temporaryHideBalance.value == true) {
            logEvent(AnalyticsConstants.Home.HIDE_BALANCE)
        } else {
            logEvent(AnalyticsConstants.Home.SHOW_BALANCE)
        }

        viewModelScope.launch { walletUIConfig.set(WalletUIConfig.SHOW_TAP_TO_HIDE_HINT, false) }
    }

    fun logDirectionChangedEvent(direction: TxFilterType) {
        val directionParameter = when (direction) {
            TxFilterType.ALL -> "all_transactions"
            TxFilterType.SENT -> "sent_transactions"
            TxFilterType.RECEIVED -> "received_transactions"
            TxFilterType.GIFT_CARD -> "gift_cards"
        }
        analytics.logEvent(
            AnalyticsConstants.Home.TRANSACTION_FILTER,
            mapOf(
                AnalyticsConstants.Parameter.VALUE to directionParameter
            )
        )

        if (direction == TxFilterType.GIFT_CARD) {
            analytics.logEvent(AnalyticsConstants.DashDirect.FILTER_GIFT_CARD, mapOf())
        }
    }

    fun processDirectTransaction(tx: Transaction) {
        walletData.processDirectTransaction(tx)
    }

    fun getLastStakingAPY() {
        viewModelScope.launch(Dispatchers.IO) {
            _stakingAPY.postValue(0.85 * blockchainStateProvider.getLastMasternodeAPY())
        }
    }

    suspend fun getDeviceTimeSkew(): Long {
        return try {
            val systemTimeMillis = System.currentTimeMillis()
            val result = HTTP_CLIENT.head("https://www.dash.org/")
            val networkTime = result.headers.getDate("date")?.time
            requireNotNull(networkTime)
            abs(systemTimeMillis - networkTime)
        } catch (ex: Exception) {
            // Ignore errors
            0L
        }
    }

    /**
     * Get ISO 3166-1 alpha-2 country code for this device (or null if not available)
     * If available, call [.showFiatCurrencyChangeDetectedDialog]
     * passing the country code.
     */
    fun detectUserCountry() = viewModelScope.launch {
        if (walletUIConfig.get(WalletUIConfig.EXCHANGE_CURRENCY_DETECTED) == true) {
            return@launch
        }

        val selectedCurrencyCode = walletUIConfig.get(WalletUIConfig.SELECTED_CURRENCY)

        try {
            val simCountry = telephonyManager.simCountryIso
            log.info("Detecting currency based on device, mobile network or locale:")

            if (simCountry != null && simCountry.length == 2) { // SIM country code is available
                log.info("Device Sim Country: $simCountry")
                updateCurrencyExchange(simCountry.uppercase(Locale.getDefault()))
            } else if (telephonyManager.phoneType != TelephonyManager.PHONE_TYPE_CDMA) {
                // device is not 3G (would be unreliable)
                val networkCountry = telephonyManager.networkCountryIso
                log.info("Network Country: $simCountry")
                if (networkCountry != null && networkCountry.length == 2) { // network country code is available
                    updateCurrencyExchange(networkCountry.uppercase(Locale.getDefault()))
                } else {
                    // Couldn't obtain country code - Use Default
                    if (selectedCurrencyCode == null) {
                        setDefaultCurrency()
                    }
                }
            } else {
                // No cellular network - Wifi Only
                if (selectedCurrencyCode == null) {
                    setDefaultCurrency()
                }
            }
        } catch (e: java.lang.Exception) {
            // fail safe
            log.info("NMA-243:  Exception thrown obtaining Locale information: ", e)
            if (selectedCurrencyCode == null) {
                setDefaultCurrency()
            }
        }
    }

    fun setExchangeCurrencyCodeDetected(currencyCode: String?) {
        viewModelScope.launch {
            currencyCode?.let { walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, it) }
            walletUIConfig.set(WalletUIConfig.EXCHANGE_CURRENCY_DETECTED, true)
        }
    }

    private fun refreshTransactions(filter: TxDirectionFilter, metadata: Map<Sha256Hash, PresentableTxMetadata>) {
        walletData.wallet?.let { wallet ->
            val transactionViews = walletData.wrapAllTransactions(
                FullCrowdNodeSignUpTxSet(walletData.networkParameters, wallet)
            ).filter { it.passesFilter(filter, metadata) }
                .sortedWith(TransactionWrapperComparator())
                .map {
                    TransactionRowView.fromTransactionWrapper(
                        it,
                        walletData.transactionBag,
                        Constants.CONTEXT,
                        metadata[it.transactions.first().txId]
                    )
                }
            _transactions.postValue(transactionViews)
        }
    }

    private fun updateSyncStatus(state: BlockchainState) {
        if (_isBlockchainSynced.value != state.isSynced()) {
            _isBlockchainSynced.postValue(state.isSynced())

            if (state.isSynced()) {
                viewModelScope.launch(Dispatchers.IO) {
                    _stakingAPY.postValue(0.85 * blockchainStateProvider.getMasternodeAPY())
                }
            }

            if (state.replaying) {
                _transactions.postValue(listOf())
            }
        }

        _isBlockchainSyncFailed.postValue(state.syncFailed())
    }

    private fun updatePercentage(state: BlockchainState) {
        var percentage = state.percentageSync

        if (state.replaying && state.percentageSync == 100) {
            // This is to prevent showing 100% when using the Rescan blockchain function.
            // The first few broadcasted blockchainStates are with percentage sync at 100%
            percentage = 0
        }
        _blockchainSyncPercentage.postValue(percentage)
    }

    private fun TransactionWrapper.passesFilter(
        filter: TxDirectionFilter,
        metadata: Map<Sha256Hash, PresentableTxMetadata>
    ): Boolean {
        return (filter.direction == TxFilterType.GIFT_CARD && isGiftCard(metadata)) ||
            transactions.any { tx -> filter.matches(tx) }
    }

    private fun TransactionWrapper.isGiftCard(metadata: Map<Sha256Hash, PresentableTxMetadata>): Boolean {
        return metadata[transactions.first().txId]?.service == ServiceName.DashDirect
    }

    /**
     * Check whether app was ever updated or if it is an installation that was never updated.
     * Show dialog to update if it's being updated or change it automatically.
     *
     * @param countryCode countryCode ISO 3166-1 alpha-2 country code.
     */
    private suspend fun updateCurrencyExchange(countryCode: String) {
        log.info("Updating currency exchange rate based on country: $countryCode")
        val l = Locale("", countryCode)
        val currency = Currency.getInstance(l)
        var newCurrencyCode = currency.currencyCode
        val currentCurrencyCode = walletUIConfig.getExchangeCurrencyCode()

        if (!currentCurrencyCode.equals(newCurrencyCode, ignoreCase = true)) {
            if (config.wasUpgraded()) {
                currencyChangeDetected.postValue(Pair(currentCurrencyCode, newCurrencyCode))
            } else {
                if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
                    log.info("found obsolete currency: $newCurrencyCode")
                    newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode)
                }
                // check to see if we use a different currency code for exchange rates
                newCurrencyCode = CurrencyInfo.getOtherName(newCurrencyCode)
                log.info("Setting Local Currency: $newCurrencyCode")
                walletUIConfig.set(WalletUIConfig.EXCHANGE_CURRENCY_DETECTED, true)
                walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, newCurrencyCode)
            }
        }

        // Fallback to default
        if (walletUIConfig.get(WalletUIConfig.SELECTED_CURRENCY) == null) {
            setDefaultExchangeCurrencyCode()
        }
    }

    private suspend fun setDefaultCurrency() {
        val countryCode = getCurrentCountry()
        log.info("Setting default currency:")

        try {
            log.info("Local Country: $countryCode")
            val l = Locale("", countryCode)
            val currency = Currency.getInstance(l)
            var newCurrencyCode = currency.currencyCode

            if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
                log.info("found obsolete currency: $newCurrencyCode")
                newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode)
            }

            // check to see if we use a different currency code for exchange rates
            newCurrencyCode = CurrencyInfo.getOtherName(newCurrencyCode)
            log.info("Setting Local Currency: $newCurrencyCode")
            walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, newCurrencyCode)

            // Fallback to default
            if (walletUIConfig.get(WalletUIConfig.SELECTED_CURRENCY) == null) {
                setDefaultExchangeCurrencyCode()
            }
        } catch (x: IllegalArgumentException) {
            log.info("Cannot obtain currency for $countryCode: ", x)
            setDefaultExchangeCurrencyCode()
        }
    }

    private suspend fun setDefaultExchangeCurrencyCode() {
        log.info("Using default Country: US")
        log.info(
            "Using default currency: " +
                org.dash.wallet.common.util.Constants.DEFAULT_EXCHANGE_CURRENCY
        )
        walletUIConfig.set(
            WalletUIConfig.SELECTED_CURRENCY,
            org.dash.wallet.common.util.Constants.DEFAULT_EXCHANGE_CURRENCY
        )
    }

    private fun getCurrentCountry(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList.getDefault()[0].country
        } else {
            Locale.getDefault().country
        }
    }
}
