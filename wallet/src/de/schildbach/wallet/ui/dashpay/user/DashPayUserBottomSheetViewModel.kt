/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay.user

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet.data.NotificationItemContact
import de.schildbach.wallet.data.NotificationItemPayment
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.DashSystemService
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.work.SendContactRequestOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject

enum class NotificationFilter {
    ALL,
    RECEIVED,
    SENT
}

data class DashPayUserBottomSheetUIState(
    val userData: UsernameSearchResult? = null,
    val sendContactRequestState: Resource<Pair<String, String>>? = null,
    val notifications: List<NotificationItem> = emptyList(),
    val filter: NotificationFilter = NotificationFilter.ALL,
    val networkError: Boolean = false,
    val creditCheck: DashPayUserBottomSheetViewModel.CreditCheckResult? = null
)

@HiltViewModel
class DashPayUserBottomSheetViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val platformSyncService: PlatformSyncService,
    private val analytics: AnalyticsService,
    val platformRepo: PlatformRepo,
    val identityRepository: IdentityRepository,
    private val dashSystemService: DashSystemService,
    private val walletData: WalletDataProvider,
    private val blockchainStateDao: BlockchainStateDao
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(DashPayUserBottomSheetViewModel::class.java)
    }

    sealed class CreditCheckResult {
        object Loading : CreditCheckResult()
        object Error : CreditCheckResult()
        data class Insufficient(val empty: Boolean) : CreditCheckResult()
        object Sufficient : CreditCheckResult()
    }

    enum class CreditCheckOutcome {
        ShowError,
        ShowWarningEmpty,
        ShowWarningLow,
        Proceed
    }

    private var contactRequestStatusJob: Job? = null
    private var initialized = false

    private val _uiState = MutableStateFlow(DashPayUserBottomSheetUIState())
    val uiState: StateFlow<DashPayUserBottomSheetUIState> = _uiState.asStateFlow()

    // Source-of-truth raw notifications; the displayed `uiState.notifications` is this
    // list filtered through `uiState.filter`. Stored separately so the filter can
    // re-apply without re-fetching DB contacts.
    private val rawNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())

    init {
        blockchainStateDao.observeState()
            .onEach { state ->
                val networkError = state?.impediments?.contains(BlockchainState.Impediment.NETWORK) == true
                _uiState.update { it.copy(networkError = networkError) }
            }
            .catch { ex -> log.error("error observing blockchain state", ex) }
            .launchIn(viewModelScope)

        combine(rawNotifications, _uiState.map { it.filter }.distinctUntilChanged()) { raw, filter ->
            applyFilter(raw, filter)
        }
            .onEach { filtered -> _uiState.update { it.copy(notifications = filtered) } }
            .launchIn(viewModelScope)
    }

    fun setFilter(filter: NotificationFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    /** True if [tx] sends value out of this wallet. Used by the activity-row icon picker. */
    fun isSentTransaction(tx: org.bitcoinj.core.Transaction): Boolean =
        tx.getValue(walletData.transactionBag).signum() < 0

    private fun applyFilter(items: List<NotificationItem>, filter: NotificationFilter): List<NotificationItem> {
        if (filter == NotificationFilter.ALL) return items
        val bag = walletData.transactionBag
        return items.filter { item ->
            when (item) {
                is NotificationItemPayment -> {
                    val tx = item.tx ?: return@filter false
                    val sent = tx.getValue(bag).signum() < 0
                    val isInternal = tx.purpose == org.bitcoinj.core.Transaction.Purpose.KEY_ROTATION
                    when (filter) {
                        NotificationFilter.RECEIVED -> !sent && !isInternal
                        NotificationFilter.SENT -> sent && !isInternal
                        else -> true
                    }
                }
                else -> true
            }
        }
    }

    fun initUserData(userData: UsernameSearchResult) {
        if (initialized) return
        initialized = true

        _uiState.update { it.copy(userData = userData) }
        observeContactNotifications(userData.dashPayProfile)

        viewModelScope.launch {
            platformRepo.addOrUpdateDashPayProfile(userData.dashPayProfile)
            val username = userData.dashPayProfile.username

            if (userData.toContactRequest == null && userData.fromContactRequest == null) {
                try {
                    platformRepo.getLocalUserDataByUsername(username)?.let { fresh ->
                        log.info("obtained local user data for $username")
                        _uiState.update { state -> state.copy(userData = mergePreservingProfileFields(state.userData, fresh)) }
                    }
                } catch (ex: Exception) {
                    log.error("failed to obtain local user data for $username", ex)
                }
            }

            try {
                identityRepository.getUser(username).firstOrNull()?.let { fresh ->
                    _uiState.update { state -> state.copy(userData = mergePreservingProfileFields(state.userData, fresh)) }
                }
            } catch (ex: Exception) {
                log.error("Failed to load Profile", ex)
            }

            platformRepo.platform.stateRepository.addValidIdentity(userData.dashPayProfile.userIdentifier)

            if (SendContactRequestOperation.hasActiveOperation(context, userData.dashPayProfile.userId)) {
                initContactRequestStatusObservation(userData.dashPayProfile.userId)
            }
        }
    }

    fun sendContactRequest() {
        val userData = _uiState.value.userData ?: throw IllegalStateException("No user data")
        SendContactRequestOperation(context)
            .create(userData.dashPayProfile.userId)
            .enqueue()

        initContactRequestStatusObservation(userData.dashPayProfile.userId)
    }

    private fun initContactRequestStatusObservation(userId: String) {
        contactRequestStatusJob?.cancel()
        contactRequestStatusJob = SendContactRequestOperation.operationStatus(
            context, userId, analytics
        ).onEach { resource ->
            _uiState.update { it.copy(sendContactRequestState = resource) }
        }.launchIn(viewModelScope)
    }

    suspend fun hasEnoughCredits(): CreditBalanceInfo? {
        return identityRepository.getIdentityBalance()
    }

    fun getChainLockBlockHeight(): Int {
        return dashSystemService.system.chainLockHandler.bestChainLockBlockHeight
    }

    suspend fun checkCreditsAndSend(): CreditCheckOutcome {
        _uiState.update { it.copy(creditCheck = CreditCheckResult.Loading) }
        val enough = hasEnoughCredits()
        return if (enough == null) {
            _uiState.update { it.copy(creditCheck = CreditCheckResult.Error) }
            CreditCheckOutcome.ShowError
        } else {
            val isEmpty = enough.isBalanceEmpty()
            val shouldWarn = enough.isBalanceWarning()
            when {
                isEmpty -> {
                    _uiState.update { it.copy(creditCheck = CreditCheckResult.Insufficient(true)) }
                    CreditCheckOutcome.ShowWarningEmpty
                }
                shouldWarn -> {
                    _uiState.update { it.copy(creditCheck = CreditCheckResult.Insufficient(false)) }
                    CreditCheckOutcome.ShowWarningLow
                }
                else -> {
                    _uiState.update { it.copy(creditCheck = CreditCheckResult.Sufficient) }
                    CreditCheckOutcome.Proceed
                }
            }
        }
    }

    fun resetCreditCheck() {
        _uiState.update { it.copy(creditCheck = null) }
    }

    private fun observeContactNotifications(dashPayProfile: DashPayProfile) {
        combine(
            identityRepository.observeContacts(dashPayProfile.username, UsernameSortOrderBy.DATE_ADDED, true)
                .distinctUntilChanged(),
            walletData.observeMostRecentTransaction()
                .distinctUntilChanged()
        ) { contacts, _ ->
            contacts
        }.map { toNotificationItems(dashPayProfile.userId, it) }
            .onEach { results -> rawNotifications.value = results }
            .catch { ex ->
                log.error("error while observing contact requests", ex)
            }
            .launchIn(viewModelScope)
    }

    /**
     * Platform sometimes returns a partial DashPayProfile (e.g. with an empty avatarUrl) even
     * though the local DB and contact-request flow have richer data. Wholesale replacing
     * `_uiState.userData` with such a partial profile makes the header avatar revert to the
     * placeholder mid-session. Preserve non-empty fields from the current profile.
     */
    private fun mergePreservingProfileFields(
        current: UsernameSearchResult?,
        fresh: UsernameSearchResult
    ): UsernameSearchResult {
        if (current == null) return fresh
        val currentProfile = current.dashPayProfile
        val freshProfile = fresh.dashPayProfile
        val mergedProfile = freshProfile.copy(
            displayName = freshProfile.displayName.ifEmpty { currentProfile.displayName },
            publicMessage = freshProfile.publicMessage.ifEmpty { currentProfile.publicMessage },
            avatarUrl = freshProfile.avatarUrl.ifEmpty { currentProfile.avatarUrl },
            avatarHash = freshProfile.avatarHash ?: currentProfile.avatarHash,
            avatarFingerprint = freshProfile.avatarFingerprint ?: currentProfile.avatarFingerprint
        )
        return fresh.copy(dashPayProfile = mergedProfile)
    }

    suspend fun toNotificationItems(userId: String, contactRequests: List<UsernameSearchResult>): List<NotificationItem> {
        return withContext(Dispatchers.IO) {
            val results = arrayListOf<NotificationItem>()
            var accountReference = 0
            contactRequests.filter { cr ->
                cr.dashPayProfile.userId == userId
            }.forEach {
                val current = _uiState.value.userData
                // Refresh _userData when type changes OR when the profile differs (e.g. the
                // initial search result had no avatarUrl but the DB now does). Without the
                // profile-diff check, the header stays on the stale initial profile while
                // the notification rows render the fresh DB-backed one.
                if (current == null || it.type != current.type || it.dashPayProfile != current.dashPayProfile) {
                    _uiState.update { state -> state.copy(userData = it) }
                }

                if (it.type == UsernameSearchResult.Type.REQUEST_RECEIVED) {
                    results.add(NotificationItemContact(it, true))
                    accountReference = it.fromContactRequest!!.accountReference
                } else {
                    results.add(NotificationItemContact(it))
                }
                if (it.type == UsernameSearchResult.Type.CONTACT_ESTABLISHED) {
                    val incoming = (it.toContactRequest!!.timestamp > it.fromContactRequest!!.timestamp)
                    val invitationItem =
                        if (incoming) it.copy(toContactRequest = null) else it.copy(fromContactRequest = null)
                    results.add(NotificationItemContact(invitationItem, isInvitationOfEstablished = true))
                    accountReference = it.fromContactRequest!!.accountReference
                }
            }

            val blockchainIdentity = identityRepository.blockchainIdentity ?: run {
                log.warn("blockchainIdentity is null, cannot get contact transactions")
                return@withContext emptyList()
            }
            val txs = blockchainIdentity.getContactTransactions(Identifier.from(userId), accountReference)

            txs.forEach {
                results.add(NotificationItemPayment(it))
            }

            val sortedResults = results.sortedWith(
                compareByDescending { item: NotificationItem -> item.getDate() }.thenBy { item: NotificationItem -> item.getId() }
            )

            return@withContext sortedResults
        }
    }
}