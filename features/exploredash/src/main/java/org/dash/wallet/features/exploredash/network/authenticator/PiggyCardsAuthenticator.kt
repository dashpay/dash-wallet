/*
 * Copyright 2025 Dash Core Group.
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

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.LoginRequest
import org.dash.wallet.features.exploredash.network.service.piggycards.PiggyCardsTokenApi
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.utils.PiggyCardsConfig
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class PiggyCardsAuthenticator @Inject constructor(
    private val tokenApi: PiggyCardsTokenApi,
    private val config: PiggyCardsConfig
) : Authenticator {

    companion object {
        private val log = LoggerFactory.getLogger(PiggyCardsAuthenticator::class.java)

        // Shared across every PiggyCardsAuthenticator instance (the OkHttp retry path plus the
        // factory-built instance used by the repository) so concurrent re-logins are serialized
        // against the single shared token store, instead of racing on separate per-instance locks.
        private val tokenMutex = Mutex()
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.responseCount >= 2) {
            return null
        }

        return runBlocking {
            reLogin()?.let { accessToken ->
                response.request.newBuilder()
                    .header("Authorization", "Bearer $accessToken")
                    .build()
            }
        }
    }

    /**
     * Re-authenticates with the stored credentials under a process-wide lock and persists the new
     * access token, so the OkHttp retry path and [org.dash.wallet.features.exploredash.repository.PiggyCardsRepository]
     * never issue overlapping logins.
     *
     * @return the new access token, or null when no credentials are stored or the login failed.
     *   Stored credentials are preserved on transient failures so the session survives; only a
     *   genuine rejection (HTTP 401) clears the cached access token.
     */
    suspend fun reLogin(): String? = tokenMutex.withLock {
        val userId = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID)
        val password = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD)

        if (userId.isNullOrBlank() || password.isNullOrBlank()) {
            return@withLock null
        }

        try {
            val response = tokenApi.login(LoginRequest(userId = userId, password = password))
            persistLogin(response.accessToken, response.expiresIn)
            response.accessToken.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log.error("Failed to refresh token: ${e.message}", e)
            // Only a genuine credential rejection drops the cached token. The login endpoint
            // returns HTTP 401 for that case, surfaced by ErrorHandlingInterceptor as a
            // CTXSpendException; any other failure is transient, so the token is kept and the
            // next request retries instead of forcing the user to sign in again.
            if (e is CTXSpendException && e.errorCode == 401) {
                config.setSecuredData(PiggyCardsConfig.PREFS_KEY_ACCESS_TOKEN, "")
            }
            null
        }
    }

    private suspend fun persistLogin(accessToken: String, expiresIn: Int) {
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_ACCESS_TOKEN, accessToken)

        val expiresAt = LocalDateTime.now().plusSeconds(expiresIn.toLong())
        config.setSecuredData(
            PiggyCardsConfig.PREFS_KEY_TOKEN_EXPIRES_AT,
            expiresAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
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
}
