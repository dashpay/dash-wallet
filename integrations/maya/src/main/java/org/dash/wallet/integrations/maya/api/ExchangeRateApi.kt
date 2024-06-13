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

package org.dash.wallet.integrations.maya.api

import org.dash.wallet.integrations.maya.model.CurrencyBeaconResponse
import org.dash.wallet.integrations.maya.model.ExchangeRateResponse
import org.dash.wallet.integrations.maya.model.FreeCurrencyResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * https://v6.exchangerate-api.com/v6/{api-key}}/latest/USD
 */
interface ExchangeRateApi {
    @GET("latest/USD")
    suspend fun getRates(): Response<ExchangeRateResponse>
}

interface CurrencyBeaconApi {
    @GET("latest")
    suspend fun getRates(
        @Query("base") baseCurrencyCode: String,
        @Query("symbols") resultCurrencyCode: String,
        @Query("api_key") apiKey: String = "1xsN7q7S2Lo3gzlmtrpdmLufO96OBlRK"
    ): Response<CurrencyBeaconResponse>
}

interface FreeCurrencyApi {
    @GET("latest")
    suspend fun getRates(
        @Query("base_currency") baseCurrencyCode: String = "USD",
        @Query("currencies") resultCurrencyCode: String,
        @Query("apikey") apiKey: String = "fca_live_SysbOIn5mJg21vRzfUVRYPA6hIxku1umZUaUkNty"
    ): Response<FreeCurrencyResponse>
}
