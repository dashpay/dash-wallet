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

package org.dash.wallet.features.exploredash.network.service.stubs

import kotlinx.coroutines.delay
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.features.exploredash.data.dashdirect.model.giftcard.GetGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.giftcard.GetGiftCardResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.merchant.GetMerchantByIdRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.merchant.GetMerchantByIdResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.paymentstatus.PaymentStatusRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.paymentstatus.PaymentStatusResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.purchase.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashdirect.model.purchase.PurchaseGiftCardResponse
import org.dash.wallet.features.exploredash.network.service.DashDirectServicesApi
import org.dash.wallet.features.exploredash.network.service.stubs.FakeDashDirectSendService.Companion.DASH_DIRECT_SCHEMA
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

data class FakeApiCache(
    var giftCardId: String = "",
    var orderId: String = "",
    var paymentId: String = "",
    var cardNumber: String = "",
    var cardPin: String = ""
)

class FakeDashDirectApi(
    private val realApi: DashDirectServicesApi,
    private val exchangeRates: ExchangeRatesProvider
) : DashDirectServicesApi {
    private lateinit var cache: FakeApiCache

    override suspend fun purchaseGiftCard(
        deviceID: String,
        email: String,
        purchaseGiftCardRequest: PurchaseGiftCardRequest
    ): PurchaseGiftCardResponse {
        cache = FakeApiCache()
        cache.apply {
            giftCardId = Random.nextInt(10000).toString()
            orderId = Random.nextInt(10000).toString()
            paymentId = Random.nextInt(10000).toString()
        }

        val exchangeRate = exchangeRates.getExchangeRate("USD")
        val dashAmount = purchaseGiftCardRequest.amountUSD?.let { amountUsd ->
            exchangeRate?.let {
                ExchangeRate(Coin.COIN, exchangeRate.fiat)
                    .fiatToCoin(Fiat.parseFiat("USD", amountUsd.toString()))
            }
        } ?: 0

        delay(500.milliseconds)
        return PurchaseGiftCardResponse(
            successful = true,
            data = PurchaseGiftCardResponse.Data(
                giftCardId = cache.giftCardId,
                dashAmount = dashAmount.toString(),
                orderId = cache.orderId,
                paymentId = cache.paymentId,
                uri = "${DASH_DIRECT_SCHEMA}example.com?amount=$dashAmount",
                success = true
            )
        )
    }

    override suspend fun getMerchantById(
        email: String,
        getMerchantByIdRequest: GetMerchantByIdRequest
    ): GetMerchantByIdResponse? {
        return realApi.getMerchantById(email, getMerchantByIdRequest)
    }

    override suspend fun getPaymentStatus(
        email: String,
        paymentStatusRequest: PaymentStatusRequest
    ): PaymentStatusResponse {
        delay(500.milliseconds)
        return PaymentStatusResponse(
            data = PaymentStatusResponse.Data(
                status = "paid",
                giftCardId = cache.giftCardId.toLong()
            )
        )
    }

    override suspend fun getGiftCard(email: String, getGiftCardRequest: GetGiftCardRequest): GetGiftCardResponse {
        cache.apply {
            cardNumber = Random.nextInt(10000).toString()
            cardPin = Random.nextInt(10000).toString()
        }

        delay(500.milliseconds)
        return GetGiftCardResponse(
            data = GetGiftCardResponse.Data(
                barcodeUrl = "https://optional.is/required/wp-content/uploads/2009/06/barcode-pdf417.png",
                cardNumber = cache.cardNumber,
                cardPin = cache.cardPin
            )
        )
    }
}
