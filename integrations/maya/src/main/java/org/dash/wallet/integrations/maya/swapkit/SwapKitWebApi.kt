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

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitPriceItem
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitPriceRequest
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitPriceTokenRef
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitQuoteRequest
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitQuoteResponse
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitSwapRequest
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitSwapResponse
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitToken
import org.slf4j.LoggerFactory
import java.io.IOException
import javax.inject.Inject

/**
 * Thin wrapper around [SwapKitEndpoint] with the same error-handling shape as
 * [org.dash.wallet.integrations.maya.api.MayaWebApi] — exceptions and non-2xx
 * responses degrade to safe defaults (empty list / null) and are logged.
 */
open class SwapKitWebApi @Inject constructor(
    private val endpoint: SwapKitEndpoint,
    private val analyticsService: AnalyticsService
) {
    companion object {
        private val log = LoggerFactory.getLogger(SwapKitWebApi::class.java)
    }

    suspend fun getTokens(provider: String): List<SwapKitToken> {
        return safeCall("getTokens($provider)", emptyList()) {
            val response = endpoint.getTokens(provider)
            if (response.isSuccessful) response.body()?.tokens.orEmpty() else emptyList()
        }
    }

    suspend fun getSwapTo(sellAsset: String): List<String> {
        return safeCall("getSwapTo($sellAsset)", emptyList()) {
            val response = endpoint.getSwapTo(sellAsset)
            if (response.isSuccessful) response.body().orEmpty() else emptyList()
        }
    }

    suspend fun getQuote(request: SwapKitQuoteRequest): SwapKitQuoteResponse? {
        return safeCall("getQuote(${request.sellAsset}->${request.buyAsset})", null) {
            val response = endpoint.postQuote(request)
            if (response.isSuccessful) {
                response.body()
            } else {
                // SwapKit returns errors like {"error":"noRoutesFound","message":"...","data":{...}}
                // as non-2xx responses, so the body is on errorBody(). Parse it so callers
                // can react to the error code rather than seeing an opaque null.
                parseErrorBody(response.errorBody()?.string())
            }
        }
    }

    private fun parseErrorBody(body: String?): SwapKitQuoteResponse? {
        if (body.isNullOrBlank()) return null
        return try {
            Gson().fromJson(body, SwapKitQuoteResponse::class.java)
        } catch (ex: JsonSyntaxException) {
            log.warn("swapkit getQuote: could not parse error body: $ex")
            null
        }
    }

    suspend fun postSwap(request: SwapKitSwapRequest): SwapKitSwapResponse? {
        return safeCall("postSwap(${request.routeId})", null) {
            val response = endpoint.postSwap(request)
            if (response.isSuccessful) {
                response.body()
            } else {
                // SwapKit /v3/swap returns errors like {"message":"Cannot build transaction..."}
                // (and sometimes {"error":"...","message":"..."}) on non-2xx. Parse so the
                // aggregator surfaces the real message instead of a generic failure string.
                parseSwapErrorBody(response.errorBody()?.string())
            }
        }
    }

    private fun parseSwapErrorBody(body: String?): SwapKitSwapResponse? {
        if (body.isNullOrBlank()) return null
        return try {
            val parsed = Gson().fromJson(body, SwapKitSwapResponse::class.java) ?: return null
            // Aggregator checks `swap.error`; fall the message into that slot when the
            // server only sent `message` so the existing error path picks it up.
            if (parsed.error == null && !parsed.message.isNullOrBlank()) {
                parsed.copy(error = parsed.message)
            } else {
                parsed
            }
        } catch (ex: JsonSyntaxException) {
            log.warn("swapkit postSwap: could not parse error body: $ex")
            null
        }
    }

    suspend fun getPrices(identifiers: List<String>): List<SwapKitPriceItem> {
        if (identifiers.isEmpty()) return emptyList()
        return safeCall("getPrices(${identifiers.size})", emptyList()) {
            val response = endpoint.postPrice(
                SwapKitPriceRequest(
                    tokens = identifiers.map { SwapKitPriceTokenRef(it) },
                    metadata = false
                )
            )
            if (response.isSuccessful) response.body().orEmpty() else emptyList()
        }
    }

    private inline fun <T> safeCall(label: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (ex: Exception) {
            log.error("swapkit $label: $ex")
            if (ex !is IOException) {
                analyticsService.logError(ex)
            }
            fallback
        }
    }
}
