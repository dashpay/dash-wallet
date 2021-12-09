package org.dash.wallet.integration.coinbase_integration.repository.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.model.TokenResponse
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.network.safeApiCall
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseTokenRefreshApi
import javax.inject.Inject

class TokenAuthenticator @Inject constructor(
    private val tokenApi: CoinBaseTokenRefreshApi,
    private val userPreferences: Configuration
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        return runBlocking {
            when (val tokenResponse = getUpdatedToken()) {
                is ResponseResource.Success -> {
                    tokenResponse.value.body()?.let { tokenResponse ->
                        userPreferences.setLastCoinBaseAccessToken(tokenResponse.accessToken)
                        userPreferences.setLastCoinBaseRefreshToken(tokenResponse.refreshToken)
                        response.request().newBuilder()
                            .header("Authorization", "Bearer ${tokenResponse.accessToken}")
                            .build()
                    }
                }
                else -> null
            }
        }
    }

    private suspend fun getUpdatedToken(): ResponseResource<retrofit2.Response<TokenResponse>> {
        val refreshToken = userPreferences.lastCoinbaseRefreshToken
        return safeApiCall { tokenApi.refreshToken(refreshToken = refreshToken) }
    }
}
