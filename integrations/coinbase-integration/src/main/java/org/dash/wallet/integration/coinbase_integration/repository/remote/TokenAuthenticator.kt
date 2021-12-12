/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
                    tokenResponse.value.body()?.let {
                        userPreferences.setLastCoinBaseAccessToken(it.accessToken)
                        userPreferences.setLastCoinBaseRefreshToken(it.refreshToken)
                        response.request().newBuilder()
                            .header("Authorization", "Bearer ${it.accessToken}")
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
