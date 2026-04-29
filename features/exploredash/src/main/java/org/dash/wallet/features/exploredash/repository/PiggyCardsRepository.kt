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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.uri.BitcoinURIParseException
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.util.Constants
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.DenominationType
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardStatus
import org.dash.wallet.features.exploredash.data.dashspend.model.UpdatedMerchantDetails
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.Brand
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.ExchangeRateResult
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.Giftcard
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.LoginRequest
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.LoginResponse
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.Order
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.OrderRequest
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.OrderResponse
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.SignupRequest
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.User
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.UserMetadata
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.VerifyOtpRequest
import org.dash.wallet.features.exploredash.network.service.piggycards.PiggyCardsApi
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardShoppingCart
import org.dash.wallet.features.exploredash.utils.PiggyCardsConfig
import org.slf4j.LoggerFactory
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.max

class PiggyCardsRepository @Inject constructor(
    private val api: PiggyCardsApi,
    private val config: PiggyCardsConfig
) : DashSpendRepository {
    companion object {
        const val DEFAULT_COUNTRY = "US"
        const val DEFAULT_STATE = "CA"
        private val log = LoggerFactory.getLogger(PiggyCardsRepository::class.java)
        private val disabledMerchants = listOf<String>()
        val disabledGiftCards = mapOf(
            174 to listOf("Xbox Live", "Xbox Game Pass")
        )
        private const val INSTANT_DELIVERY = "(instant delivery)"
        private const val SERVICE_FEE = 150
        private const val SERVICE_FEE_PERCENT = 1.5
        private const val DASH_DASH_KEY = "DASH.DASH"
    }
    private val giftCardMap = hashMapOf<String, List<Giftcard>>()
    private val exchangeRateMap = hashMapOf<String, ExchangeRateResult>()

    override val userEmail: Flow<String?> = config.observeSecureData(PiggyCardsConfig.PREFS_KEY_EMAIL)

    override suspend fun login(email: String) = signup(email)

    override suspend fun signup(email: String): Boolean {
        val response = api.signup(
            SignupRequest(
                firstName = "",
                lastName = "",
                email = email,
                country = DEFAULT_COUNTRY,
                state = DEFAULT_STATE
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
        return try {
            val userId = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID)
            val password = config.getSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD)

            if (userId != null && password != null) {
                val response = api.login(LoginRequest(userId = userId, password = password))
                handleLoginResponse(response)
            } else {
                false
            }
        } catch (e: Exception) {
            log.error("Failed to perform auto login: ${e.message}", e)
            false
        }
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

    override suspend fun getMerchant(merchantId: String): UpdatedMerchantDetails? {
        val brandList = api.getBrands(DEFAULT_COUNTRY)
        val brandWithMerchantId = brandList.find { it.id == merchantId }
        brandWithMerchantId?.let { it ->
            val allGiftCards = api.getGiftCards(DEFAULT_COUNTRY, it.id)
            val disabledGiftCardsForMerchant = disabledGiftCards[brandWithMerchantId.id.toInt()].orEmpty()
            val giftcards = if (disabledGiftCardsForMerchant.isEmpty()) {
                allGiftCards?.data
            } else {
                allGiftCards?.data?.filter { giftCard ->
                    !disabledGiftCardsForMerchant.any { name -> giftCard.name.contains(name) }
                }
            }

            val immediateDeliveryCards = giftcards?.filter { giftCard ->
                giftCard.name.lowercase().contains(INSTANT_DELIVERY) && giftCard.quantity > 0
            }

            val nonImmediateDeliveryFixedCards = giftcards?.filter { giftCard ->
                !giftCard.name.lowercase().contains(INSTANT_DELIVERY) && giftCard.quantity > 0 && giftCard.isFixed
            } ?: listOf()

            // do we have a range card
            val rangeGiftCard = giftcards?.find { !it.isFixed }

            // do we have an option card
            val optionCard = giftcards?.find { it.isOption }

            // priority:
            // 1. Instant Delivery Fixed Value Cards and all other fixed delivery
            // 2. Flexible Value Cards
            // 3. Fixed value cards
            if (!immediateDeliveryCards.isNullOrEmpty()) {
                val allFixedCards = immediateDeliveryCards.plus(nonImmediateDeliveryFixedCards)
                return updatedMerchantDetails(allFixedCards, brandWithMerchantId)
            } else if (rangeGiftCard != null) {
                val denominations = listOf(
                    rangeGiftCard.minDenomination,
                    rangeGiftCard.maxDenomination
                )
                val denominationsType = "min-max"
                val discountPercentage = (rangeGiftCard.discountPercentage * 100).toInt() - SERVICE_FEE
                giftCardMap[it.id] = listOf(rangeGiftCard)
                return UpdatedMerchantDetails(
                    rangeGiftCard.brandId.toString(),
                    denominations,
                    denominationsType,
                    discountPercentage,
                    redeemType = "barcode",
                    enabled = rangeGiftCard.quantity > 0 && !disabledMerchants.contains(rangeGiftCard.name),
                    productId = rangeGiftCard.id.toString()
                )
            } else if (optionCard != null) {
                val denominations = optionCard.denomination.split(",").map { it.toDouble() }
                val denominationsType = "fixed"
                val discountPercentage = (optionCard.discountPercentage * 100).toInt() - SERVICE_FEE
                giftCardMap[it.id] = listOf(optionCard)
                return UpdatedMerchantDetails(
                    optionCard.brandId.toString(),
                    denominations,
                    denominationsType,
                    discountPercentage,
                    redeemType = "barcode",
                    enabled = optionCard.quantity > 0 && !disabledMerchants.contains(optionCard.name),
                    productId = optionCard.id.toString()
                )
            } else if (!giftcards.isNullOrEmpty()) {
                return updatedMerchantDetails(
                    nonImmediateDeliveryFixedCards,
                    it
                )
            } else {
                return UpdatedMerchantDetails(
                    it.id,
                    listOf(),
                    "fixed",
                    0,
                    redeemType = "",
                    enabled = false
                )
            }
        }
        return UpdatedMerchantDetails(
            merchantId,
            listOf(0.0),
            "fixed",
            0,
            redeemType = "",
            enabled = false
        )
    }

    suspend fun getMerchant(merchantId: String, type: DenominationType): UpdatedMerchantDetails? {
        val brandList = api.getBrands(DEFAULT_COUNTRY)
        val brandWithMerchantId = brandList.find { it.id == merchantId }
        brandWithMerchantId?.let { it ->
            val allGiftCards = api.getGiftCards(DEFAULT_COUNTRY, it.id)
            val disabledGiftCardsForMerchant = disabledGiftCards[brandWithMerchantId.id.toInt()].orEmpty()
            val giftcards = if (disabledGiftCardsForMerchant.isEmpty()) {
                allGiftCards?.data
            } else {
                allGiftCards?.data?.filter { giftCard ->
                    !disabledGiftCardsForMerchant.any { name -> giftCard.name.contains(name) }
                }
            }
            when (type) {
                is DenominationType.Fixed -> {
                    val immediateDeliveryCards = giftcards?.filter { giftCard ->
                        giftCard.name.lowercase().contains(INSTANT_DELIVERY) && giftCard.quantity > 0
                    } ?: listOf()

                    val nonImmediateDeliveryFixedCards = giftcards?.filter { giftCard ->
                        !giftCard.name.lowercase()
                            .contains(INSTANT_DELIVERY) && giftCard.quantity > 0 && giftCard.isFixed
                    } ?: listOf()

                    val allFixedCards = immediateDeliveryCards.plus(nonImmediateDeliveryFixedCards)

                    if (allFixedCards.isNotEmpty()) {
                        return updatedMerchantDetails(allFixedCards, brandWithMerchantId)
                    }

                    // is this an option card, if not fixed
                    val optionCard = giftcards?.find { it.isOption }
                    if (optionCard != null) {
                        val denominations = optionCard.denomination.split(",").map { it.toDouble() }
                        val denominationsType = "fixed"
                        val discountPercentage = (optionCard.discountPercentage * 100).toInt() - SERVICE_FEE
                        giftCardMap[it.id] = listOf(optionCard)
                        return UpdatedMerchantDetails(
                            optionCard.brandId.toString(),
                            denominations,
                            denominationsType,
                            discountPercentage,
                            redeemType = "barcode",
                            enabled = optionCard.quantity > 0 && !disabledMerchants.contains(optionCard.name),
                            productId = optionCard.id.toString()
                        )
                    }
                }
                is DenominationType.MinMax -> {
                    // do we have a range card
                    giftcards?.find { !it.isFixed }?.let { rangeGiftCard ->
                        val denominations = listOf(
                            rangeGiftCard.minDenomination,
                            rangeGiftCard.maxDenomination
                        )
                        val denominationsType = "min-max"
                        val discountPercentage = (rangeGiftCard.discountPercentage * 100).toInt() - SERVICE_FEE
                        giftCardMap[it.id] = listOf(rangeGiftCard)
                        return UpdatedMerchantDetails(
                            rangeGiftCard.brandId.toString(),
                            denominations,
                            denominationsType,
                            discountPercentage,
                            redeemType = "barcode",
                            enabled = rangeGiftCard.quantity > 0 && !disabledMerchants.contains(rangeGiftCard.name),
                            productId = rangeGiftCard.id.toString()
                        )
                    }
                }
                else -> return UpdatedMerchantDetails(
                    it.id,
                    listOf(),
                    "fixed",
                    0,
                    redeemType = "",
                    enabled = false
                )
            }
        }
        return UpdatedMerchantDetails(
            merchantId,
            listOf(0.0),
            "fixed",
            0,
            redeemType = "",
            enabled = false
        )
    }

    private fun updatedMerchantDetails(
        data: List<Giftcard>,
        it: Brand
    ): UpdatedMerchantDetails {
        val denominations = arrayListOf<Double>()
        var discountPercentage = -100.0 // very low number for max
        val activeGiftCards = arrayListOf<Giftcard>()
        data.forEach { card ->
            if (card.quantity > 0) {
                denominations.add(card.denomination.toDouble())
                discountPercentage = max(card.discountPercentage, discountPercentage)
                activeGiftCards.add(card)
            }
        }
        val denominationsType = "fixed"
        giftCardMap[it.id] = activeGiftCards
        // val format = DecimalFormat("0.##")
        return UpdatedMerchantDetails(
            it.id,
            denominations.sorted(),
            denominationsType,
            (discountPercentage * 100).toInt() - SERVICE_FEE,
            redeemType = "barcode",
            enabled = denominations.isNotEmpty() && !disabledMerchants.contains(
                activeGiftCards.firstOrNull()?.name ?: ""
            )
        )
    }

    private suspend fun purchaseGiftCard(
        merchantId: String,
        order: GiftCardShoppingCart,
        fiatCurrency: String
    ): OrderResponse {
        val userEmail = userEmail.first()!!
        val giftCards = giftCardMap[merchantId]
        val thisOrder = order.copy()
        if (!giftCards.isNullOrEmpty()) {
            val optionGiftcard = giftCards.find { it.isOption }
            val rangeGiftCard = giftCards.find { it.isRange }
            if (rangeGiftCard != null) {
                thisOrder.forEach { orderItem ->
                    orderItem.productId = rangeGiftCard.id
                }
            } else if (optionGiftcard != null) {
                // there is probably a bug here, but there are no option cards to test
                thisOrder.first().productId = optionGiftcard.id
            } else {
                thisOrder.forEach { orderItem ->
                    val card = giftCards.find {
                        it.quantity > 0 && it.name.contains(INSTANT_DELIVERY) &&
                                it.denomination.toBigDecimal().compareTo(orderItem.value.toBigDecimal()) == 0
                    } ?: giftCards.find {
                        it.quantity > 0 && it.denomination.toBigDecimal().compareTo(orderItem.value.toBigDecimal()) == 0
                    }
                    card?.id ?: throw IllegalStateException(
                        "cannot find the selected fixed card ${orderItem.value} for brand $merchantId"
                    )
                    orderItem.productId = card.id
                }
            }
            return api.createOrder(
                OrderRequest(
                    thisOrder.map { orderItem ->
                        Order(
                            productId = orderItem.productId,
                            quantity = orderItem.quantity,
                            denomination = orderItem.value,
                            currency = fiatCurrency
                        )
                    },
                    recipientEmail = userEmail,
                    user = User(
                        name = "none",
                        ip = "192.168.100.1",
                        UserMetadata(
                            "2025-07-01",
                            country = DEFAULT_COUNTRY,
                            state = DEFAULT_STATE
                        )
                    )
                )
            )
        } else {
            throw IllegalStateException("card data not found: $merchantId")
        }
    }

    override suspend fun orderGiftcard(
        cryptoCurrency: String,
        fiatCurrency: String,
        order: GiftCardShoppingCart,
        merchantId: String
    ): List<GiftCardInfo> {
        val orderResponse = purchaseGiftCard(merchantId, order, fiatCurrency)
        val rate = api.getExchangeRate(fiatCurrency)
        exchangeRateMap[orderResponse.id] = rate
        delay(250)
        val response = getGiftCard(orderResponse.id) ?: throw Exception("invalid order number ${orderResponse.id}")

        return try {
            val uri = BitcoinURI(orderResponse.payTo)
            // return value
            val giftCard = response.first()
            order.mapIndexed { index, orderItem ->
                GiftCardInfo(
                    id = orderResponse.id,
                    merchantName = giftCard.merchantName,
                    status = giftCard.status,
                    cryptoAmount = uri.amount.toPlainString(),
                    cryptoCurrency = Constants.DASH_CURRENCY, // need a constant
                    paymentCryptoNetwork = Constants.DASH_CURRENCY,
                    rate = rate.exchangeRate.toString(),
                    fiatAmount = orderItem.value.toString(),//get(index).value.toString(),
                    fiatCurrency = Constants.USD_CURRENCY,
                    paymentUrls = hashMapOf(DASH_DASH_KEY to orderResponse.payTo)
                )
            }
        } catch (e: BitcoinURIParseException) {
            if (e.message?.contains("Unsupported URI scheme") == true || orderResponse.payTo.isEmpty()) {
                throw CTXSpendException(
                    orderResponse.payMessage,
                    serviceName = ServiceName.PiggyCards,
                    200,
                    orderResponse.payMessage,
                    cause = e
                )
            }
            throw CTXSpendException(
                e.message ?: "Unknown URI error",
                serviceName = ServiceName.PiggyCards,
                200,
                "",
                cause = e
            )
        }
    }

    override suspend fun getGiftCard(giftCardId: String): List<GiftCardInfo> {
        val orderStatus = api.getOrderStatus(giftCardId)

        if (orderStatus.code != 200) {
            log.error("order status not retrieved: $giftCardId: ${orderStatus.message}")
            return listOf()
        }

        val data = orderStatus.data
        val cards = orderStatus.data.cards

        return cards.map { giftCard ->
            GiftCardInfo(
                id = data.orderId,
                merchantName = giftCard.name,
                status = when (data.status) {
                    "Payment pending" -> GiftCardStatus.UNPAID
                    "Paid" -> GiftCardStatus.PAID
                    "Processing" -> GiftCardStatus.PAID
                    "Complete" -> GiftCardStatus.FULFILLED
                    "Cancelled" -> GiftCardStatus.REJECTED
                    else -> GiftCardStatus.UNPAID
                },
                barcodeUrl = giftCard.barcodeLink,
                cardNumber = giftCard.claimCode,
                cardPin = giftCard.claimPin,
                cryptoAmount = "0.0",
                cryptoCurrency = Constants.DASH_CURRENCY,
                paymentCryptoNetwork = Constants.DASH_CURRENCY,
                paymentId = data.orderId,
                percentDiscount = "0.0",
                rate = exchangeRateMap[data.orderId]?.exchangeRate.toString() ?: "0.0",
                redeemUrl = giftCard.claimLink,
                fiatAmount = "0.0",
                fiatCurrency = Constants.USD_CURRENCY,
                paymentUrls = hashMapOf(DASH_DASH_KEY to data.payTo)
            )
        }
    }

    suspend fun getAccountEmail(): String? {
        return config.getSecuredData(PiggyCardsConfig.PREFS_KEY_EMAIL)
    }

    private val numberFormat = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 0
    }

    override fun getGiftCardDiscount(merchantId: String, denomination: Double): Double {
        return giftCardMap[merchantId]?.find {
            it.isFixed && it.denomination == numberFormat.format(denomination) || !it.isFixed
        }?.discountPercentage?.let {
            (it - SERVICE_FEE_PERCENT) / 100.0
        } ?: 0.0
    }
}
