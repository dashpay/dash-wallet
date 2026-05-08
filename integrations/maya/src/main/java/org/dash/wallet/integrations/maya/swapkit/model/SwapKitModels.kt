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

package org.dash.wallet.integrations.maya.swapkit.model

import com.google.gson.annotations.SerializedName

// /tokens?provider=NAME

data class SwapKitTokenListResponse(
    val provider: String? = null,
    val name: String? = null,
    val timestamp: String? = null,
    val count: Int = 0,
    val tokens: List<SwapKitToken> = emptyList()
)

data class SwapKitToken(
    val chain: String? = null,
    val address: String? = null,
    val chainId: String? = null,
    val ticker: String? = null,
    val identifier: String = "",
    val symbol: String? = null,
    val name: String? = null,
    val decimals: Int = 0,
    val logoURI: String? = null,
    val coingeckoId: String? = null
)

// POST /v3/quote

data class SwapKitQuoteRequest(
    val sellAsset: String,
    val buyAsset: String,
    val sellAmount: String,
    val slippage: Int? = null,
    val sourceAddress: String? = null,
    val destinationAddress: String? = null,
    val providers: List<String>? = null
)

data class SwapKitQuoteResponse(
    val quoteId: String? = null,
    val routes: List<SwapKitRoute> = emptyList(),
    val providerErrors: List<SwapKitProviderError>? = null,
    val error: String? = null
)

data class SwapKitRoute(
    val routeId: String = "",
    val providers: List<String> = emptyList(),
    val sellAsset: String? = null,
    val buyAsset: String? = null,
    val sellAmount: String? = null,
    val expectedBuyAmount: String = "0",
    val expectedBuyAmountMaxSlippage: String? = null,
    val fees: List<SwapKitFee> = emptyList(),
    val estimatedTime: SwapKitEstimatedTime? = null,
    val totalSlippageBps: Double = 0.0,
    val warnings: List<String>? = null,
    val meta: SwapKitRouteMeta? = null
)

data class SwapKitFee(
    val type: String? = null,
    val amount: String? = null,
    val asset: String? = null,
    val chain: String? = null
)

data class SwapKitEstimatedTime(
    val inbound: Double? = null,
    val swap: Double? = null,
    val outbound: Double? = null,
    val total: Double? = null
)

data class SwapKitRouteMeta(
    val tags: List<String>? = null,
    val assets: List<SwapKitMetaAsset>? = null
)

data class SwapKitMetaAsset(
    val asset: String? = null,
    val price: Double? = null,
    val image: String? = null
)

data class SwapKitProviderError(
    val provider: String? = null,
    val errorCode: String? = null,
    val message: String? = null
)

// POST /v3/swap

data class SwapKitSwapRequest(
    val routeId: String,
    val sourceAddress: String,
    val destinationAddress: String,
    val disableBalanceCheck: Boolean? = null,
    val overrideSlippage: Boolean? = null
)

data class SwapKitSwapResponse(
    val swapId: String? = null,
    val providers: List<String>? = null,
    val sellAsset: String? = null,
    val buyAsset: String? = null,
    val sellAmount: String? = null,
    val expectedBuyAmount: String? = null,
    val expectedBuyAmountMaxSlippage: String? = null,
    val targetAddress: String? = null,
    val inboundAddress: String? = null,
    val memo: String? = null,
    val fees: List<SwapKitFee>? = null,
    val txType: String? = null,
    val error: String? = null
)

// POST /price

data class SwapKitPriceRequest(
    val tokens: List<SwapKitPriceTokenRef>,
    val metadata: Boolean = false
)

data class SwapKitPriceTokenRef(
    val identifier: String
)

data class SwapKitPriceItem(
    val identifier: String = "",
    val provider: String? = null,
    @SerializedName("price_usd") val priceUsd: Double = 0.0,
    val timestamp: Long? = null
)