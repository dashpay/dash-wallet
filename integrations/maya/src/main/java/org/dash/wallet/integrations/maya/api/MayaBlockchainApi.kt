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

import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.money.Dash
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel

/**
 * Builds, signs and broadcasts the Maya swap transaction for a quoted trade.
 *
 * Implemented in the wallet module (de.schildbach.wallet.payments.MayaBlockchainApiImpl),
 * which builds the deposit on the Kotlin SDK's deferred build/broadcast surface and
 * verifies the MAYACHAIN deposit shape before broadcasting; this module stays free
 * of wallet-engine types.
 */
interface MayaBlockchainApi {
    /**
     * Re-fetches a fresh quote via Maya, then builds + signs + broadcasts the DASH transaction.
     * Used by the direct Maya backend.
     */
    suspend fun commitSwapTransaction(
        tradeId: String,
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel>

    /**
     * Builds + signs + broadcasts the DASH transaction for an already-resolved trade
     * (vault address + memo + fee already populated). The SwapKit backend uses this
     * directly after refreshing the route via SwapKit's own API.
     */
    suspend fun buildAndSendSwapTx(
        swapTradeUIModel: SwapTradeUIModel
    ): ResponseResource<SwapTradeUIModel>

    /**
     * The largest amount a swap deposit can pay a vault right now: what a
     * DRAIN of the funding account delivers, measured through the real SDK
     * builder and reported by the engine — never estimated, and never reduced
     * by a headroom or reserve constant.
     *
     * Quote a MAX sell at exactly this figure. The deposit that follows runs
     * the identical drain, so quote and payment agree by construction rather
     * than by a margin chosen to be safe. A quote above the real deliverable
     * would make the deposit pay less than quoted, and NEAR Intents refuses
     * under-delivery (~1h wait, then a refund minus 0.001 DASH).
     *
     * [Dash.ZERO] when no drain is fundable at all.
     */
    suspend fun maxSwapDepositAmount(): Dash
}
