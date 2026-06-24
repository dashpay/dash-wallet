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

package org.dash.wallet.integrations.maya.api

import android.content.Intent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.integrations.maya.model.AccountDataUIModel
import org.dash.wallet.integrations.maya.model.BuyOrder
import org.dash.wallet.integrations.maya.model.InboundAddress
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.model.SwapQuote
import org.dash.wallet.integrations.maya.model.SwapQuoteRequest
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel

/**
 * Backend-agnostic surface for cross-chain swaps.
 *
 * Both the direct Maya integration ([MayaApiAggregator]) and the SwapKit aggregator
 * ([org.dash.wallet.integrations.maya.swapkit.SwapKitApiAggregator]) implement this
 * interface so that ViewModels can be wired to either backend.
 *
 * Maya-shaped models ([PoolInfo], [SwapQuote], [InboundAddress], [SwapTradeUIModel])
 * are reused as the common DTO shape. SwapKit responses are mapped onto these on the
 * provider side; only the fields the wallet UI consumes need to be populated.
 */
/** Shared empty default for backends that don't resolve per-asset route providers. */
private val EMPTY_PREFERRED_ROUTE_PROVIDERS: StateFlow<Map<String, RouteProvider>> =
    MutableStateFlow(emptyMap())

interface SwapProvider {
    val poolInfoList: StateFlow<List<PoolInfo>>
    val apiError: StateFlow<Exception?>
    var notificationIntent: Intent?
    var showNotificationOnResult: Boolean

    /**
     * SwapKit only: asset identifier → the recommended [RouteProvider] for assets
     * routable via BOTH Maya and NEAR, resolved asynchronously by an indicative quote
     * after the pool list is published. Assets absent from the map (single-provider,
     * not-yet-resolved, or Maya-halted) have no calculated preference. Empty for the
     * native Maya backend.
     */
    val preferredRouteProviders: StateFlow<Map<String, RouteProvider>>
        get() = EMPTY_PREFERRED_ROUTE_PROVIDERS

    suspend fun reset()

    fun observePoolList(fiatExchangeRate: Fiat): Flow<List<PoolInfo>>

    /**
     * Returns the list of vault/inbound addresses for every chain the provider supports.
     * For Maya this is the live `/inbound_addresses` response. For SwapKit, vault
     * addresses come back per-swap inside `/v3/swap`, so the implementation synthesises
     * one entry per supported chain with `halted=false` (and the actual address blank).
     */
    suspend fun getInboundAddresses(): List<InboundAddress>

    /** Indicative quote against a chain's example address — used to bootstrap the input screen. */
    suspend fun getDefaultSwapQuote(toAsset: String, value: Long = 10_0000_0000): SwapQuote?

    /** Indicative quote against a user-specified destination address. */
    suspend fun getDefaultSwapQuote(toAsset: String, destinationAddress: String, value: Long = 1_0000_0000): SwapQuote?

    /**
     * Resolves a [SwapQuoteRequest] into a fully-specified [SwapTradeUIModel] with vault
     * address + memo + fee, ready for the user to confirm. For SwapKit this calls
     * `/v3/quote` followed by `/v3/swap`.
     */
    suspend fun getSwapInfo(swapRequest: SwapQuoteRequest): ResponseResource<SwapTradeUIModel>

    /**
     * Commits a confirmed [SwapTradeUIModel]: refreshes the quote (if applicable),
     * builds and broadcasts the DASH transaction. Both backends ultimately delegate
     * to [MayaBlockchainApi] to construct the DASH tx — the only difference is which
     * web API the refresh hits.
     */
    suspend fun commitSwapTransaction(
        tradeId: String,
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel>

    /**
     * Creates a BUY order (crypto -> DASH). SwapKit only: runs `/v3/quote` + `/v3/swap` with
     * sellAsset = [sellAsset] (the chosen crypto, e.g. "BTC.BTC"), buyAsset = DASH, and returns
     * the inbound deposit [BuyOrder.depositAddress] the user must send [sellAmount] (a human-unit
     * decimal of the crypto) to. [destinationAddress] is the user's DASH receive address (where the
     * converted DASH lands); [refundAddress] is reported to SwapKit as the source address and is
     * where NEAR-route refunds are returned. The native Maya backend doesn't implement buys and
     * returns a failure via the default below.
     */
    suspend fun createBuyOrder(
        sellAsset: String,
        sellAmount: String,
        destinationAddress: String,
        refundAddress: String
    ): ResponseResource<BuyOrder> = ResponseResource.Failure(
        UnsupportedOperationException("buy not supported by this provider"),
        false,
        0,
        null
    )

    /** Stub-friendly user-accounts probe; today only Maya returns a single placeholder. */
    suspend fun getUserAccounts(currency: String): List<AccountDataUIModel>

    fun applyPoolPrices(pools: List<PoolInfo>, usdToFiat: Fiat)
}
