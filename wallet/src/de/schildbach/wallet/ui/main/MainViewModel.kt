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
import androidx.core.os.bundleOf
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.data.BlockchainStateDao
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.SeriousErrorLiveData
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.SingleLiveEvent
import de.schildbach.wallet.ui.SingleLiveEventExt
import de.schildbach.wallet.ui.dashpay.*
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import javax.inject.Inject

@HiltViewModel
@ExperimentalCoroutinesApi
class MainViewModel @Inject constructor(
    val analytics: AnalyticsService,
    private val clipboardManager: ClipboardManager,
    private val config: Configuration,
    blockchainStateDao: BlockchainStateDao,
    exchangeRatesProvider: ExchangeRatesProvider,
    private val walletData: WalletDataProvider,
    walletApplication: WalletApplication,
    appDatabase: AppDatabase,
    val platformRepo: PlatformRepo
) : BaseProfileViewModel(walletApplication, appDatabase) {
    companion object {
        private val log = LoggerFactory.getLogger(MainViewModel::class.java)
    }

    private val listener: SharedPreferences.OnSharedPreferenceChangeListener
    private val currencyCode = MutableStateFlow(config.exchangeCurrencyCode)

    val balanceDashFormat: MonetaryFormat = config.format.noCode()
    val onTransactionsUpdated = SingleLiveEvent<Unit>()
    val isPassphraseVerified: Boolean
        get() = !config.remindBackupSeed

    private val _isBlockchainSynced = MutableLiveData<Boolean>()
    val isBlockchainSynced: LiveData<Boolean>
        get() = _isBlockchainSynced

    private val _isBlockchainSyncFailed = MutableLiveData<Boolean>()
    val isBlockchainSyncFailed: LiveData<Boolean>
        get() = _isBlockchainSyncFailed

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
    val showCreateUsernameEvent = SingleLiveEventExt<Unit>()
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

        blockchainStateDao.observeState()
            .filterNotNull()
            .onEach {
                _isBlockchainSynced.postValue(it.isSynced())
                _isBlockchainSyncFailed.postValue(it.syncFailed())
                _isNetworkUnavailable.postValue(it.impediments.contains(BlockchainState.Impediment.NETWORK))
            }
            .launchIn(viewModelScope)

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

    override fun onCleared() {
        super.onCleared()
        config.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun processDirectTransaction(tx: Transaction) {
        walletData.processDirectTransaction(tx)
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
}