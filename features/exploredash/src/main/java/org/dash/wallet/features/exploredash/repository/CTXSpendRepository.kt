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
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.safeApiCall
import org.dash.wallet.common.util.ResourceString
import org.dash.wallet.features.exploredash.data.ctxspend.model.GetMerchantResponse
import org.dash.wallet.features.exploredash.data.ctxspend.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.ctxspend.model.LoginRequest
import org.dash.wallet.features.exploredash.data.ctxspend.model.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.ctxspend.model.VerifyEmailRequest
import org.dash.wallet.features.exploredash.network.authenticator.TokenAuthenticator
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLHandshakeException

class CTXSpendException(
    message: String,
    val errorCode: Int? = null,
    val errorBody: String? = null,
    cause: Throwable? = null
) : Exception(message, cause) {
    var resourceString: ResourceString? = null
    var giftCardResponse: GiftCardResponse? = null
    var txId: String? = null
    private val errorMap: Map<String, Any>

    constructor(message: ResourceString, giftCardResponse: GiftCardResponse? = null) : this("") {
        this.resourceString = message
        this.giftCardResponse = giftCardResponse
    }

    constructor(message: String, giftCardResponse: GiftCardResponse?, txId: String) : this(message) {
        this.giftCardResponse = giftCardResponse
        this.txId = txId
    }

    init {
        errorMap = try {
            if (errorBody != null) {
                @Suppress("UNCHECKED_CAST")
                Gson().fromJson(errorBody, Map::class.java) as? Map<String, Any> ?: emptyMap()
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun toString(): String {
        return "CTX error: $message\n  $giftCardResponse\n  $errorCode: $errorBody"
    }

    val isLimitError: Boolean
        get() {
            val fiatAmount = ((errorMap["fields"] as? Map<*, *>)?.get("fiatAmount") as? List<*>)?.firstOrNull()
            return errorCode == 400 && (fiatAmount == "above threshold" || fiatAmount == "below threshold")
        }
    val isNetworkError: Boolean
        get() = cause?.let { it is SSLHandshakeException } ?: false
}

class CTXSpendRepository @Inject constructor(
    private val api: CTXSpendApi,
    private val config: CTXSpendConfig,
    private val tokenAuthenticator: TokenAuthenticator
) : CTXSpendRepositoryInt {
    companion object {
        private val ONE_DAY = TimeUnit.DAYS.toMillis(1)
    }
    override val userEmail: Flow<String?> = config.observeSecureData(CTXSpendConfig.PREFS_KEY_CTX_PAY_EMAIL)

    override suspend fun login(email: String): ResponseResource<Boolean> = safeApiCall {
        api.login(LoginRequest(email = email)).also {
            config.setSecuredData(CTXSpendConfig.PREFS_KEY_CTX_PAY_EMAIL, email)
            config.set(CTXSpendConfig.PREFS_DEVICE_UUID, UUID.randomUUID().toString())
        }
        true
    }

    override suspend fun verifyEmail(code: String): ResponseResource<Boolean> = safeApiCall {
        val email = config.getSecuredData(CTXSpendConfig.PREFS_KEY_CTX_PAY_EMAIL)
        api.verifyEmail(VerifyEmailRequest(email = email!!, code = code)).also {
            config.setSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN, it?.accessToken!!)
            config.setSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN, it?.refreshToken!!)
        }
        config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN)?.isNotEmpty() ?: false
    }

    override suspend fun isUserSignedIn() =
        config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN)?.isNotEmpty() ?: false

    override suspend fun getCTXSpendEmail() =
        config.getSecuredData(CTXSpendConfig.PREFS_KEY_CTX_PAY_EMAIL)

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
    ) = safeApiCall {
        api.purchaseGiftCard(
            purchaseGiftCardRequest = PurchaseGiftCardRequest(
                cryptoCurrency = "DASH",
                fiatCurrency = "USD",
                fiatAmount = fiatAmount,
                merchantId = merchantId
            )
        )
    }

    override suspend fun getMerchant(merchantId: String) =
        api.getMerchant(merchantId)

    override suspend fun getGiftCardByTxid(txid: String) = safeApiCall {
        api.getGiftCard(txid)
    }

    override suspend fun refreshToken(): Boolean {
        return when (val tokenResponse = tokenAuthenticator.getUpdatedToken()) {
            is ResponseResource.Success -> {
                tokenResponse.value?.let {
                    config.setSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN, it.accessToken ?: "")
                    config.setSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN, it.refreshToken ?: "")
                    true
                } ?: false
            }

            else -> {
                config.setSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN, "")
                config.setSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN, "")
                false
            }
        }
    }
}

interface CTXSpendRepositoryInt {
    val userEmail: Flow<String?>
    suspend fun login(email: String): ResponseResource<Boolean>
    suspend fun verifyEmail(code: String): ResponseResource<Boolean>
    suspend fun isUserSignedIn(): Boolean
    suspend fun getCTXSpendEmail(): String?
    suspend fun logout()
    suspend fun purchaseGiftCard(
        cryptoCurrency: String,
        fiatCurrency: String,
        fiatAmount: String,
        merchantId: String
    ): ResponseResource<GiftCardResponse?>

    suspend fun getMerchant(merchantId: String): GetMerchantResponse?
    suspend fun getGiftCardByTxid(txid: String): ResponseResource<GiftCardResponse?>
    suspend fun refreshToken(): Boolean
}
