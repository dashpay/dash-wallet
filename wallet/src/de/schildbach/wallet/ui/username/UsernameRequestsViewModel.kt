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

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.username.adapters.UsernameRequestGroupView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

data class UsernameRequestsUIState(
    val usernameRequests: List<UsernameRequestGroupView> = listOf(),
    val showFirstTimeInfo: Boolean = false
)

@HiltViewModel
class UsernameRequestsViewModel @Inject constructor(
    private val dashPayConfig: DashPayConfig,
    private val usernameRequestDao: UsernameRequestDao
): ViewModel() {
    private val _uiState = MutableStateFlow(UsernameRequestsUIState())
    val uiState: StateFlow<UsernameRequestsUIState> = _uiState.asStateFlow()

    private val workerJob = SupervisorJob()
    val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    init {
        dashPayConfig.observe(DashPayConfig.VOTING_INFO_SHOWN)
            .onEach { isShown -> _uiState.update { it.copy(showFirstTimeInfo = isShown != true) } }
            .launchIn(viewModelScope)

        usernameRequestDao.observeDuplicates()
            .map { duplicates ->
                duplicates.groupBy { it.username }
                    .map { (username, list) ->
                        UsernameRequestGroupView(
                            username,
                            list.sortedByDescending { it.votes }
                        )
                    }
            }
            .onEach { requests -> _uiState.update { it.copy(usernameRequests = requests) } }
            .launchIn(viewModelWorkerScope)
    }

    suspend fun setFirstTimeInfoShown() {
        dashPayConfig.set(DashPayConfig.VOTING_INFO_SHOWN, true)
    }

    private var nameCount = 1
    fun prepopulateList() {
        nameCount++
        val now = System.nanoTime() / 1000
        val names = listOf("John", "doe", "Sarah", "Jane", "jack", "Jill", "Bob")

        viewModelScope.launch {
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, nameCount)],
                    Random.nextLong(1689230321, now),
                    "dslfsdkfsjs",
                    "https://example.com",
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, nameCount)],
                    Random.nextLong(1689230321, now),
                    "dslfsdkfsjs",
                    null,
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, nameCount)],
                    Random.nextLong(1689230321, now),
                    "dslfsdkfsjs",
                    null,
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, nameCount)],
                    Random.nextLong(1689230321, now),
                    "dslfsdkfsjs",
                    "https://example.com",
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, nameCount)],
                    Random.nextLong(1689230321, now),
                    "dslfsdkfsjs",
                    null,
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, nameCount)],
                    Random.nextLong(1689230321, now),
                    "dslfsdkfsjs",
                    null,
                    Random.nextInt(0, 15)
                )
            )
            usernameRequestDao.insert(
                UsernameRequest(
                    UUID.randomUUID().toString(),
                    names[Random.nextInt(0, nameCount)],
                    Random.nextLong(1689230321, now),
                    "dslfsdkfsjs",
                    null,
                    Random.nextInt(0, 15)
                )
            )
        }
    }
}
