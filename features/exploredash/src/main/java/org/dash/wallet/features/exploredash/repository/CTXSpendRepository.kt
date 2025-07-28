/*
 * Copyright 2023 Dash Core Group.
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

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.util.ResourceString
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GetMerchantResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.LoginRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.VerifyEmailRequest
import org.dash.wallet.features.exploredash.network.authenticator.TokenAuthenticator
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import java.util.UUID
import javax.inject.Inject

class CTXSpendException(
    message: String,
    val errorCode: Int? = null,
    val errorBody: String? = null
) : Exception(message) {
    var resourceString: ResourceString? = null
    private val errorMap: Map<String, Any>

    constructor(message: ResourceString) : this("") {
        this.resourceString = message
    }

    init {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        errorMap = try {
            if (errorBody != null) {
                Gson().fromJson(errorBody, type) ?: emptyMap()
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    val isLimitError: Boolean
        get() {
            val fiatAmount = ((errorMap["fields"] as? Map<*, *>)?.get("fiatAmount") as? List<*>)?.firstOrNull()
            return errorCode == 400 && (fiatAmount == "above threshold" || fiatAmount == "below threshold")
        }
}

class CTXSpendRepository @Inject constructor(
    private val api: CTXSpendApi,
    private val config: CTXSpendConfig,
    private val tokenAuthenticator: TokenAuthenticator
) : CTXSpendRepositoryInt, DashSpendRepository {
    override val userEmail: Flow<String?> = config.observeSecureData(CTXSpendConfig.PREFS_KEY_CTX_PAY_EMAIL)

    override suspend fun signup(email: String) = login(email)

    override suspend fun login(email: String): Boolean {
        api.login(LoginRequest(email = email))
        config.setSecuredData(CTXSpendConfig.PREFS_KEY_CTX_PAY_EMAIL, email)
        config.set(CTXSpendConfig.PREFS_DEVICE_UUID, UUID.randomUUID().toString())
        return true
    }

    override suspend fun verifyEmail(code: String): Boolean {
        val email = config.getSecuredData(CTXSpendConfig.PREFS_KEY_CTX_PAY_EMAIL)
        val response = api.verifyEmail(VerifyEmailRequest(email = email!!, code = code))
        config.setSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN, response?.accessToken!!)
        config.setSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN, response?.refreshToken!!)
        return config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN)?.isNotEmpty() ?: false
    }

    override suspend fun isUserSignedIn() =
        config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN)?.isNotEmpty() ?: false

    override suspend fun logout() {
        config.clearAll()
    }

    suspend fun reset() {
        config.setSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN, "")
        config.setSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN, "")
    }

    override suspend fun purchaseGiftCard(
        cryptoCurrency: String,
        fiatCurrency: String,
        fiatAmount: String,
        merchantId: String
    ): GiftCardResponse {
        return api.purchaseGiftCard(
            purchaseGiftCardRequest = PurchaseGiftCardRequest(
                cryptoCurrency = "DASH",
                fiatCurrency = "USD",
                fiatAmount = fiatAmount,
                merchantId = merchantId
            )
        )
    }

    override suspend fun getMerchant(merchantId: String): GetMerchantResponse? =
        api.getMerchant(merchantId)

    override suspend fun getGiftCardByTxid(txid: String): GiftCardResponse? {
        return api.getGiftCard(txid)
    }

    override suspend fun refreshToken(): Boolean {
        return try {
            val tokenResponse = tokenAuthenticator.getUpdatedToken()
            tokenResponse?.let {
                config.setSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN, it.accessToken ?: "")
                config.setSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN, it.refreshToken ?: "")
                true
            } ?: false
        } catch (e: Exception) {
            config.setSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN, "")
            config.setSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN, "")
            false
        }
    }

    suspend fun getCTXSpendEmail(): String? {
        return config.getSecuredData(CTXSpendConfig.PREFS_KEY_CTX_PAY_EMAIL)
    }
}

interface CTXSpendRepositoryInt {
    suspend fun purchaseGiftCard(
        cryptoCurrency: String,
        fiatCurrency: String,
        fiatAmount: String,
        merchantId: String
    ): GiftCardResponse?

    suspend fun getMerchant(merchantId: String): GetMerchantResponse?
    suspend fun getGiftCardByTxid(txid: String): GiftCardResponse?
    suspend fun refreshToken(): Boolean
}