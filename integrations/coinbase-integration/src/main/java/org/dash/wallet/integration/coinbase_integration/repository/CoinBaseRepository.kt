package org.dash.wallet.integration.coinbase_integration.repository

import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.network.safeApiCall
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseServicesApi
import javax.inject.Inject

class CoinBaseRepository @Inject constructor(
    private val api: CoinBaseServicesApi,
    private val userPreferences: Configuration
) {
    suspend fun getUserAccount() = safeApiCall { api.getUserAccount() }

    suspend fun getExchangeRates() = safeApiCall { api.getExchangeRates() }

    fun disconnectCoinbaseAccount() {
        userPreferences.setLastCoinBaseAccessToken(null)
        userPreferences.setLastCoinBaseRefreshToken(null)
    }
}
