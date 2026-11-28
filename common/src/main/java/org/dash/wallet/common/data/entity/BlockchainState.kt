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

package org.dash.wallet.common.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*
// NOTE on [chainlockHeight]: it is a MONOTONIC LOWER BOUND on the network's
// best chainlocked height, not a live mirror of it. Pre-cutover the dashj
// writer refreshes it from `chainLockHandler.bestChainLockBlockHeight` only
// on sync-progress callbacks; post-cutover the Kotlin SDK writer advances it
// from the engine's `ChainLockProcessed` wallet events, which it only sees
// while the process is running. Read it as "everything at or below this
// height is PROVEN chainlocked" and treat anything above as unknown — never
// as "not chainlocked".
// TODO: not updated on new blocks after sync has finished
@Entity(tableName = "blockchain_state")
data class BlockchainState(var bestChainDate: Date?,
                           var bestChainHeight: Int,
                           var replaying: Boolean,
                           var impediments: MutableSet<Impediment>,
                           var chainlockHeight: Int,
                           var mnlistHeight: Int,
                           var percentageSync: Int) {

    @JvmOverloads
    constructor(replaying: Boolean = false) : this(null, 0, replaying,
            EnumSet.noneOf(Impediment::class.java), 0, 0, 0)

    @PrimaryKey
    var id = 1
        set(@Suppress("UNUSED_PARAMETER") value) {
            field = 1
        }

    enum class Impediment {
        STORAGE, NETWORK, SECURITY
    }

    fun syncFailed(): Boolean {
        return impediments.contains(Impediment.NETWORK)
    }

    fun isSynced(): Boolean {
        return !replaying && percentageSync == 100 && !syncFailed()
    }

    /**
     * Whether a screen must keep a fixed-amount payment blocked. Until the wallet's first scan
     * has finished ([firstSyncDone] false) anything short of synced blocks. After it, a wallet
     * that is only catching up may pay, but a sync failure (engine error, no network) still
     * blocks: the send itself would be refused (thepastaclaw on #1721).
     */
    fun blocksFixedPayment(firstSyncDone: Boolean): Boolean {
        return syncFailed() || (!firstSyncDone && (replaying || !isSynced()))
    }
}
