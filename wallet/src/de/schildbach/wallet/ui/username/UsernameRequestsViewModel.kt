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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.min
import kotlin.random.Random

data class UsernameRequestsUIState(
    val usernameRequests: List<UsernameRequestGroupView> = listOf(),
    val showFirstTimeInfo: Boolean = false
)

data class FiltersUIState(
    val sortByOption: UsernameSortOption = UsernameSortOption.DateDescending,
    val typeOption: UsernameTypeOption = UsernameTypeOption.All,
    val onlyDuplicates: Boolean = true,
    val onlyLinks: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsernameRequestsViewModel @Inject constructor(
    private val dashPayConfig: DashPayConfig,
    private val usernameRequestDao: UsernameRequestDao
): ViewModel() {
    private val _uiState = MutableStateFlow(UsernameRequestsUIState())
    val uiState: StateFlow<UsernameRequestsUIState> = _uiState.asStateFlow()

    private val _filterState = MutableStateFlow(FiltersUIState())
    val filterState: StateFlow<FiltersUIState> = _filterState.asStateFlow()

    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    init {
        dashPayConfig.observe(DashPayConfig.VOTING_INFO_SHOWN)
            .onEach { isShown -> _uiState.update { it.copy(showFirstTimeInfo = isShown != true) } }
            .launchIn(viewModelScope)

        _filterState.flatMapLatest { filterState ->
            usernameRequestDao.observeDuplicates()
                .map { duplicates ->
                    duplicates.groupBy { it.username }
                        .map { (username, list) ->
                            val sortedList = list.sort(filterState.sortByOption)
                            val max = when (filterState.sortByOption) {
                                UsernameSortOption.VotesDescending -> sortedList.first().votes
                                UsernameSortOption.VotesAscending -> sortedList.last().votes
                                else -> sortedList.maxOf { it.votes }
                            }
                            sortedList.forEach { it.hasMaximumVotes = it.votes == max }
                            UsernameRequestGroupView(username, sortedList, isExpanded = isExpanded(username))
                        }
                }
        }.onEach { requests -> _uiState.update { it.copy(usernameRequests = requests) } }
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

    private fun List<UsernameRequest>.sort(sortByOption: UsernameSortOption): List<UsernameRequest> {
        return this.sortedWith(
            when (sortByOption) {
                UsernameSortOption.DateAscending -> compareBy { it.createdAt }
                UsernameSortOption.DateDescending -> compareByDescending { it.createdAt }
                UsernameSortOption.VotesAscending -> compareBy { it.votes }
                UsernameSortOption.VotesDescending -> compareByDescending { it.votes }
            }
        )
    }

    private fun isExpanded(username: String): Boolean {
        return _uiState.value.usernameRequests.any { it.username == username && it.isExpanded }
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
                    "dslfsdkfsjs",
                    "https://example.com",
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    "dslfsdkfsjs",
                    null,
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    "dslfsdkfsjs",
                    null,
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    "dslfsdkfsjs",
                    "https://example.com",
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    "dslfsdkfsjs",
                    null,
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    "dslfsdkfsjs",
                    null,
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, min(names.size, nameCount))],
                    Random.nextLong(from, now),
                    "dslfsdkfsjs",
                    null,
                    Random.nextInt(0, 15)
                )
            )
        }
    }
}
