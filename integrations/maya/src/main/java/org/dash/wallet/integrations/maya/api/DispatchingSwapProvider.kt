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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.integrations.maya.model.AccountDataUIModel
import org.dash.wallet.integrations.maya.model.BuyOrder
import org.dash.wallet.integrations.maya.model.InboundAddress
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.model.SwapQuote
import org.dash.wallet.integrations.maya.model.SwapQuoteRequest
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.swapkit.SwapKitApiAggregator
import org.dash.wallet.integrations.maya.swapkit.SwapKitConstants
import org.dash.wallet.integrations.maya.utils.MayaConfig
import org.dash.wallet.integrations.maya.utils.SwapBackend
import org.dash.wallet.integrations.maya.utils.SwapDirection
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton [SwapProvider] that delegates to either [MayaApiAggregator] or
 * [SwapKitApiAggregator] based on the persisted [MayaConfig.SWAP_BACKEND] preference.
 *
 * Switching the active backend at runtime is supported via [setBackend] — the next
 * subscription on a property like [poolInfoList] (e.g. when a screen reopens) will
 * resolve to the newly-selected backend's underlying flow. Existing subscriptions
 * stay attached to their original flow until they're recollected, which matches
 * the typical "back-out, tap the other entry, re-enter" UX pattern.
 *
 * If SwapKit is requested but no API key is configured, the dispatcher falls back
 * to Maya so the wallet stays usable without credentials.
 */
@Singleton
class DispatchingSwapProvider @Inject constructor(
    private val maya: MayaApiAggregator,
    private val swapKit: SwapKitApiAggregator,
    private val config: MayaConfig
) : SwapProvider {
    companion object {
        private val log = LoggerFactory.getLogger(DispatchingSwapProvider::class.java)
    }

    @Volatile
    private var activeBackend: SwapBackend = SwapBackend.MAYA

    private val persistScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Load the persisted backend off the main thread to avoid blocking the
        // constructing thread (Hilt may build this singleton on the main thread).
        persistScope.launch {
            val configured = runCatching { config.get(MayaConfig.SWAP_BACKEND) }
                .getOrNull()
                ?.let { runCatching { SwapBackend.valueOf(it) }.getOrNull() }
                ?: SwapBackend.MAYA
            activeBackend = effective(configured)
        }
    }

    private fun effective(requested: SwapBackend): SwapBackend {
        return if (requested == SwapBackend.SWAPKIT && SwapKitConstants.API_KEY.isBlank()) {
            log.warn("SwapKit requested but no API key — falling back to Maya")
            SwapBackend.MAYA
        } else {
            requested
        }
    }

    fun currentBackend(): SwapBackend = activeBackend

    /**
     * Switches the active backend immediately (in-memory) and persists the choice
     * asynchronously. Synchronous on purpose so callers can switch and navigate in
     * the same thread tick without racing the new screen's first subscription.
     */
    fun setBackend(requested: SwapBackend) {
        val resolved = effective(requested)
        activeBackend = resolved
        persistScope.launch { config.set(MayaConfig.SWAP_BACKEND, resolved.name) }
    }

    internal val active: SwapProvider
        get() = when (activeBackend) {
            SwapBackend.SWAPKIT -> swapKit
            SwapBackend.MAYA -> maya
        }

    override val poolInfoList: StateFlow<List<PoolInfo>>
        get() = active.poolInfoList

    override val apiError: StateFlow<Exception?>
        get() = active.apiError

    override val preferredRouteProviders: StateFlow<Map<String, RouteProvider>>
        get() = active.preferredRouteProviders

    override var notificationIntent: Intent?
        get() = active.notificationIntent
        set(value) { active.notificationIntent = value }

    override var showNotificationOnResult: Boolean
        get() = active.showNotificationOnResult
        set(value) { active.showNotificationOnResult = value }

    override fun setSwapDirection(direction: SwapDirection) = active.setSwapDirection(direction)

    override suspend fun reset() = active.reset()

    override fun observePoolList(fiatExchangeRate: Fiat): Flow<List<PoolInfo>> =
        active.observePoolList(fiatExchangeRate)

    override suspend fun getInboundAddresses(): List<InboundAddress> =
        active.getInboundAddresses()

    override suspend fun getDefaultSwapQuote(toAsset: String, value: Long): SwapQuote? =
        active.getDefaultSwapQuote(toAsset, value)

    override suspend fun getDefaultSwapQuote(
        toAsset: String,
        destinationAddress: String,
        value: Long
    ): SwapQuote? = active.getDefaultSwapQuote(toAsset, destinationAddress, value)

    override suspend fun getSwapInfo(swapRequest: SwapQuoteRequest): ResponseResource<SwapTradeUIModel> =
        active.getSwapInfo(swapRequest)

    override suspend fun commitSwapTransaction(
        tradeId: String,
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel> = active.commitSwapTransaction(tradeId, swapTradeUIModel)

    override suspend fun createBuyOrder(
        sellAsset: String,
        sellAmount: String,
        destinationAddress: String,
        refundAddress: String
    ): ResponseResource<BuyOrder> =
        active.createBuyOrder(sellAsset, sellAmount, destinationAddress, refundAddress)

    override suspend fun getUserAccounts(currency: String): List<AccountDataUIModel> =
        active.getUserAccounts(currency)

    override fun applyPoolPrices(pools: List<PoolInfo>, usdToFiat: Fiat) {
        active.applyPoolPrices(pools, usdToFiat)
    }
}
