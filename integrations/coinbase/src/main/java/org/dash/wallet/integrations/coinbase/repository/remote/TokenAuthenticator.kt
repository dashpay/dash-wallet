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
package org.dash.wallet.integrations.coinbase.repository.remote

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.safeApiCall
import org.dash.wallet.integrations.coinbase.model.TokenResponse
import org.dash.wallet.integrations.coinbase.service.CoinBaseTokenRefreshApi
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import org.slf4j.LoggerFactory
import javax.inject.Inject

class TokenAuthenticator @Inject constructor(
    private val tokenApi: CoinBaseTokenRefreshApi,
    private val config: CoinbaseConfig
) : Authenticator {
    companion object {
        private val log = LoggerFactory.getLogger(TokenAuthenticator::class.java)

        /**
         * The only codes on which Coinbase has definitively rejected the refresh token: 400
         * for an `invalid_grant` (revoked or expired), 401 for credentials it will not
         * accept. Per OAuth2 these are verdicts on the grant itself.
         */
        private val AUTH_REJECTION_CODES = setOf(400, 401)
    }

    // For multiple call to refresh token sync
    private val tokenMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        return runBlocking {
            tokenMutex.withLock {
                when (val tokenResponse = getUpdatedToken()) {
                    is ResponseResource.Success -> {
                        tokenResponse.value?.let {
                            config.set(CoinbaseConfig.LAST_ACCESS_TOKEN, it.accessToken)
                            config.set(CoinbaseConfig.LAST_REFRESH_TOKEN, it.refreshToken)
                            response.request.newBuilder()
                                .header("Authorization", "Bearer ${it.accessToken}")
                                .build()
                        }
                    }

                    is ResponseResource.Failure -> {
                        if (isAuthRejection(tokenResponse)) {
                            log.info(
                                "coinbase rejected the refresh token (code {}); clearing credentials",
                                tokenResponse.errorCode
                            )
                            config.set(CoinbaseConfig.LAST_ACCESS_TOKEN, "")
                            config.set(CoinbaseConfig.LAST_REFRESH_TOKEN, "")
                        } else {
                            // A transport failure says nothing about whether the token is
                            // still good. Clearing here de-authenticated the user and forced
                            // a re-link on a single network blip (MO-995).
                            log.warn(
                                "coinbase token refresh failed with no verdict on the token; keeping credentials",
                                tokenResponse.throwable
                            )
                        }
                        null
                    }
                }
            }
        }
    }

    /** True only when the failure is Coinbase rejecting the grant, not the network failing. */
    private fun isAuthRejection(failure: ResponseResource.Failure): Boolean {
        val code = failure.errorCode
        return !failure.isNetworkError && code != null && code in AUTH_REJECTION_CODES
    }

    private suspend fun getUpdatedToken(): ResponseResource<TokenResponse?> {
        val refreshToken = config.get(CoinbaseConfig.LAST_REFRESH_TOKEN) ?: ""
        return safeApiCall { tokenApi.refreshToken(refreshToken = refreshToken) }
    }
}
