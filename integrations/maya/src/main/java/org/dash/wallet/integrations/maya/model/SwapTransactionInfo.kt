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

import com.google.gson.annotations.SerializedName

data class SwapTransactionInfo(
    @SerializedName("observed_tx") val observedTx: ObservedTx,
    @SerializedName("keysign_metric") val keysignMetric: KeysignMetric,
    val error: String?
)

data class ObservedTx(
    @SerializedName("tx") val transaction: Transaction,
    val status: String,
    @SerializedName("out_hashes") val outHashes: List<String>,
    @SerializedName("block_height") val blockHeight: Int,
    val signers: List<String>,
    @SerializedName("observed_pub_key") val observedPubKey: String,
    @SerializedName("finalise_height") val finaliseHeight: Int
)

data class Transaction(
    val id: String,
    val chain: String,
    @SerializedName("from_address") val fromAddress: String,
    @SerializedName("to_address") val toAddress: String,
    val coins: List<CoinAmount>,
    val gas: List<CoinAmount>,
    val memo: String
)

data class CoinAmount(
    val asset: String,
    val amount: String
)

data class KeysignMetric(
    @SerializedName("tx_id") val txId: String,
    @SerializedName("node_tss_times") val nodeTssTimes: Any?
)
