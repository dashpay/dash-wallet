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
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.service.CoinBaseClientConstants
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseViewModel
import org.slf4j.LoggerFactory
import java.lang.Exception
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
        return try {
            coinBaseRepository.completeCoinbaseAuthentication(code)
        } catch (ex: Exception) {
            log.error("Coinbase login error $ex")
            false
        }
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

    fun getCoinbaseLinkAccountUrl(): String {
        return "https://www.coinbase.com/oauth/authorize?client_id=${CoinBaseClientConstants.CLIENT_ID}" +
                "&redirect_uri=${CoinbaseConstants.REDIRECT_URL}&response_type" +
                "=code&scope=wallet:accounts:read,wallet:user:read,wallet:payment-methods:read," +
                "wallet:buys:read,wallet:buys:create,wallet:transactions:transfer," +
                "wallet:sells:create,wallet:sells:read,wallet:deposits:create," +
                "wallet:transactions:request,wallet:transactions:read,wallet:trades:create," +
                "wallet:supported-assets:read,wallet:transactions:send," +
                "wallet:addresses:read,wallet:addresses:create" +
                "&meta[send_limit_amount]=10" +
                "&meta[send_limit_currency]=USD" +
                "&meta[send_limit_period]=month" +
                "&account=all"
    }
}
