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
