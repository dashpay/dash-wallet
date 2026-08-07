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

package org.dash.wallet.common.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.dash.wallet.common.data.TxId

/** Mirrors the SwapKit /track `status` values. */
enum class SwapOrderStatus {
    NOT_STARTED,
    PENDING,
    SWAPPING,
    COMPLETED,
    REFUNDED,
    FAILED,
    UNKNOWN;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == REFUNDED || this == FAILED

    companion object {
        val active = listOf(NOT_STARTED, PENDING, SWAPPING, UNKNOWN)

        fun fromTrackStatus(value: String?): SwapOrderStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * A DEX sell swap (DASH -> other asset) keyed by the DASH transaction that funded it.
 * Amounts are decimal strings denominated in [toAsset]; [timestamp], [finalisedAt] and
 * [lastChecked] are milliseconds since epoch.
 */
@Entity(tableName = "swap_orders")
data class SwapOrder(
    @PrimaryKey val txId: TxId,
    val service: String,
    val provider: String? = null,
    val fromAsset: String,
    val toAsset: String,
    val toAddress: String,
    /** Inbound address the DASH was sent to (Maya vault / NEAR deposit channel). NEAR
     *  Intents swaps are tracked by this address when the hash lookup fails. */
    val depositAddress: String? = null,
    val expectedToAmount: String? = null,
    val actualToAmount: String? = null,
    val status: SwapOrderStatus = SwapOrderStatus.PENDING,
    val outboundTxHash: String? = null,
    val timestamp: Long,
    val finalisedAt: Long? = null,
    val lastChecked: Long = 0
)
