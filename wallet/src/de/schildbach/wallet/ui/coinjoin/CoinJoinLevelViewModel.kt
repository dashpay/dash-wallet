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

package de.schildbach.wallet.ui.coinjoin

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.MixingStatus
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
open class CoinJoinLevelViewModel @Inject constructor(
    private val analytics: AnalyticsService,
    private val coinJoinService: CoinJoinService,
    private var networkState: NetworkStateInt
) : ViewModel() {

    val isMixing: Boolean
        get() = coinJoinService.mixingStatus == MixingStatus.MIXING ||
            coinJoinService.mixingStatus == MixingStatus.PAUSED

    var mixingMode: CoinJoinMode
        get() = coinJoinService.mode
        set(value) {
            coinJoinService.mode = value
//            coinJoinService.prepareAndStartMixing() TODO restart mixing?
        }

    fun isWifiConnected(): Boolean {
        return networkState.isWifiConnected()
    }

    suspend fun startMixing(mode: CoinJoinMode) {
        analytics.logEvent(
            AnalyticsConstants.CoinJoinPrivacy.USERNAME_PRIVACY_BTN_CONTINUE,
            mapOf(AnalyticsConstants.Parameter.VALUE to mode.name)
        )

        coinJoinService.mode = mode
        coinJoinService.prepareAndStartMixing() // TODO: change the logic if needed
    }

    suspend fun stopMixing() {
//        coinJoinService.stopMixing() // TODO expose stop method?
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }
}
