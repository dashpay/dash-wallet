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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.entity.SwapOrder
import org.dash.wallet.common.data.entity.SwapOrderStatus
import org.dash.wallet.integrations.maya.data.SwapOrderDao
import org.dash.wallet.integrations.maya.swapkit.SwapKitConstants
import org.dash.wallet.integrations.maya.swapkit.SwapKitWebApi
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitTrackRequest
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Polls SwapKit `/track` for every non-terminal [SwapOrder] until it completes, refunds
 * or fails, persisting status changes so the transaction list updates reactively.
 *
 * Tracking is by on-chain hash + chain id, so it works for swaps built by either backend
 * (native Maya or SwapKit). [start] is called once at app start and resumes any swaps
 * that were still in flight when the process last died; the poll loop runs only while
 * active orders exist and stops on its own once the last one settles or exceeds
 * [MAX_TRACKING_AGE_MS].
 */
@Singleton
class SwapTrackingService @Inject constructor(
    private val webApi: SwapKitWebApi,
    private val swapOrderDao: SwapOrderDao
) {
    companion object {
        private val log = LoggerFactory.getLogger(SwapTrackingService::class.java)
        private val POLL_PERIOD = 30.seconds
        private val MAX_TRACKING_AGE_MS = TimeUnit.HOURS.toMillis(24)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null

    fun start() {
        if (trackingJob?.isActive == true) {
            return
        }
        trackingJob = scope.launch {
            swapOrderDao.observeOrdersWithStatus(SwapOrderStatus.active)
                .distinctUntilChanged()
                .collectLatest { orders ->
                    while (isActive) {
                        val trackable = orders.filter {
                            System.currentTimeMillis() - it.timestamp < MAX_TRACKING_AGE_MS
                        }
                        if (trackable.isEmpty()) {
                            break
                        }
                        trackable.forEach { checkOrder(it) }
                        delay(POLL_PERIOD)
                    }
                }
        }
    }

    fun stop() {
        trackingJob?.cancel()
        trackingJob = null
    }

    private fun isNearRoute(order: SwapOrder): Boolean {
        return order.provider?.contains("NEAR", ignoreCase = true) == true
    }

    private suspend fun checkOrder(order: SwapOrder) {
        val txHash = order.txId.toString()
        var result = webApi.track(
            SwapKitTrackRequest(
                hash = txHash,
                chainId = SwapKitConstants.DASH_CHAIN_ID,
                depositAddress = order.depositAddress
            )
        )
        // NEAR Intents swaps are keyed by their deposit channel, not the on-chain hash —
        // if the hash lookup errors, retry by depositAddress alone before giving up.
        if (result == null && isNearRoute(order) && !order.depositAddress.isNullOrEmpty()) {
            result = webApi.track(SwapKitTrackRequest(depositAddress = order.depositAddress))
        }
        if (result == null) {
            return
        }
        // A body without a status carries no information (e.g. the tracker hasn't
        // indexed the tx yet) — keep the current state and retry on the next tick.
        val status = result.status?.let { SwapOrderStatus.fromTrackStatus(it) } ?: return

        val outboundTxHash = result.legs.orEmpty()
            .lastOrNull { !it.hash.isNullOrEmpty() && !it.hash.equals(txHash, ignoreCase = true) }
            ?.hash ?: order.outboundTxHash
        val updated = order.copy(
            status = status,
            actualToAmount = result.toAmount ?: order.actualToAmount,
            outboundTxHash = outboundTxHash,
            // the API reports -1 for "unknown" even on completed swaps
            finalisedAt = result.finalisedAt?.takeIf { it > 0 }?.let { it * 1000 } ?: order.finalisedAt
        )

        // Only write on a material change: a Room write re-emits the observed flow,
        // so unconditional writes (even lastChecked alone) would restart the poll
        // loop immediately and turn the 30s ticker into a tight loop.
        if (updated != order) {
            swapOrderDao.updateOrder(updated.copy(lastChecked = System.currentTimeMillis()))
            log.info(
                "swap {}: status {} -> {} (outbound tx: {})",
                txHash, order.status, status, outboundTxHash
            )
        }
    }
}
