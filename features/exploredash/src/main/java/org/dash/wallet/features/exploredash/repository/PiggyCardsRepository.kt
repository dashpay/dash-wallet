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

package org.dash.wallet.features.exploredash.repository

import kotlinx.coroutines.flow.Flow
import org.dash.wallet.features.exploredash.data.piggycards.model.*
import org.dash.wallet.features.exploredash.network.service.piggycards.PiggyCardsApi
import org.dash.wallet.features.exploredash.utils.PiggyCardsConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class PiggyCardsRepository @Inject constructor(
    private val api: PiggyCardsApi,
    private val config: PiggyCardsConfig
) : DashSpendRepository {

    override val userEmail: Flow<String?> = config.observeSecureData(PiggyCardsConfig.PREFS_KEY_EMAIL)

    override suspend fun login(email: String): Boolean {
        val existingUserId = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID)
        val existingPassword = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD)
        
        return if (existingUserId != null && existingPassword != null) {
            val response = api.login(LoginRequest(userId = existingUserId, password = existingPassword))
            handleLoginResponse(response)
        } else {
            false
        }
    }

    override suspend fun signup(email: String): Boolean {
        val response = api.signup(
            SignupRequest(
                firstName = "",
                lastName = "",
                email = email,
                country = "US",
                state = "AZ"
            )
        )
        
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID, response.userId)
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_EMAIL, email)
        
        return true
    }

    override suspend fun verifyEmail(code: String): Boolean {
        val email = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_EMAIL)
        val response = api.verifyOtp(VerifyOtpRequest(email = email!!, otp = code))
        
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD, response.generatedPassword)
        
        return performAutoLogin()
    }

    private suspend fun performAutoLogin(): Boolean {
        val userId = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID)
        val password = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD)
        
        if (userId != null && password != null) {
            val response = api.login(LoginRequest(userId = userId, password = password))
            return handleLoginResponse(response)
        }
        return false
    }

    private suspend fun handleLoginResponse(response: LoginResponse): Boolean {
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_ACCESS_TOKEN, response.accessToken)
        
        val expiresAt = LocalDateTime.now().plusSeconds(response.expiresIn.toLong())
        config.setSecuredData(
            PiggyCardsConfig.PREFS_KEY_TOKEN_EXPIRES_AT,
            expiresAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
        
        return response.accessToken.isNotEmpty()
    }

    override suspend fun isUserSignedIn(): Boolean {
        val token = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_ACCESS_TOKEN)
        val expiresAt = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_TOKEN_EXPIRES_AT)
        
        if (token.isNullOrEmpty() || expiresAt == null) return false
        
        val expireTime = LocalDateTime.parse(expiresAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val isExpired = LocalDateTime.now().isAfter(expireTime)
        
        return if (isExpired) {
            refreshToken()
        } else {
            true
        }
    }

    override suspend fun logout() {
        config.clearAll()
    }

    override suspend fun refreshToken(): Boolean {
        return performAutoLogin()
    }
}
