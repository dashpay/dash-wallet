package org.dash.wallet.integration.coinbase_integration.service

import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountInfo
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface CoinBaseServicesApi {

    @GET("v2/accounts")
    suspend fun getUserAccount(
        @Header("CB-VERSION") apiVersion: String = "2021-09-07",
        @Query("limit") limit: Int = 300
    ): Response<CoinBaseUserAccountInfo>

    @GET("v2/exchange-rates")
    suspend fun getExchangeRates(
        @Query("currency")currency: String = "DASH"
    ): Response<CoinBaseUserAccountInfo>
}
