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

package de.schildbach.wallet.ui.payments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Context
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class PaymentsViewModel @Inject constructor(
    platformRepo: PlatformRepo,
    identityConfig: BlockchainIdentityConfig,
    private val walletDataProvider: WalletDataProvider,
    private val analytics: AnalyticsService
): ViewModel() {
    var fromQuickReceive: Boolean = false

    private val _dashPayProfile = MutableStateFlow<DashPayProfile?>(null)
    val dashPayProfile = _dashPayProfile.asLiveData()

    suspend fun getCurrentAddress() = withContext(Dispatchers.IO) {
        Context.propagate(walletDataProvider.wallet!!.context)
        walletDataProvider.currentReceiveAddress()
    }

    suspend fun getFreshAddress() = withContext(Dispatchers.IO) {
        Context.propagate(walletDataProvider.wallet!!.context)
        walletDataProvider.freshReceiveAddress()
    }
    init {
        identityConfig.observeBase()
            .flatMapLatest { identity ->
                if (identity.userId != null && identity.creationComplete) {
                    platformRepo.observeProfileByUserId(identity.userId)
                } else {
                    flowOf()
                }
            }
            .onEach {
                _dashPayProfile.value = it
            }
            .launchIn(viewModelScope)
    }

    fun logSpecifyAmount() {
        if (fromQuickReceive) {
            analytics.logEvent(AnalyticsConstants.LockScreen.QUICK_RECEIVE_AMOUNT, mapOf())
        } else {
            analytics.logEvent(AnalyticsConstants.SendReceive.SPECIFY_AMOUNT, mapOf())
        }
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }
}
