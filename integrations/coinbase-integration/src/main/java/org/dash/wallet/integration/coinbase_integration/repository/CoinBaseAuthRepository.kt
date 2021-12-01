package org.dash.wallet.integration.coinbase_integration.repository

import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.network.safeApiCall
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseAuthApi
import javax.inject.Inject

class CoinBaseAuthRepository @Inject constructor(
    private val api: CoinBaseAuthApi,
    private val userPreferences: Configuration
) {
    suspend fun getUserToken(
        code: String
    ) = safeApiCall {
        api.getToken(code = code).also {
            it.body()?.let {
                saveAccessTokens(it.accessToken, it.refreshToken)
            }
        }
    }

    private fun saveAccessTokens(accessToken: String, refreshToken: String) {
        userPreferences.setLastCoinBaseAccessToken(accessToken)
        userPreferences.setLastCoinBaseRefreshToken(refreshToken)
    }

    fun getSavedUserCoinBaseToken(): String? {
        return userPreferences.lastCoinbaseAccessToken
    }

    fun isUserConnected() = userPreferences.lastCoinbaseAccessToken.isNullOrEmpty().not()
}
