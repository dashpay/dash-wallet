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
package org.dash.wallet.integration.coinbase_integration.repository.remote

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.model.TokenResponse
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.safeApiCall
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseTokenRefreshApi
import org.dash.wallet.integration.coinbase_integration.utils.CoinbaseConfig
import javax.inject.Inject

class TokenAuthenticator @Inject constructor(
    private val tokenApi: CoinBaseTokenRefreshApi,
    private val userPreferences: Configuration,
    private val config: CoinbaseConfig
) : Authenticator {

    // For multiple call to refresh token sync
    private val tokenMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        return runBlocking {
            tokenMutex.withLock {
                when (val tokenResponse = getUpdatedToken()) {
                    is ResponseResource.Success -> {
                        tokenResponse.value?.let {
                            userPreferences.setLastCoinBaseAccessToken(it.accessToken)
                            userPreferences.setLastCoinBaseRefreshToken(it.refreshToken)
                            response.request.newBuilder()
                                .header("Authorization", "Bearer ${it.accessToken}")
                                .build()
                        }
                    }

                    else -> {
                        userPreferences.setLastCoinBaseAccessToken(null)
                        userPreferences.setLastCoinBaseRefreshToken(null)
                        config.setPreference(CoinbaseConfig.LOGOUT_COINBASE, true)
                        null
                    }
                }
            }
        }
    }

    private suspend fun getUpdatedToken(): ResponseResource<TokenResponse?> {
        val refreshToken = userPreferences.lastCoinbaseRefreshToken
        println(" --- lastCoinbaseAccessToken --- ${userPreferences.lastCoinbaseAccessToken}")
        println(" === lastCoinbaseRefreshToken === ${userPreferences.lastCoinbaseRefreshToken}")
        return safeApiCall { tokenApi.refreshToken(refreshToken = refreshToken) }
    }
}
