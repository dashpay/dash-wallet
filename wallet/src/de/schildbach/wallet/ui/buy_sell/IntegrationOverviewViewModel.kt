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

package de.schildbach.wallet.ui.buy_sell

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class IntegrationOverviewViewModel @Inject constructor(
    private val config: CoinbaseConfig,
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val analyticsService: AnalyticsService
): ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(IntegrationOverviewViewModel::class.java)
    }

    suspend fun loginToCoinbase(code: String): Boolean {
        when (val response = coinBaseRepository.completeCoinbaseAuthentication(code)) {
            is ResponseResource.Success -> {
                if (response.value) {
                    return true
                }
            }

            is ResponseResource.Failure -> {
                log.error("Coinbase login error ${response.errorCode}: ${response.errorBody ?: "empty"}")
            }
        }

        return false
    }

    suspend fun shouldShowCoinbaseInfoPopup(): Boolean {
        return config.get(CoinbaseConfig.AUTH_INFO_SHOWN) != true
    }

    suspend fun setCoinbaseInfoPopupShown() {
        config.set(CoinbaseConfig.AUTH_INFO_SHOWN, true)
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }
}
