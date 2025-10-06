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
    }

    // For multiple call to refresh token sync
    private val tokenMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        return runBlocking {
            tokenMutex.withLock {
                try {
                    val loginResponse = refreshToken()
                    if (loginResponse != null) {
                        handleLoginResponse(loginResponse)
                        response.request.newBuilder()
                            .header("Authorization", "Bearer ${loginResponse.accessToken}")
                            .build()
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    log.error("Failed to refresh token: ${e.message}", e)
                    config.setSecuredData(PiggyCardsConfig.PREFS_KEY_ACCESS_TOKEN, "")
                    null
                }
            }
        }
    }

    private suspend fun refreshToken(): org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.LoginResponse? {
        val userId = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID)
        val password = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD)
        
        return if (!userId.isNullOrBlank() && !password.isNullOrBlank()) {
            tokenApi.login(LoginRequest(userId = userId, password = password))
        } else {
            null
        }
    }

    private suspend fun handleLoginResponse(response: org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.LoginResponse) {
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_ACCESS_TOKEN, response.accessToken)
        
        val expiresAt = LocalDateTime.now().plusSeconds(response.expiresIn.toLong())
        config.setSecuredData(
            PiggyCardsConfig.PREFS_KEY_TOKEN_EXPIRES_AT,
            expiresAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }
}