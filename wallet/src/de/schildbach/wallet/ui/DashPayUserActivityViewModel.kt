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
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.DashSystemService
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.WalletDataProvider
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
    private val dashSystemService: DashSystemService,
    private val walletData: WalletDataProvider
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
                platformRepo.getUser(username).firstOrNull()?.let {
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
        }.launchIn(viewModelScope)
    }

    suspend fun hasEnoughCredits(): CreditBalanceInfo {
        return platformRepo.getIdentityBalance()
    }

    fun getChainLockBlockHeight(): Int {
        return dashSystemService.system.chainLockHandler.bestChainLockBlockHeight
    }

    private fun observeContactNotifications(dashPayProfile: DashPayProfile) {
        combine(
            platformRepo.observeContacts(dashPayProfile.username, UsernameSortOrderBy.DATE_ADDED, true)
                .distinctUntilChanged(),
            walletData.observeMostRecentTransaction()
                .distinctUntilChanged()
        ) { contacts, _ ->
            contacts
        }.map { toNotificationItems(dashPayProfile.userId, it) }
         .onEach { results ->
            _notifications.value = results
         }
         .catch { ex ->
            log.error("error while observing contact requests", ex)
         }
         .launchIn(viewModelScope)
    }

    suspend fun toNotificationItems(userId: String, contactRequests: List<UsernameSearchResult>): List<NotificationItem> {
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

            val blockchainIdentity = platformRepo.blockchainIdentity
            val txs = blockchainIdentity.getContactTransactions(Identifier.from(userId), accountReference)

            txs.forEach {
                results.add(NotificationItemPayment(it))
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