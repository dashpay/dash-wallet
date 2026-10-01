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
 * The verdict that actually stops the service: idle counters AND no replay
 * in progress (docs/upgrade-memory-and-sync-plan.md, Phase 1b item 7).
 *
 * The SDK replay has multi-minute stretches with no filter advance —
 * matched-block download and processing for the CoinJoin years, and the
 * DashPay bring-up that used to precede SPV — during which every counter
 * reads zero. On the reference install the idle rule tore the engine down
 * nine times in one day and ten the next, and the SDK persisted its
 * watermark rarely enough that each teardown cost up to 155,000 blocks;
 * two days after the upgrade the replay was further from the tip than when
 * it started. The old dashj-era contract was "the service stays alive until
 * the replay finishes"; `replaying` only rescheduled a one-minute restart
 * AFTER the stop. It is now a guard that skips the stop. A genuinely stuck
 * engine is the SDK stall impediment's and the watchdog's problem, not this
 * rule's. Pure — host-testable.
 */
fun shouldStopForIdle(history: List<SyncActivitySample>, replaying: Boolean): Boolean =
    !replaying && isSyncIdle(history)

/**
 * The longest an in-flight SDK engine start keeps an idle service alive
 * ([engineStartHoldsService]). A healthy start is the DashPay bring-up
 * (budgeted at 20 s) plus the SPV client's load of its stored header chain,
 * which takes minutes on a large chain on a slow phone but not this long; past
 * it the start is treated as hung, and the idle rule applies again so a wedged
 * native call cannot pin the foreground service for good.
 */
const val ENGINE_START_KEEPALIVE_MS = 15L * 60 * 1000

/**
 * Whether an SDK engine start that began at [startingSinceMs] (0 = none in
 * flight; see `L1ShadowSyncService.engineStartingSinceMs`) should keep the
 * service from stopping for idleness. A start reports no progress at all, so
 * every counter reads zero and [isSyncIdle] trips about two minutes in —
 * field report, 2026-09-30: the service stopped two minutes after a restart
 * with the engine still loading its headers, the process was frozen without
 * its foreground service, and sync stood still for 14 minutes until the user
 * reopened the app. Capped at [capMs] so a hung start cannot hold the service
 * forever. Pure — host-testable.
 */
fun engineStartHoldsService(
    startingSinceMs: Long,
    nowMs: Long,
    capMs: Long = ENGINE_START_KEEPALIVE_MS
): Boolean = startingSinceMs > 0L && nowMs - startingSinceMs < capMs

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

/**
 * How far the SDK's durable synced height may trail the committed scan cursor
 * before it holds an idle service ([durableLagHoldsService]). The SDK persists
 * that height in 5,000-block steps, so a lag of a step or two is the normal
 * resting state; past this it is unpersisted work a stop would throw away.
 */
const val DURABLE_LAG_MARGIN_BLOCKS = 10_000L

/**
 * How long the durable height may stand still before the lag stops holding the
 * service. In the field report it advanced every couple of minutes while it
 * caught up, so this is generous for a draining persister and still bounds one
 * that has stopped.
 */
const val DURABLE_LAG_STALL_MS = 10L * 60 * 1000

/**
 * Whether the SDK's DURABLE synced height ([durableHeight], where a restart
 * resumes) trailing the committed scan cursor ([committedHeight]) should keep
 * the service from stopping for idleness. Once the scan reports SYNCED every
 * idle counter can read zero while the SDK is still persisting what it scanned;
 * a stop then re-walks everything unpersisted on the next start. Field report,
 * 2026-09-30: stopped 126,694 blocks behind, catching up at ~10,000 a minute.
 *
 * Holds only while the lag exceeds [marginBlocks] AND the durable height has
 * advanced within [stallMs] ([msSinceDurableAdvanced]), so a persister that has
 * stopped cannot pin the service. Unknown heights (0) never hold. Pure —
 * host-testable.
 */
fun durableLagHoldsService(
    durableHeight: Long,
    committedHeight: Long,
    msSinceDurableAdvanced: Long,
    marginBlocks: Long = DURABLE_LAG_MARGIN_BLOCKS,
    stallMs: Long = DURABLE_LAG_STALL_MS
): Boolean = durableHeight > 0L && committedHeight > 0L &&
    committedHeight - durableHeight > marginBlocks &&
    msSinceDurableAdvanced < stallMs

/**
 * Tracks when the durable synced height last moved, the input
 * [durableLagHoldsService] needs. The first observation counts as movement, so
 * a lag seen for the first time gets the full [DURABLE_LAG_STALL_MS] to prove it
 * is draining. Any change counts, a decrease included: a rescan rewinds it.
 * Not thread-safe; the service's tick is its only caller.
 */
class DurableHeightTracker {
    private var lastHeight = -1L
    private var lastChangeMs = 0L

    /** Record [height] observed at [nowMs]; returns ms since it last changed. */
    fun observe(height: Long, nowMs: Long): Long {
        if (height != lastHeight) {
            lastHeight = height
            lastChangeMs = nowMs
        }
        return nowMs - lastChangeMs
    }
}
