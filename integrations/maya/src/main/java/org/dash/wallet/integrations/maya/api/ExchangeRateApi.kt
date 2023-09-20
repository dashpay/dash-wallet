package org.dash.wallet.integrations.maya.api

import org.dash.wallet.integrations.maya.model.ExchangeRateResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface ExchangeRateApi {
    @GET("latest")
    suspend fun getRates(@Query("base") baseCurrencyCode: String): Response<ExchangeRateResponse>
}
