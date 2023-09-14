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

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.WalletUIConfig
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.database.dao.BlockchainIdentityDataDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.SeriousErrorLiveData
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.transactions.TxDirectionFilter
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.NotificationCountLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import de.schildbach.wallet.ui.transactions.TransactionRowView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperComparator
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.collections.set

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    val analytics: AnalyticsService,
    private val clipboardManager: ClipboardManager,
    private val config: Configuration,
    private val walletUIConfig: WalletUIConfig,
    exchangeRatesProvider: ExchangeRatesProvider,
    val walletData: WalletDataProvider,
    private val walletApplication: WalletApplication,
    val platformRepo: PlatformRepo,
    val platformSyncService: PlatformSyncService,
    blockchainIdentityDataDao: BlockchainIdentityDataDao,
    private val savedStateHandle: SavedStateHandle,
    private val metadataProvider: TransactionMetadataProvider,
    private val blockchainStateProvider: BlockchainStateProvider,
    val biometricHelper: BiometricHelper,
    private val invitationsDao: InvitationsDao,
    userAlertDao: UserAlertDao,
    dashPayProfileDao: DashPayProfileDao,
    private val dashPayConfig: DashPayConfig
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {
    companion object {
        private const val THROTTLE_DURATION = 500L
        private const val DIRECTION_KEY = "tx_direction"
        private val log = LoggerFactory.getLogger(MainViewModel::class.java)
    }

    private val workerJob = SupervisorJob()
    @VisibleForTesting
    val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)
    private val listener: SharedPreferences.OnSharedPreferenceChangeListener
    private val currencyCode = MutableStateFlow(config.exchangeCurrencyCode)

    val isPassphraseVerified: Boolean
        get() = !config.remindBackupSeed
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
    val hideBalance = walletUIConfig.observePreference(WalletUIConfig.AUTO_HIDE_BALANCE)
        .combine(_temporaryHideBalance) { autoHide, temporaryHide ->
            temporaryHide ?: autoHide ?: false
        }
        .asLiveData()

    val showTapToHideHint = walletUIConfig.observePreference(WalletUIConfig.SHOW_TAP_TO_HIDE_HINT).asLiveData()

    private val _isNetworkUnavailable = MutableLiveData<Boolean>()
    val isNetworkUnavailable: LiveData<Boolean>
        get() = _isNetworkUnavailable

    private val _stakingAPY = MutableLiveData<Double>()
    val stakingAPY: LiveData<Double>
        get() = _stakingAPY

    // DashPay

    private val isPlatformAvailableData = liveData(Dispatchers.IO) {
        val status = if (Constants.SUPPORTS_PLATFORM) {
            platformRepo.isPlatformAvailable()
        } else {
            Resource.success(false)
        }
        if (status.status == Status.SUCCESS && status.data != null) {
            emit(status.data)
        } else {
            emit(false)
        }
    }

    val isAbleToCreateIdentityLiveData = MediatorLiveData<Boolean>().apply {
        addSource(isPlatformAvailableData) {
            value = combineLatestData()
        }
        addSource(_isBlockchainSynced) {
            value = combineLatestData()
        }
        addSource(blockchainIdentity) {
            value = combineLatestData()
        }
        addSource(_balance) {
            value = combineLatestData()
        }
    }

    val isAbleToCreateIdentity: Boolean
        get() = isAbleToCreateIdentityLiveData.value ?: false

    val showCreateUsernameEvent = SingleLiveEvent<Unit>()
    val sendContactRequestState = SendContactRequestOperation.allOperationsStatus(walletApplication)
    val seriousErrorLiveData = SeriousErrorLiveData(platformRepo)
    var processingSeriousError = false

    val notificationCountData =
        NotificationCountLiveData(walletApplication, platformRepo, platformSyncService, dashPayConfig, viewModelScope)
    val notificationCount: Int
        get() = notificationCountData.value ?: 0

    private var contactRequestTimer: AnalyticsTimer? = null

    // end DashPay

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

        currencyCode.filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeExchangeRate(code)
                    .filterNotNull()
            }
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Configuration.PREFS_KEY_EXCHANGE_CURRENCY -> {
                    currencyCode.value = config.exchangeCurrencyCode
                }
            }
        }
        config.registerOnSharedPreferenceChangeListener(listener)

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
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    fun logError(ex: Exception, details: String) {
        analytics.logError(ex, details)
    }

    fun getClipboardInput(): String {
        var input: String? = null

        if (clipboardManager.hasPrimaryClip()) {
            val clip = clipboardManager.primaryClip ?: return ""
            val clipDescription = clip.description

            if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) {
                input = clip.getItemAt(0).uri?.toString()
            } else if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            ) {
                input = clip.getItemAt(0).text?.toString()
            }
        }

        return input ?: ""
    }

    fun triggerHideBalance() {
        val isHiding = hideBalance.value ?: false
        _temporaryHideBalance.value = !isHiding

        if (_temporaryHideBalance.value == true) {
            logEvent(AnalyticsConstants.Home.HIDE_BALANCE)
        } else {
            logEvent(AnalyticsConstants.Home.SHOW_BALANCE)
        }

        viewModelScope.launch { walletUIConfig.setPreference(WalletUIConfig.SHOW_TAP_TO_HIDE_HINT, false) }
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

    override fun onCleared() {
        super.onCleared()
        config.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private suspend fun refreshTransactions(filter: TxDirectionFilter, metadata: Map<Sha256Hash, PresentableTxMetadata>) {
        walletData.wallet?.let { wallet ->
            val contactsByIdentity: HashMap<String, DashPayProfile> = hashMapOf()

            if (platformRepo.hasIdentity) {
                val contacts = platformRepo.searchContacts(
                    "",
                    UsernameSortOrderBy.LAST_ACTIVITY,
                    false
                )
                contacts.data?.forEach { result ->
                    contactsByIdentity[result.dashPayProfile.userId] = result.dashPayProfile
                }
            }

            val transactionViews = walletData.wrapAllTransactions(
                FullCrowdNodeSignUpTxSet(walletData.networkParameters, wallet)
            ).filter { it.passesFilter(filter, metadata) }
                .sortedWith(TransactionWrapperComparator())
                .map {
                    var contact: DashPayProfile? = null
                    val tx = it.transactions.first()
                    val isInternal = tx.isEntirelySelf(wallet)

                    if (it.transactions.size == 1) {
                        if (!isInternal && platformRepo.hasIdentity) {
                            val contactId = platformRepo.blockchainIdentity.getContactForTransaction(tx)

                            if (contactId != null) {
                                contact = contactsByIdentity[contactId]
                            }
                        }
                    }

                    TransactionRowView.fromTransactionWrapper(
                        it,
                        walletData.transactionBag,
                        Constants.CONTEXT,
                        contact,
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
            transactions.any { tx -> filter.matches(tx) }
    }
    private fun TransactionWrapper.isGiftCard(metadata: Map<Sha256Hash, PresentableTxMetadata>): Boolean {
        return metadata[transactions.first().txId]?.service == ServiceName.DashDirect
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

        if (data?.creationState == BlockchainIdentityData.CreationState.DONE) {
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
        dashPayConfig.get(DashPayConfig.REQUESTED_USERNAME) ?: ""
    suspend fun getInviteHistory() = invitationsDao.loadAll()

    private fun combineLatestData(): Boolean {
        //TODO REVERT
        val isPlatformAvailable = true // isPlatformAvailableData.value ?: false
        val isSynced = true // _isBlockchainSynced.value ?: false
        val noIdentityCreatedOrInProgress = (blockchainIdentity.value == null) || blockchainIdentity.value!!.creationState == BlockchainIdentityData.CreationState.NONE
        val canAffordIdentityCreation = true// walletData.canAffordIdentityCreation()
        return isSynced && isPlatformAvailable && noIdentityCreatedOrInProgress && canAffordIdentityCreation
    }

    private fun startContactRequestTimer() {
        contactRequestTimer = AnalyticsTimer(
            analytics,
            log,
            AnalyticsConstants.Process.PROCESS_CONTACT_REQUEST_RECEIVE
        )
    }
}
