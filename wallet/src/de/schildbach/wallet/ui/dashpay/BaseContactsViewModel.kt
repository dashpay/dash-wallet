/*
 * Copyright 2024 Dash Core Group.
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
package de.schildbach.wallet.ui.dashpay

import androidx.lifecycle.*
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

// base view model that keeps track of contact requests
@OptIn(ExperimentalCoroutinesApi::class)
open class BaseContactsViewModel(
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao,
    protected val contactRequestDao: DashPayContactRequestDao
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {

    private val _hasContacts = MutableStateFlow(false)
    val hasContacts: StateFlow<Boolean>
        get() = _hasContacts

    init {
        blockchainIdentityDataDao.observe(BlockchainIdentityConfig.IDENTITY_ID)
            .filterNotNull()
            .flatMapLatest {
                val size = contactRequestDao.loadFromOthers(it).size
                println(size)
                contactRequestDao.observeReceivedRequestsCount(it)
            }
            .onEach {
                _hasContacts.value = it != 0
            }
            .launchIn(viewModelScope)
    }
}
