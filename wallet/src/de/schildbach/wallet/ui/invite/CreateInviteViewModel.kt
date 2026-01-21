/*
 * Copyright 2021 Dash Core Group.
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

package de.schildbach.wallet.ui.invite

import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.bitcoinj.core.Coin
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
class CreateInviteViewModel @Inject constructor(
    private val walletData: WalletDataProvider,
    private val analytics: AnalyticsService,
    blockchainStateDao: BlockchainStateDao,
    invitationsDao: InvitationsDao,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {

    private val blockchainStateData = MutableStateFlow<BlockchainState?>(null)
    private val spendableBalance = MutableStateFlow(Coin.ZERO)
    val invitations = MutableStateFlow<List<Invitation>>(listOf())

    init {
        invitationsDao.observe()
            .onEach { invitations.value = it }
            .launchIn(viewModelScope)

        blockchainStateDao.observeState()
            .onEach { blockchainStateData.value = it }
            .launchIn(viewModelScope)

        walletData.observeSpendableBalance()
            .onEach {
                spendableBalance.value = it
            }
            .launchIn(viewModelScope)
    }

    val isAbleToCreateInviteFlow = combine(
        blockchainStateData,
        blockchainIdentity.asFlow(),
        spendableBalance
    ) { _, _, balance ->
        combineLatestData(balance)
    }.stateIn(viewModelScope, SharingStarted.Lazily, initialValue = combineLatestData(spendableBalance.value))

    val isAbleToCreateInvite: Boolean
        get() = isAbleToCreateInviteFlow.value

    private fun combineLatestData(balance: Coin): Boolean {
        val isSynced = blockchainStateData.value?.isSynced() ?: false
        val identityCreated = blockchainIdentity.value?.creationComplete ?: false
        return isSynced && identityCreated && balance >= Constants.DASH_PAY_FEE
    }

    val invitationCount: Int
        get() = invitations.value.size

    val isAbleToPerformInviteAction = combine(
        isAbleToCreateInviteFlow,
        invitations
    ) { _, _ ->
        combineLatestInviteActionData()
    }.stateIn(viewModelScope, SharingStarted.Lazily, initialValue = combineLatestData(spendableBalance.value))

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    private fun combineLatestInviteActionData(): Boolean {
        val hasInviteHistory = invitationCount > 0
        val canAffordInviteCreation = isAbleToCreateInvite
        return hasInviteHistory || canAffordInviteCreation
    }
}
