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

package de.schildbach.wallet.service

import de.schildbach.wallet.service.platform.sdk.ShadowSyncProgress

/**
 * The foreground blockchain service's IDLE detector, extracted from
 * [BlockchainServiceImpl]'s `ACTION_TIME_TICK` receiver so it is pure and
 * host-JVM testable — and so the SAMPLE SOURCE can follow whichever L1
 * engine is actually running.
 *
 * ## Why this had to move
 *
 * The detector's job is to let a genuinely idle sync service stop instead
 * of holding a wakelock and a foreground notification forever. It decides
 * that from four per-minute activity counters that were ALL dashj-fed:
 * blocks and headers from `blockChain`/`headerChain.bestChainHeight`
 * deltas, transactions from the dashj wallet listener, and mnlistdiffs
 * from the dashj peergroup.
 *
 * Post-cutover the dashj peergroup is HELD, so all four are permanently
 * zero no matter how hard the Kotlin SDK engine is syncing. The detector
 * then trips deterministically after [MIN_COLLECT_HISTORY] ticks —
 * roughly two minutes after every single start — and `stopSelf()`s a
 * service that is in the middle of the initial SDK scan.
 *
 * The fix is NOT to disable it: an idle service should still stop. It is
 * to sample the engine that owns L1 ([sdkActivitySample]) instead of the
 * one that is held, keeping the idle RULE ([isSyncIdle]) byte-identical.
 */
data class SyncActivitySample(
    val transactionsReceived: Int,
    val blocksDownloaded: Int,
    val headersDownloaded: Int,
    val mnListDiffsDownloaded: Int
) {
    /** The log format [BlockchainServiceImpl] has always printed. */
    override fun toString(): String =
        "$transactionsReceived/$blocksDownloaded/$headersDownloaded/$mnListDiffsDownloaded"
}

/** Minimum samples before an idle verdict may be reached at all. */
const val MIN_COLLECT_HISTORY = 2

/** Per-counter recency windows (index into the newest-first history). */
const val IDLE_HEADER_TIMEOUT_MIN = 2
const val IDLE_MNLIST_TIMEOUT_MIN = 2
const val IDLE_BLOCK_TIMEOUT_MIN = 2
const val IDLE_TRANSACTION_TIMEOUT_MIN = 9

/** How many samples the ring keeps. */
val MAX_HISTORY_SIZE = maxOf(IDLE_TRANSACTION_TIMEOUT_MIN, IDLE_BLOCK_TIMEOUT_MIN)

/**
 * Whether [history] (NEWEST FIRST, as [BlockchainServiceImpl] builds it)
 * shows no sync activity — the verdict that stops the service.
 *
 * Behaviour is deliberately IDENTICAL to the inline loop this replaces:
 * fewer than [MIN_COLLECT_HISTORY] samples is never idle, and any single
 * non-zero counter inside its own recency window makes the whole window
 * active. Pure — host-testable.
 */
fun isSyncIdle(history: List<SyncActivitySample>): Boolean {
    if (history.size < MIN_COLLECT_HISTORY) return false
    history.forEachIndexed { i, entry ->
        val blocksActive = entry.blocksDownloaded > 0 && i <= IDLE_BLOCK_TIMEOUT_MIN
        val transactionsActive = entry.transactionsReceived > 0 && i <= IDLE_TRANSACTION_TIMEOUT_MIN
        val headersActive = entry.headersDownloaded > 0 && i <= IDLE_HEADER_TIMEOUT_MIN
        val mnListDiffsActive = entry.mnListDiffsDownloaded > 0 && i <= IDLE_MNLIST_TIMEOUT_MIN
        if (blocksActive || transactionsActive || headersActive || mnListDiffsActive) return false
    }
    return true
}

/**
 * One activity sample taken from the KOTLIN SDK L1 engine — the
 * post-cutover replacement for the dashj counters, mapped onto the same
 * four slots so [isSyncIdle] is untouched:
 *
 * - blocks ← the wallet-relevant compact-FILTER scan position
 *   ([ShadowSyncProgress.filterHeight]); this is the SDK's analogue of
 *   dashj's block download, and it is what actually advances while the
 *   wallet is being scanned;
 * - headers ← [ShadowSyncProgress.headerHeight];
 * - mnlistdiffs ← [ShadowSyncProgress.mnListHeight];
 * - transactions ← [txEventsSinceLastSample], the count of engine
 *   wallet-events ([de.schildbach.wallet.service.platform.sdk.L1TxEvent])
 *   observed since the previous tick — the analogue of dashj's
 *   `transactionsReceived`.
 *
 * A null [previous] (the first tick of a session) yields an ALL-ZERO
 * sample rather than a bogus height-vs-zero delta, mirroring the dashj
 * receiver's own `lastChainHeight > 0 || lastHeaderHeight > 0` guard.
 * Height deltas are clamped at 0 so a re-scan (heights walking backwards)
 * reads as "no progress" instead of negative. Pure — host-testable.
 */
fun sdkActivitySample(
    previous: ShadowSyncProgress?,
    current: ShadowSyncProgress,
    txEventsSinceLastSample: Int
): SyncActivitySample {
    if (previous == null) {
        return SyncActivitySample(0, 0, 0, 0)
    }
    fun delta(now: Long, before: Long): Int = (now - before).coerceAtLeast(0L).toInt()
    return SyncActivitySample(
        transactionsReceived = txEventsSinceLastSample.coerceAtLeast(0),
        blocksDownloaded = delta(current.filterHeight, previous.filterHeight),
        headersDownloaded = delta(current.headerHeight, previous.headerHeight),
        mnListDiffsDownloaded = delta(current.mnListHeight, previous.mnListHeight)
    )
}
