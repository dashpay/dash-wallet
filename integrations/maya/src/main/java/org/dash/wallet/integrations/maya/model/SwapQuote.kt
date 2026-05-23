/*
 * Copyright (c) 2024. Dash Core Group.
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

package org.dash.wallet.integrations.maya.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class SwapQuote(
    @SerializedName("dust_threshold") val dustThreshold: String,
    @SerializedName("expected_amount_out") val expectedAmountOut: String,
    val expiry: Long,
    val fees: SwapFees,
    @SerializedName("inbound_address") val inboundAddress: String,
    @SerializedName("inbound_confirmation_blocks") val inboundConfirmationBlocks: Int,
    @SerializedName("inbound_confirmation_seconds") val inboundConfirmationSeconds: Int,
    val memo: String,
    val notes: String,
    @SerializedName("outbound_delay_blocks") val outboundDelayBlocks: Int,
    @SerializedName("outbound_delay_seconds") val outboundDelaySeconds: Int,
    @SerializedName("recommended_min_amount_in") val recommendedMinAmountIn: String,
    @SerializedName("slippage_bps") val slippageBps: Int,
    val warning: String,
    val error: String?
) : Parcelable

@Parcelize
data class SwapFees(
    val affiliate: String,
    val asset: String,
    val liquidity: String,
    val outbound: String,
    @SerializedName("slippage_bps") val slippageBps: Int,
    val total: String,
    @SerializedName("total_bps") val totalBps: Int
) : Parcelable
