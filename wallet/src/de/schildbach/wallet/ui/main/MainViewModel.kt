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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.SeriousErrorLiveData
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.service.DeviceInfoProvider
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.TxDisplayCacheService
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.platform.sdk.CoinJoinFundsMigrationService
import de.schildbach.wallet.service.platform.sdk.CutoverCoordinator
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.shadowSyncPercent
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet.ui.dashpay.BaseContactsViewModel
import de.schildbach.wallet.ui.dashpay.NotificationCountLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import de.schildbach.wallet.util.getTimeSkew
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.money.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.CurrencyInfo
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.SyncStage
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.RateRetrievalState
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsTimer
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.slf4j.LoggerFactory
import java.util.Currency
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    val analytics: AnalyticsService,
    private val config: Configuration,
    private val walletUIConfig: WalletUIConfig,
    exchangeRatesProvider: ExchangeRatesProvider,
    val walletData: WalletData,
    private val walletApplication: WalletApplication,
    private val identityRepository: IdentityRepository,
    val platformRepo: PlatformRepo,
    private val platformService: PlatformService,
    private val platformSyncService: PlatformSyncService,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    private val savedStateHandle: SavedStateHandle,
    blockchainStateProvider: BlockchainStateProvider,
    val biometricHelper: BiometricHelper,
    private val deviceInfo: DeviceInfoProvider,
    private val invitationsDao: InvitationsDao,
    private val usernameRequestDao: UsernameRequestDao,
    userAlertDao: UserAlertDao,
    dashPayProfileDao: DashPayProfileDao,
    private val dashPayConfig: DashPayConfig,
    dashPayContactRequestDao: DashPayContactRequestDao,
    private val txDisplayCacheService: TxDisplayCacheService,
    private val crowdNodeApi: CrowdNodeApi,
    private val coinJoinFundsMigrationService: CoinJoinFundsMigrationService,
    l1ShadowSyncService: L1ShadowSyncService,
    cutoverCoordinator: CutoverCoordinator
) : BaseContactsViewModel(blockchainIdentityDataDao, dashPayProfileDao, dashPayContactRequestDao) {
    var restoringBackup: Boolean = false

    /**
     * Phase 5d: has the cutover flipped so the SDK owns L1 this launch
     * (state CUT_OVER/SETTLED)? Resolved once, mirroring the per-launch
     * engine gate in BlockchainServiceImpl, so the home "Syncing N%" header
     * reads from whichever engine actually drives L1 this launch — the SDK
     * L1 scan post-cutover ([sdkSyncPercentage]/[sdkL1Synced]), dashj before
     * ([blockchainSyncPercentage]/[isBlockchainSynced]). false (dashj) for
     * every install until a deliberate cutover commit.
     */
    val sdkOwnsL1: StateFlow<Boolean> =
        flow { emit(runCatching { !cutoverCoordinator.dashjEngineMayStart() }.getOrDefault(false)) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** SDK L1 scan progress as a single 0..100 percent, for the header when [sdkOwnsL1]. */
    val sdkSyncPercentage: StateFlow<Int> =
        l1ShadowSyncService.progress
            .map { shadowSyncPercent(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * Whether the SDK L1 scan is caught up — hides the header when [sdkOwnsL1].
     * Uses [ShadowSyncProgress.scanCaughtUpToTip] (not the SDK's never-latching
     * `synced`/phase==SYNCED) so the header clears for a live shadow SPV that
     * has reached the tip within tolerance; SYNCED still counts.
     */
    val sdkL1Synced: StateFlow<Boolean> =
        l1ShadowSyncService.progress
            .map { it.synced || it.scanCaughtUpToTip }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val balanceDashFormat: MonetaryFormat = config.format.noCode().minDecimals(0)
    val fiatFormat: MonetaryFormat = Constants.LOCAL_FORMAT.minDecimals(0).optionalDecimals(0, 2)

    // Transaction list state delegated to TxDisplayCacheService
    val transactionsLoaded: StateFlow<Boolean> get() = txDisplayCacheService.transactionsLoaded
    val isBuildingCache: StateFlow<Boolean> get() = txDisplayCacheService.isBuildingCache
    val cachedRows: StateFlow<List<HistoryRowView>> get() = txDisplayCacheService.cachedRows
    val transactions: Flow<PagingData<HistoryRowView>> get() = txDisplayCacheService.transactions

    private val _transactionsDirection = MutableStateFlow(TxFilterType.ALL)
    var transactionsDirection: TxFilterType
        get() = _transactionsDirection.value
        set(value) {
            _transactionsDirection.value = value
            savedStateHandle[DIRECTION_KEY] = value
            txDisplayCacheService.setFilter(value)
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
    private var chainHeight: Int = walletData.wallet?.lastBlockSeenHeight ?: 0
    private var headersHeight: Int = walletData.wallet?.lastBlockSeenHeight ?: 0
    private val _syncStage = MutableStateFlow(SyncStage.OFFLINE)
    val syncStage: StateFlow<SyncStage>
        get() = _syncStage

    private val _exchangeRate = MutableLiveData<ExchangeRate>()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _rateStale = MutableStateFlow(RateRetrievalState.DEFAULT)
    val rateStale: Flow<RateRetrievalState>
        get() = _rateStale
    val currentStaleRateState
        get() = _rateStale.value
    var rateStaleDismissed = false

    private val _totalBalance = MutableLiveData<Coin>()
    val totalBalance: LiveData<Coin>
        get() = _totalBalance

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
    private val _remindMetadata = MutableStateFlow(false)
    val remindMetadata = _remindMetadata.asStateFlow()
    val showTapToHideHint = walletUIConfig.observe(WalletUIConfig.SHOW_TAP_TO_HIDE_HINT).asLiveData()

    private val _isNetworkUnavailable = MutableLiveData<Boolean>()
    val isNetworkUnavailable: LiveData<Boolean>
        get() = _isNetworkUnavailable

    val currencyChangeDetected = SingleLiveEvent<Pair<String, String>>()

    // DashPay
    private val isPlatformAvailable = MutableStateFlow(false)

    val isAbleToCreateIdentityLiveData = MediatorLiveData<Boolean>().apply {
        addSource(isPlatformAvailable.asLiveData()) {
            value = combineLatestData()
        }
//        addSource(_isBlockchainSynced) {
//            value = combineLatestData()
//        }
        addSource(blockchainIdentity) {
            value = combineLatestData()
        }
//        addSource(_totalBalance) {
//            value = combineLatestData()
//        }
    }

    val isAbleToCreateIdentity: StateFlow<Boolean> = combine(
        isPlatformAvailable,
        blockchainIdentity.asFlow()
    ) { isPlatformAvailable, identity ->
        combineLatestData()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val showCreateUsernameEvent = SingleLiveEvent<Unit>()

    // One-time-per-launch nudge to withdraw a remaining CrowdNode balance after sync.
    val showCrowdNodeWithdrawalReminder = SingleLiveEvent<Unit>()

    /**
     * Post-upgrade MIXED-FUNDS prompt: this wallet still holds coins on the
     * CoinJoin keychain that the app can no longer spend, and the user has
     * not yet chosen what to do with them. Fires at most once per launch and
     * only after the L1 view is current enough to trust the balance — see
     * [CoinJoinFundsMigrationService.shouldPrompt].
     */
    val showMixedFundsMigration = SingleLiveEvent<Unit>()
    val sendContactRequestState = SendContactRequestOperation.allOperationsStatus(walletApplication)
    val seriousErrorLiveData = SeriousErrorLiveData(platformRepo)
    var processingSeriousError = false

    val notificationCountData = NotificationCountLiveData(identityRepository, platformRepo, platformSyncService, dashPayConfig, viewModelScope)
    val notificationCount: Int
        get() = notificationCountData.value ?: 0

    private var contactRequestTimer: AnalyticsTimer? = null

    // end DashPay

    init {
        // Restore the saved filter and prime the service with it.
        val savedDirection: TxFilterType = savedStateHandle[DIRECTION_KEY] ?: TxFilterType.ALL
        _transactionsDirection.value = savedDirection
        txDisplayCacheService.setFilter(savedDirection)
        log.info("STARTUP MainViewModel init at {}", System.currentTimeMillis())

        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach { state ->
                updateSyncStatus(state)
                updatePercentage(state)
                headersHeight = state.mnlistHeight
                chainHeight = state.bestChainHeight
                if (!state.replaying) {
                    log.info("blockchain state update: {}; {}; {} -> {}", headersHeight, chainHeight, walletData.wallet?.lastBlockSeenHeight)
                }
            }
            .catch { e -> log.error("blockchain state flow error", e) }
            .launchIn(viewModelScope)

        // Once per launch, after sync, remind the user to withdraw any remaining CrowdNode balance.
        combine(
            blockchainStateProvider.observeState().filterNotNull(),
            crowdNodeApi.signUpStatus,
            crowdNodeApi.balance
        ) { state, signUpStatus, balance ->
            Triple(state.isSynced(), signUpStatus, balance.data)
        }
            .onEach { (isSynced, signUpStatus, balance) ->
                val hasAccount = signUpStatus != SignUpStatus.NotStarted
                val hasBalance = balance?.isPositive == true
                val alreadyShown: Boolean = savedStateHandle[CROWDNODE_REMINDER_SHOWN_KEY] ?: false

                if (isSynced && hasAccount && hasBalance && !alreadyShown) {
                    savedStateHandle[CROWDNODE_REMINDER_SHOWN_KEY] = true
                    showCrowdNodeWithdrawalReminder.postCall()
                }
            }
            .catch { e -> log.error("crowdnode withdrawal reminder flow error", e) }
            .launchIn(viewModelScope)

        // Post-upgrade MIXED-FUNDS prompt: at most once per launch, and only
        // once the L1 view is current enough that a zero/non-zero CoinJoin
        // balance means something (the service owns that predicate — it also
        // covers the "already synced, percentageSync restarted at 0" case
        // that a strict isSynced() gate would stall on). Never blocks
        // startup: this is a background collector, and a wallet with no
        // mixed funds never emits.
        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach {
                val alreadyShown: Boolean = savedStateHandle[MIXED_FUNDS_PROMPT_SHOWN_KEY] ?: false
                if (alreadyShown) return@onEach
                if (coinJoinFundsMigrationService.shouldPrompt()) {
                    savedStateHandle[MIXED_FUNDS_PROMPT_SHOWN_KEY] = true
                    showMixedFundsMigration.postCall()
                }
            }
            .catch { e -> log.error("mixed-funds migration prompt flow error", e) }
            .launchIn(viewModelScope)

        // Phase 5d: the displayed balance follows whichever engine owns L1
        // this launch. observeTotalBalance() is cutover-aware at the
        // WalletData facade (WalletApplication overlays the SDK's
        // live L1 balance via CutoverUiDataService once the cutover is
        // committed), so EVERY balance consumer switches consistently —
        // pre-cutover this is byte-identical to the dashj-only feed.
        walletData.observeTotalBalance()
            .onEach { _totalBalance.value = it }
            .catch { e -> log.error("total balance flow error", e) }
            .launchIn(viewModelScope)

        walletUIConfig
            .observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeExchangeRate(code)
                    .filterNotNull()
            }
            .onEach(_exchangeRate::postValue)
            .catch { e -> log.error("exchange rate flow error", e) }
            .launchIn(viewModelScope)

        walletUIConfig
            .observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRatesProvider.observeStaleRates(code)
            }
            .onEach(_rateStale::emit)
            .catch { e -> log.error("stale rates flow error", e) }
            .launchIn(viewModelScope)

        // DashPay
        startContactRequestTimer()

        dashPayConfig.observe(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME)
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { lastSeenNotification ->
                startContactRequestTimer()
                if (_isBlockchainSynced.value == true) {
                    forceUpdateNotificationCount()
                }
                if (lastSeenNotification != DashPayConfig.DISABLE_NOTIFICATIONS) {
                    userAlertDao.observe(lastSeenNotification)
                        .filterNotNull()
                        .distinctUntilChanged()
                        .onEach { forceUpdateNotificationCount() }
                        .launchIn(viewModelScope)
                }
            }
            .catch { e -> log.error("dashpay notification flow error", e) }
            .launchIn(viewModelScope)

        blockchainStateProvider.observeSyncStage()
            .distinctUntilChanged()
            .onEach { syncStage ->
                if (syncStage == SyncStage.PREBLOCKS || syncStage == SyncStage.BLOCKS && !isPlatformAvailable.value) {
                    isPlatformAvailable.value = if (Constants.SUPPORTS_PLATFORM) {
                        platformService.isPlatformAvailable()
                    } else {
                        false
                    }
                }
                _syncStage.value = syncStage ?: SyncStage.OFFLINE
            }
            .catch { e -> log.error("sync stage flow error", e) }
            .launchIn(viewModelScope)

        // Self-heal a transient DAPI "unavailable" verdict. The sync-stage
        // check above fires only when the STAGE changes (distinctUntilChanged),
        // so a `false` returned once the stage settles at BLOCKS would latch
        // forever — permanently hiding both Join DashPay entry points (the home
        // card and the More-screen DashPay section, each gated on
        // isAbleToCreateIdentity) until an app restart. Retry until Platform is
        // reachable, then stop; nothing here flips it back to false.
        if (Constants.SUPPORTS_PLATFORM) {
            viewModelScope.launch {
                while (isActive && !isPlatformAvailable.value) {
                    if (runCatching { platformService.isPlatformAvailable() }.getOrDefault(false)) {
                        isPlatformAvailable.value = true
                    } else {
                        delay(PLATFORM_AVAILABILITY_RETRY_MS)
                    }
                }
            }
        }
        restoringBackup = config.isRestoringBackup
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

    suspend fun getDeviceTimeSkew(force: Boolean): Pair<Boolean, Long> {
        return try {
            val timeSkew = getTimeSkew(force)
            return Pair(abs(timeSkew) > TIME_SKEW_TOLERANCE, timeSkew)
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

        if (data.creationState == IdentityCreationState.DONE) {
            identityRepository.doneAndDismiss()
            return true
        }

        return false
    }

    fun dismissUsernameCreatedCard() {
        viewModelScope.launch {
            identityRepository.doneAndDismiss()
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

    /**
     * The requested username in the user's own DISPLAY form ("contested1"),
     * never the DPNS-normalized label ("c0ntested1"). The identity restore
     * path historically persisted the normalized label into the USERNAME
     * pref (observed live on the More-screen tile), so when the stored
     * value matches a known request's normalizedLabel, the request's
     * display label wins — see [resolveRequestedUsernameDisplay].
     */
    suspend fun getRequestedUsername(): String {
        val stored = blockchainIdentityDataDao.get(BlockchainIdentityConfig.USERNAME) ?: ""
        if (stored.isEmpty()) return stored
        val identityId = blockchainIdentityDataDao.get(BlockchainIdentityConfig.IDENTITY_ID)
        val candidates = try {
            usernameRequestDao.getRequestsByNormalizedLabel(stored)
        } catch (e: Exception) {
            emptyList()
        }
        return resolveRequestedUsernameDisplay(stored, identityId, candidates)
    }
    suspend fun getInviteHistory() = invitationsDao.loadAll()

    private fun combineLatestData(): Boolean {
        return if (!Constants.SUPPORTS_PLATFORM) {
            log.info("platform is not supported")
            false
        } else {
            val isPlatformAvailable = isPlatformAvailable.value
            val identity = blockchainIdentity.value
            val noIdentityCreatedOrInProgress =
                identity == null || identity.creationState == IdentityCreationState.NONE
            log.info(
                "platform available: {}; no identity creation is progress: {}",
                isPlatformAvailable,
                noIdentityCreatedOrInProgress
            )
            return /*isSynced &&*/ isPlatformAvailable && noIdentityCreatedOrInProgress
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
        try {
            val encryptionKey = platformRepo.getWalletEncryptionKey()
                ?: throw IllegalStateException("cannot obtain wallet encryption key")
            (walletApplication.wallet as WalletEx).initializeCoinJoin(encryptionKey, 0)
        } catch (e: Exception) {
            log.error("problem adding CoinJoin support to wallet: ", e)
        }
    }

    fun observeMostRecentTransaction() = walletData.observeMostRecentTransaction().distinctUntilChanged()

    /** Delegates to [TxDisplayCacheService.forceRebuildTransactionCache]. */
    fun forceRebuildTransactionCache() = txDisplayCacheService.forceRebuildTransactionCache()

    /** Delegates to [TxDisplayCacheService.getTransactionWrapper]. */
    fun getTransactionWrapper(rowId: String): TransactionWrapper? =
        txDisplayCacheService.getTransactionWrapper(rowId)

    /** Delegates to [TxDisplayCacheService.loadGroupWrapper]. */
    suspend fun loadGroupWrapper(rowId: String): TransactionWrapper? =
        txDisplayCacheService.loadGroupWrapper(rowId)

    fun metadataReminder() {
        viewModelScope.launch {
            if (hasIdentity && !dashPayConfig.isTransactionMetadataInfoShown()) {
                // have there been 10 transactions since the last update?
                val installedDate = dashPayConfig.getMetadataFeatureInstalled()
                walletData.wallet?.let { wallet: Wallet ->
                    var count = 0
                    wallet.getTransactions(true).forEach { tx ->
                        if (tx.updateTime.time > installedDate) {
                            count++
                        }
                    }
                    if (count >= 10) {
                        _remindMetadata.value = true
                    }
                }
            }
        }
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
            percentage = 0
        }
        _blockchainSyncPercentage.postValue(percentage)
    }

    companion object {
        private const val DIRECTION_KEY = "tx_direction"
        private const val CROWDNODE_REMINDER_SHOWN_KEY = "crowdnode_withdrawal_reminder_shown"

        /**
         * Per-launch latch for the mixed-funds prompt. The PERMANENT latch is
         * [DashPayConfig.MIXED_FUNDS_MIGRATION_DONE]; this one only stops the
         * blockchain-state flow re-firing it within a single launch.
         */
        private const val MIXED_FUNDS_PROMPT_SHOWN_KEY = "mixed_funds_migration_prompt_shown"
        private const val TIME_SKEW_TOLERANCE = 3600000L // 1 hour
        /** Retry cadence for the self-healing Platform-availability poll (see init). */
        private const val PLATFORM_AVAILABILITY_RETRY_MS = 30_000L

        private val log = LoggerFactory.getLogger(MainViewModel::class.java)
    }
}

/**
 * Pick the DISPLAY form of the requested username, pure and host-testable.
 *
 * [stored] is whatever the USERNAME pref holds — the display form the user
 * typed ("contested1") on the direct creation path, but the DPNS-normalized
 * label ("c0ntested1", homoglyphs folded o→0/l→1) when the identity restore
 * path persisted `blockchainIdentity.primaryUsername` from the contested-
 * names index. [candidates] are the locally known username requests whose
 * normalizedLabel equals [stored]; the one belonging to [identityId] (any,
 * when the identity is unknown) carries the display label the contender
 * document was created with. Falls back to [stored] when no request
 * matches — never worse than the old behavior.
 */
internal fun resolveRequestedUsernameDisplay(
    stored: String,
    identityId: String?,
    candidates: List<UsernameRequest>
): String {
    val match = candidates.firstOrNull { identityId == null || it.identity == identityId }
        ?: return stored
    return match.username.ifEmpty { stored }
}
