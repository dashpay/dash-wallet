/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.username

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.username.adapters.UsernameRequestGroupView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bitcoinj.core.Base58
import java.util.UUID
import javax.inject.Inject
import kotlin.math.min
import kotlin.random.Random

data class UsernameRequestsUIState(
    val filteredUsernameRequests: List<UsernameRequestGroupView> = listOf(),
    val showFirstTimeInfo: Boolean = false,
    val voteSubmitted: Boolean = false,
    val voteCancelled: Boolean = false
)

data class FiltersUIState(
    val sortByOption: UsernameSortOption = UsernameSortOption.defaultOption,
    val typeOption: UsernameTypeOption = UsernameTypeOption.defaultOption,
    val onlyDuplicates: Boolean = true,
    val onlyLinks: Boolean = false
) {
    fun isDefault(): Boolean {
        // typeOption isn't included because we show it in the header
        return sortByOption == UsernameSortOption.DateDescending &&
            !onlyDuplicates &&
            !onlyLinks
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsernameRequestsViewModel @Inject constructor(
    private val dashPayConfig: DashPayConfig,
    private val usernameRequestDao: UsernameRequestDao
): ViewModel() {
    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    private val _uiState = MutableStateFlow(UsernameRequestsUIState())
    val uiState: StateFlow<UsernameRequestsUIState> = _uiState.asStateFlow()

    private val _filterState = MutableStateFlow(FiltersUIState())
    val filterState: StateFlow<FiltersUIState> = _filterState.asStateFlow()

    private val _selectedUsernameRequestId = MutableStateFlow<String?>(null)
    val selectedUsernameRequest: Flow<UsernameRequest> = _selectedUsernameRequestId
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { id ->
            usernameRequestDao.observeRequest(id)
                .filterNotNull()
                .distinctUntilChanged()
        }

    private val validKeys = listOf(
        "kn2GwaSZkoY8qg6i2dPCpDtDoBCftJWMzZXtHDDJ1w7PjFYfq",
        "n6YtJ7pdDYPTa57imEHEp8zinq1oNGUdwZQdnGk1MMpCWBHEq",
        "maEiRZeKXNLZovNqoS3HkmZJGmACbro7s3eC8GenExLF7QMQs"
    )

    private val _addedKeys = MutableStateFlow(listOf<String>())
    val masternodeIPs: Flow<List<String>> = _addedKeys.map {
        List(it.size) { i -> "323.232.23.$i" }
    }

    val keysAmount: Int
        get() = _addedKeys.value.size

    init {
        dashPayConfig.observe(DashPayConfig.VOTING_INFO_SHOWN)
            .onEach { isShown -> _uiState.update { it.copy(showFirstTimeInfo = isShown != true) } }
            .launchIn(viewModelScope)

        _filterState.flatMapLatest {
            observeUsernames()
                .map { duplicates ->
                    duplicates.groupBy { it.username }
                        .map { (username, list) ->
                            val sortedList = list.sortAndFilter()
                            UsernameRequestGroupView(username, sortedList, isExpanded = isExpanded(username))
                        }.filterNot { it.requests.isEmpty() }
                }
        }.onEach { requests -> _uiState.update { it.copy(filteredUsernameRequests = requests) } }
            .launchIn(viewModelWorkerScope)
    }

    suspend fun setFirstTimeInfoShown() {
        dashPayConfig.set(DashPayConfig.VOTING_INFO_SHOWN, true)
    }

    fun applyFilters(
        sortByOption: UsernameSortOption,
        typeOption: UsernameTypeOption,
        onlyDuplicates: Boolean,
        onlyLinks: Boolean
    ) {
        _filterState.update {
            it.copy(
                sortByOption = sortByOption,
                typeOption = typeOption,
                onlyDuplicates = onlyDuplicates,
                onlyLinks = onlyLinks
            )
        }
    }

    fun selectUsernameRequest(requestId: String) {
        _selectedUsernameRequestId.value = requestId
    }

    fun vote(requestId: String) {
        if (keysAmount == 0) {
            return
        }

        viewModelScope.launch {
            usernameRequestDao.getRequest(requestId)?.let { request ->
                usernameRequestDao.update(request.copy(votes = request.votes + keysAmount, isApproved = true))
                _uiState.update { it.copy(voteSubmitted = true) }
            }
        }
    }

    fun revokeVote(requestId: String) {
        if (keysAmount == 0) {
            return
        }

        viewModelScope.launch {
            usernameRequestDao.getRequest(requestId)?.let { request ->
                usernameRequestDao.update(request.copy(votes = request.votes - keysAmount, isApproved = false))
                _uiState.update { it.copy(voteCancelled = true) }
            }
        }
    }

    fun voteForAll() {
        if (keysAmount == 0) {
            return
        }

        viewModelScope.launch {
            val filteredRequests = _uiState.value.filteredUsernameRequests
            val requestIds = filteredRequests.flatMap {
                it.requests
            }.filterNot {
                it.isApproved
            }.map {
                it.requestId
            }
            usernameRequestDao.voteForRequests(
                requestIds,
                keysAmount
            )
            _uiState.update { it.copy(voteSubmitted = true) }
        }
    }

    fun voteHandled() {
        _uiState.update { it.copy(voteSubmitted = false, voteCancelled = false) }
    }

    fun verifyKey(key: String): Boolean {
        return validKeys.contains(key)
    }

    fun addKey(key: String) {
        _addedKeys.value = _addedKeys.value + key
    }

    private fun observeUsernames(): Flow<List<UsernameRequest>> {
        return if (_filterState.value.onlyDuplicates) {
            usernameRequestDao.observeDuplicates(_filterState.value.onlyLinks)
        } else {
            usernameRequestDao.observeAll(_filterState.value.onlyLinks)
        }
    }

    private fun List<UsernameRequest>.sortAndFilter(): List<UsernameRequest> {
        val sortByOption = _filterState.value.sortByOption
        val sorted = this.sortedWith(
            when (sortByOption) {
                UsernameSortOption.DateAscending -> compareBy { it.createdAt }
                UsernameSortOption.DateDescending -> compareByDescending { it.createdAt }
                UsernameSortOption.VotesAscending -> compareBy { it.votes }
                UsernameSortOption.VotesDescending -> compareByDescending { it.votes }
            }
        )

        return when (_filterState.value.typeOption) {
            UsernameTypeOption.All -> sorted
            UsernameTypeOption.Approved -> sorted.filter { it.isApproved }
            UsernameTypeOption.NotApproved -> sorted.filter { !it.isApproved }
        }
    }

    private fun isExpanded(username: String): Boolean {
        return _uiState.value.filteredUsernameRequests.any { it.username == username && it.isExpanded }
    }

    private var nameCount = 1
    fun prepopulateList() {
        nameCount++
        val now = System.currentTimeMillis() / 1000
        val names = listOf("John", "doe", "Sarah", "Jane", "jack", "Jill", "Bob")
        val from = 1658290321L

        viewModelScope.launch {
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    "https://www.figma.com/file/hh5juOSdGnNNPijJG1NGTi/DashPay%E3%83%BBIn-" +
                        "process%E3%83%BBAndroid?type=design&node-id=752-11735&mode=design&t=zasn6AKlSwb5NuYS-0",
                    Random.nextInt(0, 15),
                    true
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    null,
                    Random.nextInt(0, 15),
                    true
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    null,
                    Random.nextInt(0, 15),
                    false
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    "https://twitter.com/ProductHunt/",
                    Random.nextInt(0, 15),
                    false
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    null,
                    Random.nextInt(0, 15),
                    false
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    null,
                    Random.nextInt(0, 15),
                    false
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    Base58.encode(UUID.randomUUID().toString().toByteArray()),
                    null,
                    Random.nextInt(0, 15),
                    false
                )
            )
        }
    }
}
