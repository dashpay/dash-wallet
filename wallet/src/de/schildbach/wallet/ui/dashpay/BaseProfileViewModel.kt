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
package de.schildbach.wallet.ui.dashpay

import androidx.lifecycle.*
import de.schildbach.wallet.database.dao.BlockchainIdentityDataDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.DashPayProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
open class BaseProfileViewModel(
    val blockchainIdentityDataDao: BlockchainIdentityDataDao,
    val dashPayProfileDao: DashPayProfileDao
) : ViewModel() {

    private val _blockchainIdentity = MutableLiveData<BlockchainIdentityBaseData?>()
    val blockchainIdentity: LiveData<BlockchainIdentityBaseData?>
        get() = _blockchainIdentity

    private val _dashPayProfile = MutableLiveData<DashPayProfile?>()
    val dashPayProfile: LiveData<DashPayProfile?>
        get() = _dashPayProfile

    val hasIdentity: Boolean
        get() = _blockchainIdentity.value?.creationComplete ?: false

    init {
        // blockchainIdentityData is observed instead of using PlatformRepo.getBlockchainIdentity()
        // since neither PlatformRepo nor blockchainIdentity is initialized when there is no username
        blockchainIdentityDataDao.observeBase()
            .distinctUntilChanged()
            .onEach(_blockchainIdentity::postValue)
            .filter { it?.userId != null }
            .flatMapLatest { dashPayProfileDao.observeByUserId(it?.userId!!) }
            .distinctUntilChanged()
            .onEach(_dashPayProfile::postValue)
            .launchIn(viewModelScope)
    }
}
