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

package org.dash.wallet.features.exploredash.network.service.ctxspend.stubs

import kotlinx.coroutines.delay
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.features.exploredash.data.ctxspend.model.GetMerchantResponse
import org.dash.wallet.features.exploredash.data.ctxspend.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.ctxspend.model.LoginRequest
import org.dash.wallet.features.exploredash.data.ctxspend.model.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.ctxspend.model.RefreshTokenResponse
import org.dash.wallet.features.exploredash.data.ctxspend.model.VerifyEmailRequest
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.network.service.ctxspend.stubs.FakeCTXSpendSendService.Companion.CTX_PAY_SCHEMA
import retrofit2.Response
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

data class FakeApiCache(
    var giftCardId: String = "",
    var orderId: String = "",
    var paymentId: String = "",
    var cardNumber: String = "",
    var cardPin: String = ""
)

class FakeCTXSpendApi(
    private val realApi: CTXSpendApi,
    private val exchangeRates: ExchangeRatesProvider
) : CTXSpendApi {
    private lateinit var cache: FakeApiCache

    override suspend fun login(loginRequest: LoginRequest): Response<Unit> {
        return Response.success(Unit)
    }

    override suspend fun verifyEmail(verifyEmailRequest: VerifyEmailRequest): RefreshTokenResponse? {
        return RefreshTokenResponse("fake_refresh_token", "fake_access_token")
    }

    override suspend fun purchaseGiftCard(purchaseGiftCardRequest: PurchaseGiftCardRequest): GiftCardResponse {
        cache = FakeApiCache()
        cache.apply {
            giftCardId = Random.nextInt(10000).toString()
            orderId = Random.nextInt(10000).toString()
            paymentId = Random.nextInt(10000).toString()
        }

        val exchangeRate = exchangeRates.getExchangeRate("USD")
        val dashAmount = purchaseGiftCardRequest.fiatAmount?.let { fiatAmount ->
            exchangeRate?.let {
                ExchangeRate(Coin.COIN, exchangeRate.fiat)
                    .fiatToCoin(Fiat.parseFiat("USD", fiatAmount))
            }
        } ?: Coin.ZERO

        delay(500.milliseconds)
        return GiftCardResponse(
            id = cache.giftCardId,
            status = "paid",
            cryptoAmount = Coin.valueOf((dashAmount.value * 0.85).toLong()).toString(),
            cryptoCurrency = "DASH",
            paymentUrls = buildMap {
                put("DASH.DASH", "${CTX_PAY_SCHEMA}example.com?amount=$dashAmount")
            },
        )
    }

    override suspend fun getMerchant(id: String): GetMerchantResponse? {
        return realApi.getMerchant(id)
    }

    override suspend fun getGiftCard(txid: String): GiftCardResponse {
        cache.apply {
            cardNumber = Random.nextInt(10000).toString()
            cardPin = Random.nextInt(10000).toString()
        }

        delay(500.milliseconds)
        return GiftCardResponse(
            id = "fakeid",
            status = "unpaid",
            barcodeUrl = "https://optional.is/required/wp-content/uploads/2009/06/barcode-pdf417.png",
            cardNumber = cache.cardNumber,
            cardPin = cache.cardPin
        )
    }
}
