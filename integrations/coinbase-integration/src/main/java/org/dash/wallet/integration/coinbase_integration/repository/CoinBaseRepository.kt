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
