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
package org.dash.wallet.integration.coinbase_integration.repository

import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.network.safeApiCall
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseAuthApi
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseServicesApi
import javax.inject.Inject

class CoinBaseRepository @Inject constructor(
    private val api: CoinBaseServicesApi,
    private val authApi: CoinBaseAuthApi,
    private val userPreferences: Configuration
) {
    suspend fun getUserAccount() = safeApiCall { api.getUserAccount() }

    suspend fun getExchangeRates() = safeApiCall { api.getExchangeRates() }

    suspend fun disconnectCoinbaseAccount() {
        userPreferences.setLastCoinBaseAccessToken(null)
        userPreferences.setLastCoinBaseRefreshToken(null)
        safeApiCall { authApi.revokeToken() }
    }

    fun saveLastCoinbaseDashAccountBalance(amount: String?) {
        amount?.let {
            userPreferences.setLastCoinBaseBalance(it)
        }
    }
}
