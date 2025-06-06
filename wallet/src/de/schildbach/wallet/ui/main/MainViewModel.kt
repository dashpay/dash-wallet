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

import android.os.LocaleList
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.google.common.base.Stopwatch
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.SeriousErrorLiveData
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.DeviceInfoProvider
import de.schildbach.wallet.service.MAX_ALLOWED_AHEAD_TIMESKEW
import de.schildbach.wallet.service.MAX_ALLOWED_BEHIND_TIMESKEW
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.transactions.TxDirectionFilter
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.transactions.coinjoin.CoinJoinTxWrapperFactory
import de.schildbach.wallet.ui.dashpay.BaseContactsViewModel
import de.schildbach.wallet.ui.dashpay.NotificationCountLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import de.schildbach.wallet.ui.transactions.TransactionRowView
import de.schildbach.wallet.ui.transactions.TxResourceMapper
import de.schildbach.wallet.util.getTimeSkew
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.WalletEx
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
import org.dash.wallet.common.services.RateRetrievalState
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.batchAndFilterUpdates
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSetFactory
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.collections.set
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    val analytics: AnalyticsService,
    private val config: Configuration,
    private val walletUIConfig: WalletUIConfig,
    exchangeRatesProvider: ExchangeRatesProvider,
    val walletData: WalletDataProvider,
    private val walletApplication: WalletApplication,
    val platformRepo: PlatformRepo,
    val platformService: PlatformService,
    val platformSyncService: PlatformSyncService,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    private val savedStateHandle: SavedStateHandle,
    private val metadataProvider: TransactionMetadataProvider,
    private val blockchainStateProvider: BlockchainStateProvider,
    val biometricHelper: BiometricHelper,
    private val deviceInfo: DeviceInfoProvider,
    private val invitationsDao: InvitationsDao,
    userAlertDao: UserAlertDao,
    dashPayProfileDao: DashPayProfileDao,
    dashPayConfig: DashPayConfig,
    dashPayContactRequestDao: DashPayContactRequestDao,
    private val coinJoinConfig: CoinJoinConfig,
    private val coinJoinService: CoinJoinService
) : BaseContactsViewModel(blockchainIdentityDataDao, dashPayProfileDao, dashPayContactRequestDao) {
    companion object {
        private const val BATCHING_PERIOD = 500L
        private const val DIRECTION_KEY = "tx_direction"
        private const val TIME_SKEW_TOLERANCE = 3600000L // seconds (1 hour)

        private val log = LoggerFactory.getLogger(MainViewModel::class.java)
    }
    private val workerJob = SupervisorJob()

    @VisibleForTesting
    val viewModelWorkerScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + workerJob)

    val balanceDashFormat: MonetaryFormat = config.format.noCode().minDecimals(0)
    val fiatFormat: MonetaryFormat = Constants.LOCAL_FORMAT.minDecimals(0).optionalDecimals(0, 2)

    var transactionsLoaded = false
        private set
    private val _transactions = MutableStateFlow<Map<LocalDate, List<TransactionRowView>>>(mapOf())
    val transactions: StateFlow<Map<LocalDate, List<TransactionRowView>>>
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
    private var chainLockBlockHeight: Int = 0

    private val _exchangeRate = MutableLiveData<ExchangeRate>()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _rateStale = MutableStateFlow(
        RateRetrievalState(
            lastAttemptFailed = false,
            staleRates = false,
            volatile = false
        )
    )
    val rateStale: Flow<RateRetrievalState>
        get() = _rateStale
    val currentStaleRateState
        get() = _rateStale.value
    var rateStaleDismissed = false

    private val _totalBalance = MutableLiveData<Coin>()
    val totalBalance: LiveData<Coin>
        get() = _totalBalance
    private val _mixedBalance = MutableLiveData<Coin>()
    val mixedBalance: LiveData<Coin>
        get() = _mixedBalance

    private var txByHash: Map<String, TransactionRowView> = mapOf()
    private var metadata: Map<Sha256Hash, PresentableTxMetadata> = mapOf()
    private var contacts: Map<String, DashPayProfile> = mapOf()
    private var minContactCreatedDate: LocalDate = LocalDate.now()
    private lateinit var crowdNodeWrapperFactory: FullCrowdNodeSignUpTxSetFactory
    private lateinit var coinJoinWrapperFactory: CoinJoinTxWrapperFactory
    private val _temporaryHideBalance = MutableStateFlow<Boolean?>(null)
    val hideBalance = walletUIConfig.observe(WalletUIConfig.AUTO_HIDE_BALANCE)
        .combine(_temporaryHideBalance) { autoHide, temporaryHide ->
            temporaryHide ?: autoHide ?: false
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val showTapToHideHint = walletUIConfig.observe(WalletUIConfig.SHOW_TAP_TO_HIDE_HINT)

    private val _isNetworkUnavailable = MutableLiveData<Boolean>()
    val isNetworkUnavailable: LiveData<Boolean>
        get() = _isNetworkUnavailable

    val currencyChangeDetected = SingleLiveEvent<Pair<String, String>>()

    // CoinJoin
    val coinJoinMode: Flow<CoinJoinMode>
        get() = coinJoinConfig.observeMode()
    val mixingState: Flow<MixingStatus>
        get() = coinJoinService.observeMixingState()
    val mixingProgress: Flow<Double>
        get() = coinJoinService.observeMixingProgress()
    val mixingSessions: Flow<Int>
        get() = coinJoinService.observeActiveSessions()

    var decimalFormat: DecimalFormat = DecimalFormat("0.000")
    val walletBalanceString: String
        get() = decimalFormat.format(totalBalance.value?.toBigDecimal() ?: BigDecimal.ZERO)

    val mixedBalanceString: String
        get() = decimalFormat.format(mixedBalance.value?.toBigDecimal() ?: BigDecimal.ZERO)

    // DashPay
    private val isPlatformAvailable = MutableStateFlow(false)


    val isAbleToCreateIdentityLiveData = MediatorLiveData<Boolean>().apply {
        addSource(isPlatformAvailable.asLiveData()) {
            value = combineLatestData()
        }
        addSource(_isBlockchainSynced) {
            value = combineLatestData()
        }
        addSource(blockchainIdentity) {
            value = combineLatestData()
        }
        addSource(_totalBalance) {
            value = combineLatestData()
        }
    }

    val isAbleToCreateIdentity: Boolean
        get() = isAbleToCreateIdentityLiveData.value ?: false

    val showCreateUsernameEvent = SingleLiveEvent<Unit>()
    val sendContactRequestState = SendContactRequestOperation.allOperationsStatus(walletApplication)
    val seriousErrorLiveData = SeriousErrorLiveData(platformRepo)
    var processingSeriousError = false

    val notificationCountData = NotificationCountLiveData(platformRepo, platformSyncService, dashPayConfig, viewModelScope)
    val notificationCount: Int
        get() = notificationCountData.value ?: 0

    private var contactRequestTimer: AnalyticsTimer? = null

    // end DashPay

    init {
        transactionsDirection = savedStateHandle[DIRECTION_KEY] ?: TxFilterType.ALL

        _transactionsDirection
            .flatMapLatest { direction ->
                val filter = TxDirectionFilter(direction, walletData.wallet!!)
                refreshTransactions(filter)
                walletData.observeTransactions(true, filter)
                    .batchAndFilterUpdates(BATCHING_PERIOD)
                    .onEach { batch ->
                        refreshTransactionBatch(batch, filter, mapOf())
                    }
            }
            .launchIn(viewModelWorkerScope)

        metadataProvider.observePresentableMetadata()
            .onEach { metadata ->
                val oldMetadata = this.metadata
                this.metadata = metadata
                updateContactsAndMetadata(oldMetadata, metadata, mapOf())
            }
            .launchIn(viewModelWorkerScope)

        platformRepo.observeContacts(
            "",
            UsernameSortOrderBy.LAST_ACTIVITY,
            false
        ).distinctUntilChanged()
        .onEach { contacts ->
            this.minContactCreatedDate = contacts.minOfOrNull { it.dashPayProfile.createdAt }?.let {
               Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            } ?: LocalDate.now()
            val contactsByIdentity = contacts.associate { it.dashPayProfile.userId to it.dashPayProfile }
            this.contacts = contactsByIdentity
            refreshContactsForAllTransactions()
        }.launchIn(viewModelWorkerScope)

        walletData.observeWalletReset()
            .onEach {
                txByHash = mapOf()
                _transactions.value = mapOf()
                walletData.wallet?.let { wallet ->
                    coinJoinWrapperFactory = CoinJoinTxWrapperFactory(walletData.networkParameters, wallet as WalletEx)
                    crowdNodeWrapperFactory = FullCrowdNodeSignUpTxSetFactory(walletData.networkParameters, wallet)
                }
            }
            .launchIn(viewModelScope)

        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach { state ->
                updateSyncStatus(state)
                updatePercentage(state)
                chainLockBlockHeight = state.chainlockHeight
            }
            .launchIn(viewModelWorkerScope)

        // we need the total wallet balance for mixing progress,
        walletData.observeTotalBalance()
            .onEach {
                _totalBalance.value = it
            }
            .launchIn(viewModelScope)

        walletData.observeMixedBalance()
            .onEach {
                _mixedBalance.value = it
            }
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

        walletUIConfig
            .observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeStaleRates(code)
            }
            .onEach(_rateStale::emit)
            .launchIn(viewModelScope)

        // DashPay
        startContactRequestTimer()

        dashPayConfig.observe(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME)
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { lastSeenNotification ->
                startContactRequestTimer()
                forceUpdateNotificationCount()

                if (lastSeenNotification != DashPayConfig.DISABLE_NOTIFICATIONS) {
                    userAlertDao.observe(lastSeenNotification)
                        .filterNotNull()
                        .distinctUntilChanged()
                        .onEach { forceUpdateNotificationCount() }
                }
            }
            .launchIn(viewModelScope)

        blockchainStateProvider.observeSyncStage()
            .distinctUntilChanged()
            .onEach { syncStage ->
                if (syncStage == PeerGroup.SyncStage.PREBLOCKS || syncStage == PeerGroup.SyncStage.BLOCKS && !isPlatformAvailable.value) {
                    isPlatformAvailable.value = if (Constants.SUPPORTS_PLATFORM) {
                        platformService.isPlatformAvailable()
                    } else {
                        false
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    fun logError(ex: Exception, details: String) {
        analytics.logError(ex, details)
    }

    fun triggerHideBalance() {
        _temporaryHideBalance.value = !hideBalance.value

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
            analytics.logEvent(AnalyticsConstants.DashSpend.FILTER_GIFT_CARD, mapOf())
        }
    }

    fun processDirectTransaction(tx: Transaction) {
        walletData.processDirectTransaction(tx)
    }

    suspend fun getCoinJoinMode(): CoinJoinMode {
        return coinJoinConfig.getMode()
    }

    suspend fun getDeviceTimeSkew(force: Boolean): Pair<Boolean, Long> {
        return try {
            val timeSkew = getTimeSkew(force)
            val maxAllowedTimeSkew: Long = if (coinJoinConfig.getMode() == CoinJoinMode.NONE) {
                TIME_SKEW_TOLERANCE
            } else {
                if (timeSkew > 0) MAX_ALLOWED_AHEAD_TIMESKEW * 3 else MAX_ALLOWED_BEHIND_TIMESKEW * 2
            }
            coinJoinService.updateTimeSkew(timeSkew)
            log.info("timeskew: {} ms", timeSkew)
            return Pair(abs(timeSkew) > maxAllowedTimeSkew, timeSkew)
        } catch (_: Exception) {
            // Ignore errors
            Pair(false, 0)
        }
    }

    fun detectUserCountry() = viewModelScope.launch {
        if (walletUIConfig.get(WalletUIConfig.EXCHANGE_CURRENCY_DETECTED) == true) {
            return@launch
        }

        val selectedCurrencyCode = walletUIConfig.get(WalletUIConfig.SELECTED_CURRENCY)
        val country = deviceInfo.getSimOrNetworkCountry()

        if (country.isNotEmpty()) {
            updateCurrencyExchange(country.uppercase(Locale.getDefault()))
        } else if (selectedCurrencyCode == null) {
            setDefaultCurrency()
        }
    }

    fun setExchangeCurrencyCodeDetected(currencyCode: String?) {
        viewModelScope.launch {
            currencyCode?.let { walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, it) }
            walletUIConfig.set(WalletUIConfig.EXCHANGE_CURRENCY_DETECTED, true)
        }
    }

    /** refresh all transactions */
    private fun refreshTransactions(filter: TxDirectionFilter) {
        walletData.wallet?.let { wallet ->
            val watch = Stopwatch.createStarted()
            val contactsToUpdate = mutableListOf<Transaction>()
            coinJoinWrapperFactory = CoinJoinTxWrapperFactory(walletData.networkParameters, wallet as WalletEx)
            crowdNodeWrapperFactory = FullCrowdNodeSignUpTxSetFactory(walletData.networkParameters, wallet)
            val allTransactionWrapped = walletData.wrapAllTransactions(
                crowdNodeWrapperFactory,
                coinJoinWrapperFactory
            ).filter { it.passesFilter(filter, metadata) }
                .sortedByDescending { it.groupDate }

            log.info("wrapAllTransactions: {} ms", watch.elapsed(TimeUnit.MILLISECONDS))

            val txByHash = mutableMapOf<String, TransactionRowView>()
            val allTransactionViews = allTransactionWrapped
                .map {
                    val tx = it.transactions.values.first()
                    val dateKey = tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

                    if (it.transactions.count() == 1 && dateKey >= minContactCreatedDate) {
                        contactsToUpdate.add(tx)
                    }

                    val rowView = TransactionRowView.fromTransactionWrapper(
                        it,
                        walletData.transactionBag,
                        Constants.CONTEXT,
                        null,
                        metadata[tx.txId],
                        chainLockBlockHeight
                    )
                    txByHash[rowView.id] = rowView
                    rowView
                }
                .groupBy {
                    Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                .mapValues { (_, transactions) ->
                    transactions.sortedByDescending { it.time }
                }


            transactionsLoaded = true
            _transactions.value = allTransactionViews
            this@MainViewModel.txByHash = txByHash

            getContactsAndMetadataForTransactions(contactsToUpdate)
        }
    }

    /**
     * this will either add a single transaction or update the transaction on the current view
     */
    private fun refreshTransactionBatch(
        transactions: List<Transaction>,
        filter: TxDirectionFilter,
        contacts: Map<Sha256Hash, DashPayProfile>
    ) {
        Context.propagate(Constants.CONTEXT)
        val items = _transactions.value.toMutableMap()
        val txByHash = this.txByHash.toMutableMap()
        val contactsToUpdate = mutableListOf<Transaction>()

        for (i in transactions.indices) {
            val tx = transactions[i]

            if (!filter.matches(tx)) {
                continue
            }

            var itemId = tx.txId.toString()
            var included = false
            var wrapper: TransactionWrapper? = null

            if (!txByHash.containsKey(itemId)) {
                // If tx isn't in txByHash by itself, we need to try to wrap it
                this.coinJoinWrapperFactory.tryInclude(tx).also {
                    included = it.first
                    wrapper = it.second
                }

                if (included && wrapper != null) {
                    itemId = wrapper.id
                } else {
                    this.crowdNodeWrapperFactory.tryInclude(tx).also {
                        included = it.first
                        wrapper = it.second
                    }

                    if (included && wrapper != null) {
                        itemId = wrapper.id
                    }
                }
            }

            // is the item currently in our list
            val rowView = txByHash[itemId]

            val transactionRow = if (!included || wrapper == null) {
                TransactionRowView.fromTransaction(
                    tx,
                    walletData.transactionBag,
                    Constants.CONTEXT,
                    metadata[tx.txId],
                    contacts[tx.txId] ?: rowView?.contact,
                    TxResourceMapper(),
                    chainLockBlockHeight
                )
            } else {
                TransactionRowView.fromTransactionWrapper(
                    wrapper,
                    walletData.transactionBag,
                    Constants.CONTEXT,
                    null,
                    metadata[tx.txId],
                    chainLockBlockHeight
                )
            }
            txByHash[transactionRow.id] = transactionRow
            val dateKey = tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

            if (rowView != null) {
                // update the current item by replacing the current item
                items[dateKey]?.toMutableList()?.let { list ->
                    val itemIndex = list.indexOfFirst { it.id == rowView.id }
                    if (itemIndex == -1) {
                        log.info("cannot find {} in list of {} items", rowView.id, list.size)
                    } else {
                        list[itemIndex] = transactionRow
                    }
                    items[dateKey] = list
                }
            } else {
                // add the item to the correct group
                if (items.containsKey(dateKey)) {
                    items[dateKey]?.toMutableList()?.let { list ->
                        val insertIndex = list.indexOfFirst { it.time < transactionRow.time }

                        if (insertIndex >= 0) {
                            list.add(insertIndex, transactionRow)
                        } else {
                            list.add(transactionRow)
                        }

                        items[dateKey] = list
                    }
                } else {
                    items[dateKey] = listOf(transactionRow)
                }
            }

            if (dateKey >= minContactCreatedDate) {
                contactsToUpdate.add(tx)
            }
        }

        _transactions.value = items
        this@MainViewModel.txByHash = txByHash
        getContactsAndMetadataForTransactions(contactsToUpdate)
    }

    private fun refreshContactsForAllTransactions() {
        val transactions = walletData.getTransactions()
        val contactsToUpdate = mutableListOf<Transaction>()

        for (tx in transactions) {
            val dateKey = tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

            if (dateKey >= minContactCreatedDate) {
                contactsToUpdate.add(tx)
            }
        }

        getContactsAndMetadataForTransactions(contactsToUpdate)
    }

    private fun getContactsAndMetadataForTransactions(txs: List<Transaction>) {
        if (txs.isEmpty()) {
            return
        }

        viewModelWorkerScope.launch {
            val contactsMap = if (this@MainViewModel.contacts.isNotEmpty()) {
                txs.filterNot { it.isEntirelySelf(walletData.transactionBag) }
                    .mapNotNull { tx ->
                        platformRepo.blockchainIdentity.getContactForTransaction(tx)?.let { contactId ->
                            contacts[contactId]?.let { contact ->
                                tx.txId to contact
                            }
                        }
                    }.toMap()
            } else {
                mapOf()
            }

            updateContactsAndMetadata(mapOf(), metadata, contactsMap)
        }
    }

    private fun updateContactsAndMetadata(
        oldMetadata: Map<Sha256Hash, PresentableTxMetadata>,
        metadata: Map<Sha256Hash, PresentableTxMetadata>,
        contacts: Map<Sha256Hash, DashPayProfile>
    ) {
        val items = _transactions.value.toMutableMap()
        if (items.isEmpty()) return

        val txByHash = this.txByHash.toMutableMap()
        // Process both old and new metadata in case if some metadata was cleared
        val allTxIds = (oldMetadata.keys + metadata.keys + contacts.keys).toSet()

        for (txId in allTxIds) {
            val rowView = txByHash[txId.toString()]

            rowView?.let {
                walletData.getTransaction(txId)?.let { tx ->
                    // Use new metadata if exists, otherwise create empty metadata
                    val txMetadata = metadata[txId] ?: PresentableTxMetadata(txId)
                    val updatedRowView = TransactionRowView.fromTransaction(
                        tx,
                        walletData.transactionBag,
                        Constants.CONTEXT,
                        txMetadata,
                        contacts[txId] ?: rowView.contact,
                        TxResourceMapper(),
                        chainLockBlockHeight
                    )
                    txByHash[txId.toString()] = updatedRowView
                    val dateKey = tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                    items[dateKey]?.toMutableList()?.let { list ->
                        val itemIndex = list.indexOfFirst { it.id == rowView.id }
                        list[itemIndex] = updatedRowView
                        items[dateKey] = list
                    }
                }
            }
        }

        _transactions.value = items
        this@MainViewModel.txByHash = txByHash
    }

    private fun updateSyncStatus(state: BlockchainState) {
        if (_isBlockchainSynced.value != state.isSynced()) {
            _isBlockchainSynced.postValue(state.isSynced())
        }

        _isBlockchainSyncFailed.postValue(state.syncFailed())
        _isNetworkUnavailable.postValue(state.impediments.contains(BlockchainState.Impediment.NETWORK))
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
            transactions.values.any { tx -> filter.matches(tx) }
    }

    private fun TransactionWrapper.isGiftCard(metadata: Map<Sha256Hash, PresentableTxMetadata>): Boolean {
        return metadata[transactions.values.first().txId]?.service == ServiceName.CTXSpend
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
        return LocaleList.getDefault()[0].country
    }

    // DashPay

    fun reportContactRequestTime() {
        contactRequestTimer?.logTiming()
        contactRequestTimer = null
    }

    private fun forceUpdateNotificationCount() {
        notificationCountData.onContactsUpdated()
        viewModelScope.launch(Dispatchers.IO) {
            platformSyncService.updateContactRequests()
        }
    }

    suspend fun dismissUsernameCreatedCardIfDone(): Boolean {
        val data = blockchainIdentityDataDao.loadBase()

        if (data.creationState == BlockchainIdentityData.CreationState.DONE) {
            platformRepo.doneAndDismiss()
            return true
        }

        return false
    }

    fun dismissUsernameCreatedCard() {
        viewModelScope.launch {
            platformRepo.doneAndDismiss()
        }
    }

    fun joinDashPay() {
        showCreateUsernameEvent.call()
    }

    fun startBlockchainService() {
        walletApplication.startBlockchainService(true)
    }

    suspend fun getProfile(profileId: String): DashPayProfile? {
        return platformRepo.loadProfileByUserId(profileId)
    }

    suspend fun getRequestedUsername(): String =
        blockchainIdentityDataDao.get(BlockchainIdentityConfig.USERNAME) ?: ""
    suspend fun getInviteHistory() = invitationsDao.loadAll()

    private fun combineLatestData(): Boolean {
        return if (!Constants.SUPPORTS_PLATFORM) {
            log.info("platform is not supported")
            false
        } else {
            val isPlatformAvailable = isPlatformAvailable.value
            val isSynced = _isBlockchainSynced.value ?: false
            val noIdentityCreatedOrInProgress =
                (blockchainIdentity.value == null) || blockchainIdentity.value!!.creationState == BlockchainIdentityData.CreationState.NONE
            log.info(
                "platform available: {}; isSynced: {}: no identity creation is progress: {}",
                isPlatformAvailable,
                isSynced,
                noIdentityCreatedOrInProgress
            )
            return isSynced && isPlatformAvailable && noIdentityCreatedOrInProgress
        }
    }

    private fun startContactRequestTimer() {
        contactRequestTimer = AnalyticsTimer(
            analytics,
            log,
            AnalyticsConstants.Process.PROCESS_CONTACT_REQUEST_RECEIVE
        )
    }

    fun addCoinJoinToWallet() {
        val encryptionKey = platformRepo.getWalletEncryptionKey() ?: throw IllegalStateException("cannot obtain wallet encryption key")
        (walletApplication.wallet as WalletEx).initializeCoinJoin(encryptionKey, 0)
    }

    fun observeMostRecentTransaction() = walletData.observeMostRecentTransaction().distinctUntilChanged()
}
