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
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel

/**
 * Builds, signs and broadcasts the Maya swap transaction for a quoted trade.
 *
 * Implemented in the wallet module (de.schildbach.wallet.payments.MayaBlockchainApiImpl),
 * which owns the dashj transaction machinery; this module stays dashj-free.
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
}
