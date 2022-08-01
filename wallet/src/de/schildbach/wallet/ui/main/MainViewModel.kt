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
import androidx.core.os.bundleOf
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.SeriousErrorLiveData
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.SingleLiveEvent
import de.schildbach.wallet.ui.dashpay.*
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.*
import de.schildbach.wallet.transactions.TxDirection
import de.schildbach.wallet.transactions.TxDirectionFilter
import de.schildbach.wallet.ui.transactions.TransactionRowView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.slf4j.LoggerFactory
import org.dash.wallet.common.transactions.TransactionFilter
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.TransactionWrapperComparator
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import java.util.HashMap
import javax.inject.Inject

@HiltViewModel
@FlowPreview
@ExperimentalCoroutinesApi
class MainViewModel @Inject constructor(
    val analytics: AnalyticsService,
    private val clipboardManager: ClipboardManager,
    private val config: Configuration,
    blockchainStateDao: BlockchainStateDao,
    exchangeRatesProvider: ExchangeRatesProvider,
    val walletData: WalletDataProvider,
    walletApplication: WalletApplication,
    appDatabase: AppDatabase,
    val platformRepo: PlatformRepo,
    private val savedStateHandle: SavedStateHandle
) : BaseProfileViewModel(walletApplication, appDatabase) {
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

    val balanceDashFormat: MonetaryFormat = config.format.noCode()
    val isPassphraseVerified: Boolean
        get() = !config.remindBackupSeed

    private val _transactions = MutableLiveData<List<TransactionRowView>>()
    val transactions: LiveData<List<TransactionRowView>>
        get() = _transactions

    private val _transactionsDirection = MutableStateFlow(TxDirection.ALL)
    var transactionsDirection: TxDirection
        get() = _transactionsDirection.value
        set(value) {
            _transactionsDirection.value = value
            savedStateHandle.set(DIRECTION_KEY, value)
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

    private val _hideBalance = MutableLiveData<Boolean>()
    val hideBalance: LiveData<Boolean>
        get() = _hideBalance

    private val _isNetworkUnavailable = MutableLiveData<Boolean>()
    val isNetworkUnavailable: LiveData<Boolean>
        get() = _isNetworkUnavailable

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

    val inviteHistory = appDatabase.invitationsDaoAsync().loadAll()
    val canAffordIdentityCreationLiveData = CanAffordIdentityCreationLiveData(walletApplication)

    val isAbleToCreateIdentityLiveData = MediatorLiveData<Boolean>().apply {
        addSource(isPlatformAvailableData) {
            value = combineLatestData()
        }
        addSource(_isBlockchainSynced) {
            value = combineLatestData()
        }
        addSource(blockchainIdentityData) {
            value = combineLatestData()
        }
        addSource(canAffordIdentityCreationLiveData) {
            value = combineLatestData()
        }
    }

    val isAbleToCreateIdentity: Boolean
        get() = isAbleToCreateIdentityLiveData.value ?: false

    val goBackAndStartActivityEvent = SingleLiveEvent<Class<*>>()
    val showCreateUsernameEvent = SingleLiveEvent<Unit>()
    val sendContactRequestState = SendContactRequestOperation.allOperationsStatus(walletApplication)
    val seriousErrorLiveData = SeriousErrorLiveData(platformRepo)
    var processingSeriousError = false

    val notificationCountData =
        NotificationCountLiveData(walletApplication, platformRepo, viewModelScope)
    val notificationCount: Int
        get() = notificationCountData.value ?: 0

    private var contactRequestTimer: AnalyticsTimer? = null

    // end DashPay

    init {
        _hideBalance.value = config.hideBalance
        transactionsDirection = savedStateHandle.get(DIRECTION_KEY) ?: TxDirection.ALL

        _transactionsDirection
            .flatMapLatest { direction ->
                val filter = TxDirectionFilter(direction, walletData.wallet!!)
                refreshTransactions(filter)
                walletData.observeTransactions(filter)
                    .debounce(THROTTLE_DURATION)
                    .onEach { refreshTransactions(filter) }
            }
            .launchIn(viewModelWorkerScope)

        blockchainStateDao.observeState()
            .filterNotNull()
            .onEach { state ->
                updateSyncStatus(state)
                updatePercentage(state)
            }
            .launchIn(viewModelWorkerScope)

        walletData.observeBalance()
            .onEach(_balance::postValue)
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
                Configuration.PREFS_LAST_SEEN_NOTIFICATION_TIME -> {
                    startContactRequestTimer()
                    forceUpdateNotificationCount()
                }
            }
        }
        config.registerOnSharedPreferenceChangeListener(listener)


        // DashPay
        startContactRequestTimer()

        // don't query alerts if notifications are disabled
        if (config.areNotificationsDisabled()) {
            val lastSeenNotification = config.lastSeenNotificationTime
            appDatabase.userAlertDaoAsync()
                .load(lastSeenNotification).observeForever { userAlert: UserAlert? ->
                    if (userAlert != null) {
                        forceUpdateNotificationCount()
                    }
                }
        }
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, bundleOf())
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
            } else if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                || clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            ) {
                input = clip.getItemAt(0).text?.toString()
            }
        }

        return input ?: ""
    }

    fun triggerHideBalance() {
        val pastValue = _hideBalance.value ?: config.hideBalance
        _hideBalance.value = !pastValue

        if (_hideBalance.value == true) {
            logEvent(AnalyticsConstants.Home.HIDE_BALANCE)
        } else {
            logEvent(AnalyticsConstants.Home.SHOW_BALANCE)
        }
    }

    fun logDirectionChangedEvent(direction: TxDirection) {
        val directionParameter = when (direction) {
            TxDirection.ALL -> "all_transactions"
            TxDirection.SENT -> "sent_transactions"
            TxDirection.RECEIVED -> "received_transactions"
        }
        analytics.logEvent(AnalyticsConstants.Home.TRANSACTION_FILTER, bundleOf(
            "filter_value" to directionParameter
        ))
    }

    fun processDirectTransaction(tx: Transaction) {
        walletData.processDirectTransaction(tx)
    }

    override fun onCleared() {
        super.onCleared()
        config.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // DashPay

    fun reportContactRequestTime() {
        contactRequestTimer?.logTiming()
        contactRequestTimer = null
    }

    private fun forceUpdateNotificationCount() {
        notificationCountData.onContactsUpdated()
        viewModelScope.launch(Dispatchers.IO) {
            platformRepo.updateContactRequests()
        }
    }

    fun dismissUsernameCreatedCard() {
        viewModelScope.launch {
            platformRepo.doneAndDismiss()
        }
    }

    fun joinDashPay() {
        showCreateUsernameEvent.call(Unit)
    }

    fun startBlockchainService() {
        walletApplication.startBlockchainService(true)
    }

    private fun combineLatestData(): Boolean {
        val isPlatformAvailable = isPlatformAvailableData.value ?: false
        val isSynced = _isBlockchainSynced.value ?: false
        val noIdentityCreatedOrInProgress = (blockchainIdentityData.value == null) || blockchainIdentityData.value!!.creationState == BlockchainIdentityData.CreationState.NONE
        val canAffordIdentityCreation = canAffordIdentityCreationLiveData.value ?: false
        return isSynced && isPlatformAvailable && noIdentityCreatedOrInProgress && canAffordIdentityCreation
    }

    private fun startContactRequestTimer() {
        contactRequestTimer = AnalyticsTimer(
            analytics,
            log,
            AnalyticsConstants.Process.PROCESS_CONTACT_REQUEST_RECEIVE
        )
    }

    private suspend fun refreshTransactions(filter: TransactionFilter) {
        walletData.wallet?.let { wallet ->
            val userIdentity = platformRepo.getBlockchainIdentity()
            val contactsByIdentity: HashMap<String, DashPayProfile> = hashMapOf()

            if (userIdentity != null) {
                val contacts = platformRepo.searchContacts("",
                    UsernameSortOrderBy.LAST_ACTIVITY, false)
                contacts.data?.forEach { result ->
                    contactsByIdentity[result.dashPayProfile.userId] = result.dashPayProfile
                }
            }

            val transactionViews = walletData.wrapAllTransactions(
                FullCrowdNodeSignUpTxSet(walletData.networkParameters, wallet)
            ).filter { it.transactions.any { tx -> filter.matches(tx) } }
             .sortedWith(TransactionWrapperComparator())
             .map {
                 var contact: DashPayProfile? = null
                 val tx = it.transactions.first()
                 val isInternal = TransactionUtils.isEntirelySelf(tx, wallet)

                 if (it.transactions.size == 1 && !isInternal) {
                     val contactId = userIdentity?.getContactForTransaction(tx)

                     if (contactId != null) {
                         contact = contactsByIdentity[contactId]
                     }
                 }

                 TransactionRowView.fromTransactionWrapper(
                     it, walletData.transactionBag,
                     Constants.CONTEXT,
                     contact
                 )
             }
            _transactions.postValue(transactionViews)
        }
    }

    private fun updateSyncStatus(state: BlockchainState) {
        if (_isBlockchainSyncFailed.value != state.isSynced()) {
            _isBlockchainSynced.postValue(state.isSynced())

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
            //This is to prevent showing 100% when using the Rescan blockchain function.
            //The first few broadcasted blockchainStates are with percentage sync at 100%
            percentage = 0
        }
        _blockchainSyncPercentage.postValue(percentage)
    }
}