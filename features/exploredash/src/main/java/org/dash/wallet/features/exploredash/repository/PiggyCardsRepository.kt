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
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.safeApiCall
import org.dash.wallet.features.exploredash.data.piggycards.model.*
import org.dash.wallet.features.exploredash.network.service.piggycards.PiggyCardsApi
import org.dash.wallet.features.exploredash.utils.PiggyCardsConfig
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class PiggyCardsRepository @Inject constructor(
    private val api: PiggyCardsApi,
    private val config: PiggyCardsConfig
) : PiggyCardsRepositoryInt {

    override val userEmail: Flow<String?> = config.observeSecureData(PiggyCardsConfig.PREFS_KEY_EMAIL)

    override suspend fun login(email: String): ResponseResource<Boolean> = safeApiCall {
        val existingUserId = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID)
        val existingPassword = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD)
        
        if (existingUserId != null && existingPassword != null) {
            val response = api.login(LoginRequest(userId = existingUserId, password = existingPassword))
            handleLoginResponse(response)
        } else {
            false
        }
    }

    override suspend fun signup(
        firstName: String,
        lastName: String,
        email: String,
        country: String,
        state: String?
    ): ResponseResource<Boolean> = safeApiCall {
        val response = api.signup(
            SignupRequest(
                firstName = firstName,
                lastName = lastName,
                email = email,
                country = country,
                state = state
            )
        )
        
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID, response.userId)
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_EMAIL, email)
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_FIRST_NAME, firstName)
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_LAST_NAME, lastName)
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_COUNTRY, country)
        state?.let { config.setSecuredData(PiggyCardsConfig.PREFS_KEY_STATE, it) }
        
        true
    }

    override suspend fun verifyEmail(code: String): ResponseResource<Boolean> = safeApiCall {
        val email = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_EMAIL)
        val response = api.verifyOtp(VerifyOtpRequest(email = email!!, otp = code))
        
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD, response.generatedPassword)
        
        performAutoLogin()
    }

    private suspend fun performAutoLogin(): Boolean {
        val userId = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID)
        val password = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD)
        
        if (userId != null && password != null) {
            return try {
                val response = api.login(LoginRequest(userId = userId, password = password))
                handleLoginResponse(response)
            } catch (e: Exception) {
                false
            }
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

    override suspend fun getEmail(): String? =
        config.getSecuredData(PiggyCardsConfig.PREFS_KEY_EMAIL)

    override suspend fun logout() {
        config.clearAll()
    }

    override suspend fun refreshToken(): Boolean {
        return performAutoLogin()
    }

    override suspend fun getMerchants(country: String): ResponseResource<List<Merchant>> = safeApiCall {
        api.getMerchants(country)
    }

    override suspend fun getMerchant(merchantId: String): ResponseResource<MerchantDetails> = safeApiCall {
        api.getMerchant(merchantId)
    }

    override suspend fun getMerchantLocations(merchantId: String): ResponseResource<List<Location>> = safeApiCall {
        api.getMerchantLocations(merchantId)
    }

    override suspend fun createOrder(
        merchantId: String,
        amount: Double,
        email: String?,
        name: String?,
        ipAddress: String?,
        location: String?
    ): ResponseResource<OrderResponse> = safeApiCall {
        val userEmail = email ?: getEmail() ?: ""
        val userName = name ?: "${config.getSecuredData(PiggyCardsConfig.PREFS_KEY_FIRST_NAME)} ${config.getSecuredData(PiggyCardsConfig.PREFS_KEY_LAST_NAME)}"
        
        api.createOrder(
            OrderRequest(
                merchantId = merchantId,
                amount = amount,
                email = userEmail,
                name = userName,
                ipAddress = ipAddress,
                location = location
            )
        )
    }

    override suspend fun getOrderStatus(orderId: String): ResponseResource<OrderStatusResponse> = safeApiCall {
        api.getOrderStatus(orderId)
    }

    override suspend fun isNewUser(): Boolean {
        val userId = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID)
        return userId.isNullOrEmpty()
    }
}

interface PiggyCardsRepositoryInt {
    val userEmail: Flow<String?>
    
    suspend fun login(email: String): ResponseResource<Boolean>
    suspend fun signup(
        firstName: String,
        lastName: String,
        email: String,
        country: String,
        state: String?
    ): ResponseResource<Boolean>
    suspend fun verifyEmail(code: String): ResponseResource<Boolean>
    suspend fun isUserSignedIn(): Boolean
    suspend fun getEmail(): String?
    suspend fun logout()
    suspend fun refreshToken(): Boolean
    suspend fun isNewUser(): Boolean
    
    suspend fun getMerchants(country: String): ResponseResource<List<Merchant>>
    suspend fun getMerchant(merchantId: String): ResponseResource<MerchantDetails>
    suspend fun getMerchantLocations(merchantId: String): ResponseResource<List<Location>>
    suspend fun createOrder(
        merchantId: String,
        amount: Double,
        email: String? = null,
        name: String? = null,
        ipAddress: String? = null,
        location: String? = null
    ): ResponseResource<OrderResponse>
    suspend fun getOrderStatus(orderId: String): ResponseResource<OrderStatusResponse>
}