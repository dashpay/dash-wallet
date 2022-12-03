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

import org.dash.wallet.common.util.Constants
import org.dash.wallet.integration.coinbase_integration.*
import org.dash.wallet.integration.coinbase_integration.model.*
import retrofit2.http.*

interface CoinBaseServicesApi {

    @GET("v2/accounts")
    suspend fun getUserAccounts(
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE,
        @Query("limit") limit: Int = 300
    ): CoinBaseUserAccountInfo?

    @GET("v2/exchange-rates")
    suspend fun getExchangeRates(
        @Query("currency")currency: String = Constants.DASH_CURRENCY
    ): CoinBaseExchangeRates?

    @GET("v2/payment-methods")
    suspend fun getActivePaymentMethods(
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE,
    ): PaymentMethodsResponse?

    @POST("v2/accounts/{account_id}/buys")
    suspend fun placeBuyOrder(
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE,
        @Path("account_id") accountId: String,
        @Body placeBuyOrderParams: PlaceBuyOrderParams
    ): BuyOrderResponse?

    @POST("v2/accounts/{account_id}/buys/{buy_id}/commit")
    suspend fun commitBuyOrder(
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE,
        @Path("account_id") accountId: String,
        @Path("buy_id") buyOrderId: String
    ): BuyOrderResponse?

    @POST("v2/accounts/{account_id}/transactions")
    suspend fun sendCoinsToWallet(
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE,
        @Header(CoinbaseConstants.CB_2FA_TOKEN_KEY) api2FATokenVersion: String?,
        @Path("account_id") accountId: String,
        @Body sendTransactionToWalletParams: SendTransactionToWalletParams
    ): SendTransactionToWalletResponse?

    @GET(CoinbaseConstants.BASE_IDS_REQUEST_URL)
    suspend fun getBaseIdForUSDModel(
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE,
        @Query("base") baseCurrency: String,
    ): BaseIdForUSDModel?

    @POST("v2/trades")
    suspend fun swapTrade(
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE,
        @Body tradesRequest: TradesRequest
    ): SwapTradeResponse?

    @POST("v2/trades/{trade_id}/commit")
    suspend fun commitSwapTrade(
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE,
        @Path("trade_id") tradeId: String,
    ): SwapTradeResponse?

    @GET("/v2/user/auth")
    suspend fun getAuthorizationInformation(
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE
    ): UserAuthorizationInfoResponse?

    @GET("v2/accounts/{account_id}/addresses")
    suspend fun getUserAccountAddress(
        @Path("account_id") accountId: String,
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE,
    ): CoinBaseAccountAddressResponse

    @POST("v2/accounts/{account_id}/addresses")
    suspend fun createAddress(
        @Header(CoinbaseConstants.CB_VERSION_KEY) apiVersion: String = CoinbaseConstants.CB_VERSION_VALUE,
        @Path("account_id") accountId: String
    ): AddressesResponse?
}
