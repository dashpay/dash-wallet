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
package org.dash.wallet.features.exploredash.network.authenticator

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.RefreshTokenRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.RefreshTokenResponse
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendTokenApi
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import retrofit2.HttpException
import javax.inject.Inject

class TokenAuthenticator @Inject constructor(
    private val tokenApi: CTXSpendTokenApi,
    private val config: CTXSpendConfig
) : Authenticator {

    companion object {
        // Shared across every TokenAuthenticator instance (the OkHttp retry path plus the
        // DI/factory-built instances) so concurrent refreshes are serialized against the
        // single shared token store. Without this, a refresh that loses the race could
        // receive a 401 for an already-rotated refresh token and clear tokens that another
        // caller just saved.
        private val tokenMutex = Mutex()
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.responseCount >= 2) {
            return null
        }

        return runBlocking {
            refreshAccessToken(response.request.accessToken)
                ?.let { response.request.withAccessToken(it) }
        }
    }

    /**
     * Refreshes the access token under a process-wide lock and persists the result, so the
     * OkHttp retry path and explicit [CTXSpendRepository.refreshToken] calls never issue
     * overlapping refreshes.
     *
     * @param staleAccessToken the rejected access token, when known (the OkHttp retry path).
     *   If another caller already rotated the token, the freshly stored token is returned
     *   without a network call. Pass null to force a refresh.
     * @return the valid access token, or null when the refresh token was rejected (state is
     *   cleared) or the refresh failed transiently (state is preserved).
     */
    suspend fun refreshAccessToken(staleAccessToken: String? = null): String? = tokenMutex.withLock {
        val currentAccessToken = config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN)

        if (staleAccessToken != null && !currentAccessToken.isNullOrBlank() && currentAccessToken != staleAccessToken) {
            return@withLock currentAccessToken
        }

        try {
            withContext(NonCancellable) {
                val tokenResponse = getUpdatedToken()
                val accessToken = tokenResponse?.accessToken?.takeIf { it.isNotBlank() }
                    ?: return@withContext null

                config.saveTokenState(accessToken, tokenResponse.refreshToken)
                accessToken
            }
        } catch (e: Exception) {
            // Only a genuine refresh-token rejection drops the session. The refresh endpoint
            // returns HTTP 401 for that case, which ErrorHandlingInterceptor's /refresh-token
            // passthrough surfaces as an HttpException; any other failure is treated as
            // transient and the tokens are kept so the user stays signed in.
            if (e.isRefreshTokenRejected()) {
                config.clearTokenState()
            }
            null
        }
    }

    private suspend fun getUpdatedToken(): RefreshTokenResponse? {
        val refreshToken = config.getSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN) ?: ""
        return tokenApi.refreshToken(RefreshTokenRequest(refreshToken = refreshToken))
    }

    private val Response.responseCount: Int
        get() {
            var result = 1
            var priorResponse = this.priorResponse
            while (priorResponse != null) {
                result++
                priorResponse = priorResponse.priorResponse
            }
            return result
        }

    private val Request.accessToken: String?
        get() = header("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }

    private fun Request.withAccessToken(accessToken: String): Request =
        newBuilder()
            .header("Authorization", "Bearer $accessToken")
            .build()

    private fun Exception.isRefreshTokenRejected(): Boolean =
        this is HttpException && code() == 401
}
