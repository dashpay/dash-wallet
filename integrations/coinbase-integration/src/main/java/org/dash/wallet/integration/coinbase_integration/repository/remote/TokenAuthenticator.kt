package org.dash.wallet.integration.coinbase_integration.repository.remote

import android.content.Context
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
    context: Context,
    private val tokenApi: CoinBaseTokenRefreshApi,
    private val userPreferences: Configuration
) : Authenticator {

//    private val appContext = context.applicationContext
//    // private val userPreferences = UserPreferences(appContext)

    override fun authenticate(route: Route?, response: Response): Request? {
        return runBlocking {
//
//            if (userPreferences.accessToken.first().isNullOrEmpty()) {
//                when (val tokenResponse = getUpdatedToken()) {
//                    is ResponseResource.Success -> {
//                        userPreferences.saveAccessTokens(
//                            tokenResponse.value.access_token,
//                            tokenResponse.value.refresh_token
//                        )
//                        response.request().newBuilder()
//                            .header("Authorization", "Bearer ${tokenResponse.value.access_token}")
//                            .build()
//                    }
//                    else -> null
//                }
//            } else {
//                response.request().newBuilder()
//                    .header("Authorization", "Bearer " + userPreferences.accessToken.first())
//                    .build()
//            }
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
