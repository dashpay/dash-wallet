/*
 * Copyright 2026 Dash Core Group.
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

package org.dash.wallet.integrations.maya.swapkit

import org.dash.wallet.integrations.maya.swapkit.model.SwapKitPriceItem
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitPriceRequest
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitQuoteRequest
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitQuoteResponse
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitSwapRequest
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitSwapResponse
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitTokenListResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit surface for the SwapKit aggregator API.
 *
 * Reference: https://docs.swapkit.dev/swapkit-api/introduction
 *
 * All requests require an `x-api-key` header — the value is injected globally by
 * [SwapKitAuthInterceptor] in [org.dash.wallet.integrations.maya.di.MayaModule].
 */
interface SwapKitEndpoint {
    @GET("tokens")
    suspend fun getTokens(@Query("provider") provider: String): Response<SwapKitTokenListResponse>

    /** Returns identifiers reachable as buy-assets when selling [sellAsset]. */
    @GET("swapTo")
    suspend fun getSwapTo(@Query("sellAsset") sellAsset: String): Response<List<String>>

    @POST("v3/quote")
    suspend fun postQuote(@Body request: SwapKitQuoteRequest): Response<SwapKitQuoteResponse>

    @POST("v3/swap")
    suspend fun postSwap(@Body request: SwapKitSwapRequest): Response<SwapKitSwapResponse>

    @POST("price")
    suspend fun postPrice(@Body request: SwapKitPriceRequest): Response<List<SwapKitPriceItem>>
}