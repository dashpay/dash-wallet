/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.integration.coinbase_integration.service

import org.dash.wallet.integration.coinbase_integration.CB_VERSION_KEY
import org.dash.wallet.integration.coinbase_integration.CB_VERSION_VALUE
import org.dash.wallet.integration.coinbase_integration.model.*
import retrofit2.Response
import retrofit2.http.*

interface CoinBaseServicesApi {

    @GET("v2/accounts")
    suspend fun getUserAccounts(
        @Header(CB_VERSION_KEY) apiVersion: String = CB_VERSION_VALUE,
        @Query("limit") limit: Int = 300
    ): Response<CoinBaseUserAccountInfo>

    @GET("v2/exchange-rates")
    suspend fun getExchangeRates(
        @Query("currency")currency: String = "DASH"
    ): CoinBaseExchangeRates?

    @GET("v2/payment-methods")
    suspend fun getActivePaymentMethods(
        @Header(CB_VERSION_KEY) apiVersion: String = CB_VERSION_VALUE,
    ): PaymentMethodsResponse?

    @POST("v2/accounts/{account_id}/buys")
    suspend fun placeBuyOrder(
        @Header(CB_VERSION_KEY) apiVersion: String = CB_VERSION_VALUE,
        @Path("account_id") accountId: String,
        @Body placeBuyOrderParams: PlaceBuyOrderParams
    ): BuyOrderResponse?

    @POST("v2/accounts/{account_id}/buys/{buy_id}/commit")
    suspend fun commitBuyOrder(
        @Header(CB_VERSION_KEY) apiVersion: String = CB_VERSION_VALUE,
        @Path("account_id") accountId: String,
        @Path("buy_id") buyOrderId: String
    ): BuyOrderResponse?

    @POST("v2/accounts/{account_id}/transactions")
    suspend fun sendCoinsToWallet(
        @Header(CB_VERSION_KEY) apiVersion: String = CB_VERSION_VALUE,
        @Path("account_id") accountId: String,
        @Body sendTransactionToWalletParams: SendTransactionToWalletParams
    ): Response<SendTransactionToWalletResponse?>

    @GET("v2/assets/prices?base=USD&filter=holdable&resolution=latest")
    suspend fun getBaseIdForUSDModel(
        @Header(CB_VERSION_KEY) apiVersion: String = CB_VERSION_VALUE,
    ): Response<BaseIdForUSDModel?>

    @POST("v2/trades")
    suspend fun swapTrade(
        @Header(CB_VERSION_KEY) apiVersion: String = CB_VERSION_VALUE,
        @Body tradesRequest: TradesRequest
    ): SwapTradeResponse?

    @POST("v2/trades/{trade_id}/commit")
    suspend fun commitSwapTrade(
        @Header(CB_VERSION_KEY) apiVersion: String = CB_VERSION_VALUE,
        @Path("trade_id") tradeId: String,
    ): SwapTradeResponse?
}
