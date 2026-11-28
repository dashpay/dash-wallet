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
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.ResourceString
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GetMerchantResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.LoginRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.VerifyEmailRequest
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardStatus
import org.dash.wallet.features.exploredash.data.dashspend.model.UnusableMerchantDetailsException
import org.dash.wallet.features.exploredash.data.dashspend.model.UpdatedMerchantDetails
import org.dash.wallet.features.exploredash.network.authenticator.TokenAuthenticator
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardShoppingCart
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.dash.wallet.features.exploredash.utils.GiftCardCurrency
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
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

    val isOutOfStock: Boolean
        get() {
            val message = errorMap["message"] as? String ?: ""
            val outOfStockRegex = Regex("Gift card \\d+ is out of stock")
            return errorCode == 500 && outOfStockRegex.matches(message) || outOfStockRegex.matches(errorBody ?: "")
        }

    val orderId: String?
        get() = giftCardResponse?.id

    override fun toString(): String {
        return "DashSpend error: $message\n  $giftCardResponse\n  $errorCode: $errorBody"
    }
}

class CTXSpendRepository @Inject constructor(
    private val api: CTXSpendApi,
    private val config: CTXSpendConfig,
    private val tokenAuthenticator: TokenAuthenticator
) : DashSpendRepository {
    companion object {
        private val REFRESH_TOKEN_EXPIRATION = TimeUnit.DAYS.toMillis(90)
        private val log = LoggerFactory.getLogger(CTXSpendRepository::class.java)
    }
    private val giftCardMap = hashMapOf<String, GetMerchantResponse>()

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
        val accessToken = response?.accessToken
        val refreshToken = response?.refreshToken

        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            return false
        }

        config.saveTokenState(accessToken, refreshToken)
        return config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN)?.isNotEmpty() ?: false
    }

    override suspend fun isUserSignedIn() =
        config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN)?.isNotEmpty() ?: false

    override suspend fun logout() {
        config.clearAll()
    }

    suspend fun reset() {
        config.clearTokenState()
    }

    suspend fun purchaseGiftCard(
        cryptoCurrency: String,
        fiatCurrency: String,
        fiatAmount: String,
        merchantId: String
    ): GiftCardResponse {
        return api.purchaseGiftCard(
            purchaseGiftCardRequest = PurchaseGiftCardRequest(
                cryptoCurrency = "DASH",
                fiatCurrency = fiatCurrency,
                fiatAmount = fiatAmount,
                merchantId = merchantId
            )
        )
    }

    override suspend fun orderGiftcard(
        cryptoCurrency: String,
        fiatCurrency: String,
        order: GiftCardShoppingCart,
        merchantId: String
    ): List<GiftCardInfo> {
        val fiatAmount = GiftCardCurrency.orderAmount(order.first().value, fiatCurrency)
        val response = purchaseGiftCard(
            merchantId = merchantId,
            fiatAmount = fiatAmount,
            fiatCurrency = fiatCurrency,
            cryptoCurrency = cryptoCurrency
        )
        // the Dash quoted in this response is paid as it stands, so it must be for the card ordered
        listOfNotNull(response.fiatCurrency, response.cardFiatCurrency)
            .firstOrNull { it.isNotBlank() && !it.equals(fiatCurrency, ignoreCase = true) }
            ?.let { reported ->
                log.error("gift card order came back in {} but {} was ordered", reported, fiatCurrency)
                throw CTXSpendException(ResourceString(R.string.gift_card_error))
            }
        if (!response.cardFiatAmount.isNullOrBlank() &&
            response.cardFiatAmount.trim().toBigDecimalOrNull()?.compareTo(fiatAmount.toBigDecimal()) != 0
        ) {
            log.error("gift card order came back for {} but {} was ordered", response.cardFiatAmount, fiatAmount)
            throw CTXSpendException(ResourceString(R.string.gift_card_error))
        }
        // USD is what CTX always sold, so only there may an answer leave the currency out
        if (!fiatCurrency.equals(Constants.USD_CURRENCY, ignoreCase = true) &&
            response.fiatCurrency.isNullOrBlank() && response.cardFiatCurrency.isNullOrBlank()
        ) {
            log.error("gift card order for {} came back without a currency", fiatCurrency)
            throw CTXSpendException(ResourceString(R.string.gift_card_error))
        }

        return listOf(
            GiftCardInfo(
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
                fiatCurrency = response.fiatCurrency?.takeIf { it.isNotBlank() } ?: fiatCurrency,
                paymentUrls = response.paymentUrls,
                paymentUrlExpiration = response.paymentUrlExpiration,
                created = response.created
            )
        )
    }

    override suspend fun getGiftCard(giftCardId: String): List<GiftCardInfo> {
        val response = getGiftCardByOrderId2(giftCardId)

        return response?.let { card ->
            listOf(
                GiftCardInfo(
                    card.id,
                    merchantName = card.merchantName,
                    status = GiftCardStatus.valueOf(card.status.uppercase()),
                    barcodeUrl = card.barcodeUrl,
                    cardNumber = card.cardNumber,
                    cardPin = card.cardPin,
                    cryptoAmount = card.cryptoAmount,
                    cryptoCurrency = card.cryptoCurrency,
                    paymentCryptoNetwork = card.paymentCryptoNetwork,
                    paymentId = card.paymentId,
                    percentDiscount = card.percentDiscount,
                    rate = card.rate,
                    redeemUrl = card.redeemUrl,
                    redeemUrlChallenge = card.redeemUrlChallenge,
                    fiatAmount = card.fiatAmount,
                    fiatCurrency = card.fiatCurrency,
                    paymentUrls = card.paymentUrls
                )
            )
        } ?: listOf()

//        return response?.result?.map { card ->
//            GiftCardInfo(
//                card.id,
//                merchantName = card.merchantName,
//                status = GiftCardStatus.valueOf(card.status.uppercase()),
//                barcodeUrl = card.barcodeUrl,
//                cardNumber = card.cardNumber,
//                cardPin = card.cardPin,
//                cryptoAmount = card.cryptoAmount,
//                cryptoCurrency = card.cryptoCurrency,
//                paymentCryptoNetwork = card.paymentCryptoNetwork,
//                paymentId = card.paymentId,
//                percentDiscount = card.percentDiscount,
//                rate = card.rate,
//                redeemUrl = card.redeemUrl,
//                redeemUrlChallenge = card.redeemUrlChallenge,
//                fiatAmount = card.fiatAmount,
//                fiatCurrency = card.fiatCurrency,
//                paymentUrls = card.paymentUrls
//            )
//        } ?: listOf()
    }

    override suspend fun getGiftCardByTxId(giftCardId: String): List<GiftCardInfo> {
        val response = _getGiftCardByTxId(giftCardId)

        return response?.let {
            listOf(
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
                    redeemUrlChallenge = response.redeemUrlChallenge,
                    fiatAmount = response.fiatAmount,
                    fiatCurrency = response.fiatCurrency,
                    paymentUrls = response.paymentUrls
                )
            )
        } ?: listOf()
    }

    override suspend fun getMerchant(merchantId: String): UpdatedMerchantDetails? {
        val response = try {
            api.getMerchant(merchantId)
        } catch (e: JsonParseException) {
            // CTX answered, with a body that cannot be decoded
            throw UnusableMerchantDetailsException(e)
        } catch (e: MalformedJsonException) {
            throw UnusableMerchantDetailsException(e)
        }
        return response?.let {
            giftCardMap[merchantId] = it
            if (response.denominations.isEmpty()) {
                throw UnusableMerchantDetailsException(IllegalStateException("no denominations"), true)
            }
            try {
                // read as decimals: toDouble() takes "NaN", and toFiat() would round 10.00001 into 10
                val currency = GiftCardCurrency.normalize(response.currency)
                val amounts = response.denominations.map { denom -> denom.trim().toBigDecimal() }
                require(amounts.all { amount -> GiftCardCurrency.isCardAmount(amount, currency) })
                UpdatedMerchantDetails(
                    id = response.id,
                    savingsPercentage = response.discount,
                    denominationsType = response.denominationsType,
                    denominations = amounts.map { amount -> amount.toDouble() },
                    redeemType = response.redeemType,
                    enabled = response.enabled,
                    currency = response.currency
                ).also { details ->
                    require(details.denominations.any { it > 0.0 })
                }
            } catch (e: Exception) {
                throw UnusableMerchantDetailsException(e)
            }
        }
    }

    suspend fun checkToken(): Boolean {
        val refreshTokenTime = config.get(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN_TIME)
        val refreshToken = config.getSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN)

        return if (refreshToken.isNullOrBlank()) {
            reset()
            false
        } else if (refreshTokenTime == null) {
            refreshToken()
        } else if ((System.currentTimeMillis() - refreshTokenTime) < REFRESH_TOKEN_EXPIRATION) {
            true
        } else {
            refreshToken()
        }
    }

//    override suspend fun getMerchant(merchantId: String): GetMerchantResponse? =
//        api.getMerchant(merchantId)

    private suspend fun _getGiftCardByTxId(txid: String): GiftCardResponse? {
        return api.getGiftCardByTxId(txid)
    }

    private suspend fun getGiftCardByOrderId2(orderId: String): GiftCardResponse? {
        return api.getGiftCardByOrderId(orderId)
    }

    override suspend fun refreshToken(): Boolean {
        // Delegate to the authenticator so this shares the same process-wide lock and token
        // persistence as the OkHttp retry path, avoiding overlapping refreshes that could
        // clear a token another caller just rotated.
        return tokenAuthenticator.refreshAccessToken() != null
    }

    suspend fun getCTXSpendEmail(): String? {
        return config.getSecuredData(CTXSpendConfig.PREFS_KEY_CTX_PAY_EMAIL)
    }

    override fun getGiftCardDiscount(merchantId: String, denomination: Double): Double {
        return giftCardMap[merchantId]?.discount?.let {
            it.toDouble() / 10000.0
        } ?: 0.0
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
