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
import org.dash.wallet.common.util.ResourceString
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.LoginRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.VerifyEmailRequest
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardStatus
import org.dash.wallet.features.exploredash.data.dashspend.model.UpdatedMerchantDetails
import org.dash.wallet.features.exploredash.network.authenticator.TokenAuthenticator
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.net.ssl.SSLHandshakeException

class CTXSpendException(
    message: String,
    val serviceName: String? = null,
    val errorCode: Int? = null,
    val errorBody: String? = null,
    cause: Throwable? = null
) : IOException(message, cause) {
    var resourceString: ResourceString? = null
    var giftCardResponse: GiftCardInfo? = null
    var txId: String? = null
    private val errorMap: Map<String, Any>

    constructor(message: ResourceString, giftCardResponse: GiftCardInfo? = null) : this("") {
        this.resourceString = message
        this.giftCardResponse = giftCardResponse
    }

    constructor(message: String, giftCardResponse: GiftCardInfo?, txId: String) : this(message) {
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

    val isLimitError: Boolean
        get() {
            val fiatAmount = ((errorMap["fields"] as? Map<*, *>)?.get("fiatAmount") as? List<*>)?.firstOrNull()
            return errorCode == 400 && (fiatAmount == "above threshold" || fiatAmount == "below threshold")
        }
    val isNetworkError: Boolean
        get() = cause?.let { it is SSLHandshakeException } ?: false
    val isRegionNotAllowed: Boolean
        get() = errorBody == "Client Transactions Not Allowed For This Region"
    override fun toString(): String {
        return "CTX error: $message\n  $giftCardResponse\n  $errorCode: $errorBody"
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

    override suspend fun orderGiftcard(
        cryptoCurrency: String,
        fiatCurrency: String,
        fiatAmount: String,
        merchantId: String
    ): GiftCardInfo {
        val response = purchaseGiftCard(
            merchantId = merchantId,
            fiatAmount = fiatAmount,
            fiatCurrency = fiatCurrency,
            cryptoCurrency = cryptoCurrency
        )

        return GiftCardInfo(
            response.id,
            response.merchantName,
            status = GiftCardStatus.valueOf(response.status.uppercase()),
            cryptoAmount = response.cryptoAmount,
            cryptoCurrency = response.cryptoCurrency,
            paymentCryptoNetwork = response.paymentCryptoNetwork,
            paymentId = response.paymentId,
            percentDiscount = response.percentDiscount,
            rate = response.rate,
            redeemUrl = response.redeemUrl,
            fiatAmount = response.fiatAmount,
            fiatCurrency = response.fiatCurrency,
            paymentUrls = response.paymentUrls,
        )
    }

    override suspend fun getGiftCard(giftCardId: String): GiftCardInfo? {
        val response = getGiftCardByTxid(giftCardId)

        return response?.let {
            GiftCardInfo(
                response.id,
                merchantName = response.merchantName,
                status = GiftCardStatus.valueOf(response.status.uppercase()),
                barcodeUrl = response.barcodeUrl,
                cardNumber = response.cardNumber,
                cardPin = response.cardPin,
                cryptoAmount = response.cryptoAmount,
                cryptoCurrency = response.cryptoCurrency,
                paymentCryptoNetwork = response.paymentCryptoNetwork,
                paymentId = response.paymentId,
                percentDiscount = response.percentDiscount,
                rate = response.rate,
                redeemUrl = response.redeemUrl,
                fiatAmount = response.fiatAmount,
                fiatCurrency = response.fiatCurrency,
                paymentUrls = response.paymentUrls,
            )
        }
    }

    override suspend fun getMerchant(merchantId: String): UpdatedMerchantDetails? {
        val response = api.getMerchant(merchantId)
        return response?.let {
            UpdatedMerchantDetails(
                id = response.id,
                savingsPercentage = response.savingsPercentage,
                denominationsType = response.denominationsType,
                denominations = response.denominations,
                redeemType = response.redeemType,
                enabled = response.enabled
            )
        }
    }

//    override suspend fun getMerchant(merchantId: String): GetMerchantResponse? =
//        api.getMerchant(merchantId)

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

    suspend fun getGiftCardByTxid(txid: String): GiftCardResponse?
    suspend fun refreshToken(): Boolean
}