/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui

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
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.DashSystemService
import de.schildbach.wallet.service.DisplayCacheRefreshBus
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class DashPayUserActivityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val platformSyncService: PlatformSyncService,
    private val analytics: AnalyticsService,
    val platformRepo: PlatformRepo,
    val identityRepository: IdentityRepository,
    private val dashSystemService: DashSystemService,
    private val walletData: WalletData,
    private val txDisplayCacheDao: TxDisplayCacheDao,
    private val displayCacheRefreshBus: DisplayCacheRefreshBus
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(DashPayUserActivityViewModel::class.java)
    }

    private var contactRequestStatusJob: Job? = null

    private val _userData = MutableStateFlow<UsernameSearchResult?>(null)
    val userData: StateFlow<UsernameSearchResult?>
        get() = _userData.asStateFlow()

    private val _sendContactRequestState = MutableStateFlow<Resource<Pair<String, String>>?>(null)
    val sendContactRequestState: StateFlow<Resource<Pair<String, String>>?>
        get() = _sendContactRequestState.asStateFlow()

    /**
     * Fires once per FAILED send/accept contact request operation so the
     * failure is surfaced (dialog) instead of the UI silently reverting to
     * its pre-send state. One-shot: re-observation (rotation, resume) does
     * not re-fire; a retried operation that fails again fires anew.
     */
    val sendContactRequestError = SingleLiveEvent<String?>()
    private var contactRequestErrorSurfaced = false

    private val _notifications = MutableStateFlow<List<NotificationItem>>(listOf())
    val notifications: StateFlow<List<NotificationItem>>
        get() = _notifications.asStateFlow()

    fun initUserData(userData: UsernameSearchResult) {
        _userData.value = userData
        observeContactNotifications(userData.dashPayProfile)

        viewModelScope.launch {
            // save the profile to the database for non-contacts
            platformRepo.addOrUpdateDashPayProfile(userData.dashPayProfile)
            val username = userData.dashPayProfile.username

            if (userData.toContactRequest == null && userData.fromContactRequest == null) {
                try {
                    platformRepo.getLocalUserDataByUsername(username)?.let {
                        log.info("obtained local user data for $username")
                        _userData.value = it
                    }
                } catch (ex: Exception) {
                    log.error("failed to obtain local user data for $username", ex)
                }
            }

            try {
                identityRepository.getUser(username).firstOrNull()?.let {
                    _userData.value = it
                }
            } catch (ex: Exception) {
                log.error("Failed to load Profile", ex)
            }

            platformRepo.platform.stateRepository.addValidIdentity(userData.dashPayProfile.userIdentifier)
            
            // Check if there's an ongoing contact request operation for this user
            if (SendContactRequestOperation.hasActiveOperation(context, userData.dashPayProfile.userId)) {
                initContactRequestStatusObservation(userData.dashPayProfile.userId)
            }
        }
    }

    fun sendContactRequest() {
        val userData = userData.value ?: throw IllegalStateException("No user data")
        SendContactRequestOperation(context)
                .create(userData.dashPayProfile.userId)
                .enqueue()

        // Observe the status of the request
        initContactRequestStatusObservation(userData.dashPayProfile.userId)
    }

    private fun initContactRequestStatusObservation(userId: String) {
        contactRequestStatusJob?.cancel()
        contactRequestStatusJob = SendContactRequestOperation.operationStatus(
            context, userId, analytics
        ).onEach { resource ->
            _sendContactRequestState.value = resource
            when (resource.status) {
                // A new run of the operation re-arms the error latch.
                Status.LOADING -> contactRequestErrorSurfaced = false
                Status.ERROR -> if (!contactRequestErrorSurfaced) {
                    contactRequestErrorSurfaced = true
                    sendContactRequestError.postValue(resource.message)
                }
                else -> Unit
            }
        }.launchIn(viewModelScope)
    }

    suspend fun hasEnoughCredits(): CreditBalanceInfo? {
        return identityRepository.getIdentityBalance()
    }

    fun getChainLockBlockHeight(): Int {
        return dashSystemService.system.chainLockHandler.bestChainLockBlockHeight
    }

    private fun observeContactNotifications(dashPayProfile: DashPayProfile) {
        combine(
            identityRepository.observeContacts(dashPayProfile.username, UsernameSortOrderBy.DATE_ADDED, true)
                .distinctUntilChanged(),
            // Trigger to re-run the merge when a dashj tx changes. It must NOT gate the combine:
            // combine only emits once EVERY source has emitted, but observeMostRecentTransaction
            // emits nothing until the dashj wallet changes — and on a wallet whose funds are
            // entirely SDK-owned (received contact payments are never bridged into the held dashj
            // wallet, and no send succeeded), the dashj wallet is empty so it never emits, leaving
            // the whole screen blank. Seed an initial Unit so the combine fires from the contacts +
            // cache sources; the cache flow (observeByContactUserId) already supplies reactivity.
            walletData.observeMostRecentTransaction()
                .map { }
                .onStart { emit(Unit) },
            // Reactive corrected-display source: re-emits whenever any of this contact's
            // tx_display_cache rows change, so the payment rows update LIVE (direction/amount
            // correct within a tick, "Sending"→"Sent" flips on IS-lock) instead of being resolved
            // once at list-build time. distinctUntilChanged suppresses no-op re-queries.
            txDisplayCacheDao.observeByContactUserId(dashPayProfile.userId)
                .distinctUntilChanged(),
            // Belt-and-suspenders trigger: CutoverUiDataService signals this bus immediately
            // after every display-cache write, so the merge re-runs (and re-reads the cache
            // fresh) even when Room's InvalidationTracker misses the change - the root cause of
            // a row lingering on "Sending"/"Processing" for minutes. onStart seeds the initial
            // emission so combine fires from the contacts + cache sources on first observe.
            displayCacheRefreshBus.changes
                .onStart { emit(Unit) }
        ) { contacts, _, _, _ ->
            contacts
        }.map { contacts ->
            // Re-read the corrected rows FRESH on every trigger (contacts change, dashj tx
            // change, Room invalidation, or a bus signal) rather than trusting the reactive
            // Flow's snapshot - this is what makes a late/missed Room invalidation still land.
            val correctedRows = txDisplayCacheDao.getByContactUserId(dashPayProfile.userId)
            // Catch INSIDE the map so a transient failure never propagates to the terminal
            // .catch below — which would COMPLETE the flow and permanently stop live updates
            // until the screen is recreated (the "stuck on Sending for minutes, fine after
            // reopen" symptom). On error keep the last emitted list so the flow stays alive and
            // the next cache/contact change re-runs the merge.
            try {
                toNotificationItems(dashPayProfile.userId, contacts, correctedRows)
            } catch (ex: Exception) {
                log.error("error building contact notification items", ex)
                _notifications.value ?: emptyList()
            }
        }
         .onEach { results ->
            _notifications.value = results
         }
         .catch { ex ->
            log.error("error while observing contact requests", ex)
         }
         .launchIn(viewModelScope)
    }

    suspend fun toNotificationItems(
        userId: String,
        contactRequests: List<UsernameSearchResult>,
        correctedRows: List<TxDisplayCacheEntry>
    ): List<NotificationItem> {
        return withContext(Dispatchers.IO) {
            val results = arrayListOf<NotificationItem>()
            var accountReference = 0
            contactRequests.filter { cr ->
                cr.dashPayProfile.userId == userId
            }.forEach {
                if (it.type != _userData.value?.type) {
                    _userData.value = it
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

            // dashj-sourced contact txs. On a held/empty dashj wallet (post-cutover) or a
            // transient error this yields nothing — but the SDK-only rows below (received contact
            // payments the SDK owns, never bridged into dashj) must STILL render, so fall through
            // to emptyList() instead of returning and discarding the whole screen.
            val txs = try {
                identityRepository.blockchainIdentity
                    ?.getContactTransactions(Identifier.from(userId), accountReference)
                    ?: emptyList()
            } catch (ex: Exception) {
                log.warn("getContactTransactions failed; rendering SDK-sourced rows only", ex)
                emptyList()
            }

            // Resolve the SDK-corrected display record for each payment txid from the REACTIVE
            // cache snapshot passed in (observeByContactUserId). For an SDK-authored contact send
            // the dashj Transaction mis-reads direction/amount/status (it only sees the +change),
            // so when a tx_display_cache row exists it carries the authoritative correction and is
            // threaded into the row. Because this snapshot re-emits on every cache change, the rows
            // now update live (direction/amount correct within a tick; "Sending"→"Sent" flips on
            // IS-lock). Absent (pre-cutover / non-SDK txs) → null → the ViewHolder renders from the
            // dashj tx exactly as before.
            val correctedById = correctedRows.associateBy { it.rowId }

            val dashjRowIds = HashSet<String>(txs.size)
            txs.forEach {
                val rowId = it.txId.toString().lowercase()
                dashjRowIds.add(rowId)
                results.add(NotificationItemPayment(it, correctedById[rowId]))
            }

            // Received contact payments the SDK owns but never bridged into the held dashj
            // wallet have NO dashj Transaction, so the loop above never emits them. Add one
            // payment row per cache entry whose txid dashj did not already surface — rendered
            // and opened entirely from the entry (tx = null). Deduped by rowId: a tx present
            // in BOTH is rendered once, via the dashj-corrected path above.
            correctedRows.forEach { entry ->
                if (entry.rowId !in dashjRowIds) {
                    results.add(NotificationItemPayment(correctedDisplay = entry))
                }
            }

            //TODO: gather other notification types
            // * invitations
            // * other

            val sortedResults = results.sortedWith(
                compareByDescending { item: NotificationItem -> item.getDate() }.thenBy { item: NotificationItem -> item.getId() }
            )

            return@withContext sortedResults
        }
    }
}