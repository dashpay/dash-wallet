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

package de.schildbach.wallet.service.platform.sdk

import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence.ConfidenceType
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// ── Transaction-level parity breakdown ────────────────────────────────
//
// The `L1Parity` line carries only totals (`tx sdk=28597 dashj=33296`), and
// the two counts measure different things: dashj counts every transaction
// in its wallet, the SDK counts distinct txids over its TXO rows. This
// breakdown says WHICH transactions differ and whether any of them carry
// money.
//
// It is limited to dashj's own history: transactions at or below dashj's
// `lastBlockSeenHeight` plus its unconfirmed ones. On an upgraded wallet
// dashj built that history independently of the SDK up to the cutover
// (where a held dashj stops), so the comparison is meaningful whether or
// not dashj is still running.

private val log = LoggerFactory.getLogger("ParityBreakdown")

/** [DashjTxFacts.height] for a transaction that is not in a block (pending or dead). */
const val NO_BLOCK = -1

/**
 * The few fields of one dashj wallet transaction the breakdown needs —
 * collected once per run by [collectDashjBreakdownFacts], never a copy of
 * the transaction.
 *
 * The classification fields ([netDuffs], [coinJoinType], [dashPayContact],
 * [special], [timeMs]) are only filled for transactions the SDK lacks; for
 * shared ones they stay at their defaults and are never read.
 *
 * @property txid dashj's hash object itself (shared with the wallet, not copied).
 * @property height the block height, or [NO_BLOCK].
 * @property dead dashj marked it DEAD (overridden by a double spend).
 * @property netDuffs `tx.getValue(wallet)`: what the transaction did to the wallet's balance.
 * @property coinJoinType dashj's own classification ([CoinJoinTransactionType.fromTx]).
 * @property dashPayContact an output belongs to a DashPay contact's keychain
 *   (`Wallet.getFriendFromTransaction`), in either direction.
 * @property special a short tag for coinbase and special (DIP-2) transactions, else null.
 */
data class DashjTxFacts(
    val txid: Sha256Hash,
    val height: Int,
    val dead: Boolean = false,
    val netDuffs: Long = 0L,
    val timeMs: Long = 0L,
    val coinJoinType: CoinJoinTransactionType = CoinJoinTransactionType.None,
    val dashPayContact: Boolean = false,
    val special: String? = null
)

/** What [collectDashjBreakdownFacts] read from the dashj wallet. */
class DashjBreakdownFacts(
    val lastBlockSeenHeight: Int,
    val txs: List<DashjTxFacts>,
    /** Transactions whose classification threw; they are counted with default (non-CoinJoin, non-DashPay) fields. */
    val classifyFailures: Int = 0
)

/**
 * The groups a dashj-only transaction falls into, in precedence order: the
 * first that matches wins. [MOVES_MONEY] and [OTHER] are what is left.
 */
internal enum class DashjOnlyGroup(val label: String) {
    COINJOIN_MIXING("mixing"),
    COINJOIN_CREATE_DENOMINATIONS("create-denominations"),
    COINJOIN_MAKE_COLLATERAL("make-collateral"),
    COINJOIN_MIXING_FEE("mixing-fee"),
    COINJOIN_COMBINE_DUST("combine-dust"),
    DASHPAY_CONTACT("dashpay-contact"),
    UNCONFIRMED("unconfirmed"),
    MOVES_MONEY("moves-money"),
    OTHER("other");

    val isCoinJoin: Boolean get() = ordinal <= COINJOIN_COMBINE_DUST.ordinal
}

/**
 * Which [DashjOnlyGroup] a dashj-only transaction belongs to. CoinJoin uses
 * dashj's own types; a CoinJoin `Send` (a payment spending mixed coins) is a
 * real payment, not mixing, so it falls through like `None`.
 */
internal fun dashjOnlyGroupOf(tx: DashjTxFacts): DashjOnlyGroup = when (tx.coinJoinType) {
    CoinJoinTransactionType.Mixing -> DashjOnlyGroup.COINJOIN_MIXING
    CoinJoinTransactionType.CreateDenomination -> DashjOnlyGroup.COINJOIN_CREATE_DENOMINATIONS
    CoinJoinTransactionType.MakeCollateralInputs -> DashjOnlyGroup.COINJOIN_MAKE_COLLATERAL
    CoinJoinTransactionType.MixingFee -> DashjOnlyGroup.COINJOIN_MIXING_FEE
    CoinJoinTransactionType.CombineDust -> DashjOnlyGroup.COINJOIN_COMBINE_DUST
    else -> when {
        tx.dashPayContact -> DashjOnlyGroup.DASHPAY_CONTACT
        tx.height == NO_BLOCK -> DashjOnlyGroup.UNCONFIRMED
        tx.netDuffs != 0L -> DashjOnlyGroup.MOVES_MONEY
        else -> DashjOnlyGroup.OTHER
    }
}

/** A short "what is this" for a listed transaction: `sent`, `received`, `coinjoin-send/sent`, `asset_lock/sent`. */
internal fun dashjTxHint(tx: DashjTxFacts): String {
    val parts = ArrayList<String>(3)
    tx.special?.let { parts += it }
    if (tx.coinJoinType == CoinJoinTransactionType.Send) parts += "coinjoin-send"
    when {
        tx.netDuffs > 0 -> parts += "received"
        tx.netDuffs < 0 -> parts += "sent"
    }
    return if (parts.isEmpty()) "-" else parts.joinToString("/")
}

internal data class GroupTotal(val count: Int, val netDuffs: Long)

internal data class MoneyTx(
    val txidHex: String,
    val height: Int,
    val netDuffs: Long,
    val timeMs: Long,
    val hint: String
)

internal data class SdkOnlyTx(val txidHex: String, val height: Int)

/**
 * The result of [computeParityBreakdown].
 *
 * @property dashjConsidered dashj transactions at or below [dashjLastBlockSeenHeight] plus its unconfirmed ones.
 * @property dashjUnconfirmed how many of [dashjConsidered] are not in a block.
 * @property dashjAboveLastBlock dashj transactions in a block above its own last block — excluded (expected 0).
 * @property sdkTotal every distinct txid in the SDK's TXO rows.
 * @property shared dashj-considered txids the SDK also has (at any height).
 * @property dashjOnlyGroups every [DashjOnlyGroup], in enum order, zeros included.
 * @property dashjOnlyDead dashj-only transactions dashj marked DEAD, across all groups.
 * @property movesMoney the [DashjOnlyGroup.MOVES_MONEY] transactions, largest |net| first, capped.
 * @property sdkOnlyAtOrBelow SDK-only txids with a block height at or below [dashjLastBlockSeenHeight].
 * @property sdkOnlyAbove SDK-only txids above it — expected once dashj is held.
 * @property sdkOnlyNoHeight SDK-only txids with no height (unconfirmed, or no transactions row).
 * @property sdkOnlyExamples from [sdkOnlyAtOrBelow], lowest height first, capped.
 */
internal data class ParityBreakdown(
    val dashjLastBlockSeenHeight: Int,
    val dashjConsidered: Int,
    val dashjUnconfirmed: Int,
    val dashjAboveLastBlock: Int,
    val sdkTotal: Int,
    val shared: Int,
    val dashjOnlyGroups: Map<DashjOnlyGroup, GroupTotal>,
    val dashjOnlyDead: Int,
    val movesMoney: List<MoneyTx>,
    val sdkOnlyAtOrBelow: Int,
    val sdkOnlyAbove: Int,
    val sdkOnlyNoHeight: Int,
    val sdkOnlyExamples: List<SdkOnlyTx>
) {
    val dashjOnly: Int get() = dashjOnlyGroups.values.sumOf { it.count }
    val dashjOnlyNetDuffs: Long get() = dashjOnlyGroups.values.sumOf { it.netDuffs }
    val sdkOnly: Int get() = sdkOnlyAtOrBelow + sdkOnlyAbove + sdkOnlyNoHeight
    fun group(g: DashjOnlyGroup): GroupTotal = dashjOnlyGroups[g] ?: GroupTotal(0, 0L)
}

/**
 * The pure core of the breakdown: set arithmetic and grouping, no wallet.
 *
 * @param dashjTxs every dashj wallet transaction (any height; those above
 *   [dashjLastBlockSeenHeight] are counted and excluded).
 * @param sdkTxidHeights every SDK txid with its block height; 0 or less
 *   means no height.
 */
internal fun computeParityBreakdown(
    dashjTxs: List<DashjTxFacts>,
    sdkTxidHeights: Map<Sha256Hash, Int>,
    dashjLastBlockSeenHeight: Int,
    maxMoneyTxs: Int = PARITY_BREAKDOWN_MAX_MONEY_TXS,
    maxSdkExamples: Int = PARITY_BREAKDOWN_MAX_SDK_EXAMPLES
): ParityBreakdown {
    // References to the facts' own hash objects — no copies.
    val dashjTxids = HashSet<Sha256Hash>(dashjTxs.size * 4 / 3 + 1)
    val counts = IntArray(DashjOnlyGroup.values().size)
    val nets = LongArray(counts.size)
    val money = ArrayList<DashjTxFacts>()
    var considered = 0
    var unconfirmed = 0
    var above = 0
    var shared = 0
    var dead = 0
    for (tx in dashjTxs) {
        dashjTxids += tx.txid
        if (tx.height != NO_BLOCK && tx.height > dashjLastBlockSeenHeight) {
            above++
            continue
        }
        considered++
        if (tx.height == NO_BLOCK) unconfirmed++
        if (tx.txid in sdkTxidHeights) {
            shared++
            continue
        }
        val group = dashjOnlyGroupOf(tx)
        counts[group.ordinal]++
        nets[group.ordinal] += tx.netDuffs
        if (tx.dead) dead++
        if (group == DashjOnlyGroup.MOVES_MONEY) money += tx
    }

    var sdkAtOrBelow = 0
    var sdkAbove = 0
    var sdkNoHeight = 0
    val sdkExamples = ArrayList<SdkOnlyTx>()
    for ((txid, height) in sdkTxidHeights) {
        if (txid in dashjTxids) continue
        when {
            height <= 0 -> sdkNoHeight++
            height <= dashjLastBlockSeenHeight -> {
                sdkAtOrBelow++
                sdkExamples += SdkOnlyTx(txid.toString(), height)
            }
            else -> sdkAbove++
        }
    }

    return ParityBreakdown(
        dashjLastBlockSeenHeight = dashjLastBlockSeenHeight,
        dashjConsidered = considered,
        dashjUnconfirmed = unconfirmed,
        dashjAboveLastBlock = above,
        sdkTotal = sdkTxidHeights.size,
        shared = shared,
        dashjOnlyGroups = DashjOnlyGroup.values().associateWith { GroupTotal(counts[it.ordinal], nets[it.ordinal]) },
        dashjOnlyDead = dead,
        movesMoney = money
            .sortedWith(
                compareByDescending<DashjTxFacts> { abs(it.netDuffs) }
                    .thenBy { it.height }
                    .thenBy { it.txid.toString() }
            )
            .take(maxMoneyTxs)
            .map { MoneyTx(it.txid.toString(), it.height, it.netDuffs, it.timeMs, dashjTxHint(it)) },
        sdkOnlyAtOrBelow = sdkAtOrBelow,
        sdkOnlyAbove = sdkAbove,
        sdkOnlyNoHeight = sdkNoHeight,
        sdkOnlyExamples = sdkExamples
            .sortedWith(compareBy<SdkOnlyTx> { it.height }.thenBy { it.txidHex })
            .take(maxSdkExamples)
    )
}

private fun signed(duffs: Long): String = if (duffs > 0) "+$duffs" else duffs.toString()

private fun heightText(height: Int): String = if (height == NO_BLOCK) "none" else height.toString()

private fun isoSeconds(ms: Long): String =
    if (ms <= 0L) "unknown" else Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

/**
 * The multi-line `ParityBreakdown` block, written to the wallet log and kept
 * (as text) for the support report. Net values are in duffs.
 */
internal fun parityBreakdownLog(
    b: ParityBreakdown,
    computedAtMs: Long,
    durationMs: Long,
    trigger: String,
    classifyFailures: Int = 0
): String = buildString {
    append("ParityBreakdown computedAt=").append(isoSeconds(computedAtMs))
    append(" took=").append(durationMs).append("ms trigger=").append(trigger)
    append(" dashjLastBlockSeenHeight=").append(b.dashjLastBlockSeenHeight)
    append("\n  dashj txids considered=").append(b.dashjConsidered)
    append(" (unconfirmed=").append(b.dashjUnconfirmed)
    append(", above last block excluded=").append(b.dashjAboveLastBlock).append(')')
    append(" | sdk txids=").append(b.sdkTotal)
    append(" | shared=").append(b.shared)
    append("\n  dashj-only=").append(b.dashjOnly)
    append(" net=").append(signed(b.dashjOnlyNetDuffs))
    append(" (dead=").append(b.dashjOnlyDead)
    if (classifyFailures > 0) append(", classify failures=").append(classifyFailures)
    append(')')
    val coinJoin = DashjOnlyGroup.values().filter { it.isCoinJoin }
    append("\n    coinjoin=").append(coinJoin.sumOf { b.group(it).count })
    append(" net=").append(signed(coinJoin.sumOf { b.group(it).netDuffs })).append(':')
    append(
        coinJoin.joinToString(",") {
            " ${it.label}=${b.group(it).count} net=${signed(b.group(it).netDuffs)}"
        }
    )
    for (g in DashjOnlyGroup.values().filterNot { it.isCoinJoin }) {
        append("\n    ").append(g.label).append('=').append(b.group(g).count)
        append(" net=").append(signed(b.group(g).netDuffs))
    }
    val money = b.group(DashjOnlyGroup.MOVES_MONEY).count
    append("\n  moves-money txids (").append(b.movesMoney.size).append(" of ").append(money)
    append(", largest |net| first):")
    if (b.movesMoney.isEmpty()) append(" none")
    for (m in b.movesMoney) {
        append("\n    ").append(m.txidHex)
        append(" height=").append(heightText(m.height))
        append(" net=").append(signed(m.netDuffs))
        append(" date=").append(isoSeconds(m.timeMs))
        append(' ').append(m.hint)
    }
    append("\n  sdk-only=").append(b.sdkOnly)
    append(": at/below dashj last block=").append(b.sdkOnlyAtOrBelow)
    append(", above=").append(b.sdkOnlyAbove)
    append(", no height=").append(b.sdkOnlyNoHeight)
    append("\n  sdk-only at/below examples (").append(b.sdkOnlyExamples.size)
    append(" of ").append(b.sdkOnlyAtOrBelow).append(", lowest height first):")
    if (b.sdkOnlyExamples.isEmpty()) append(" none")
    for (s in b.sdkOnlyExamples) {
        append("\n    ").append(s.txidHex).append(" height=").append(s.height)
    }
}

// ── dashj side ────────────────────────────────────────────────────────

/**
 * Read the breakdown's dashj side in ONE pass.
 *
 * `getTransactionList(true)` is a single snapshot of transaction REFERENCES
 * taken under the wallet's read lock (the lock itself is not reachable from
 * outside dashj) — the same set `getTransactionCount(true)` counts. Each
 * transaction then yields one small [DashjTxFacts]; nothing is wrapped or
 * copied. Only transactions the SDK lacks ([sdkTxids]) are classified, so a
 * mostly-matching wallet pays for the expensive calls (`getValue`, the
 * CoinJoin heuristics, the contact-keychain lookup) on the difference only.
 *
 * Blocking and CPU-bound: call off the main thread.
 */
internal fun collectDashjBreakdownFacts(wallet: Wallet, sdkTxids: Set<Sha256Hash>): DashjBreakdownFacts {
    val lastBlockSeenHeight = wallet.lastBlockSeenHeight
    val context = wallet.context
    val txs = wallet.getTransactionList(true)
    val out = ArrayList<DashjTxFacts>(txs.size)
    var failures = 0
    for (tx in txs) {
        val confidence = tx.getConfidence(context)
        val confidenceType = confidence.confidenceType
        val height = if (confidenceType == ConfidenceType.BUILDING) confidence.appearedAtChainHeight else NO_BLOCK
        val dead = confidenceType == ConfidenceType.DEAD
        val txid = tx.txId
        if (txid in sdkTxids) {
            out += DashjTxFacts(txid, height, dead)
            continue
        }
        out += try {
            DashjTxFacts(
                txid = txid,
                height = height,
                dead = dead,
                netDuffs = tx.getValue(wallet).value,
                timeMs = tx.updateTime?.time ?: 0L,
                coinJoinType = CoinJoinTransactionType.fromTx(tx, wallet),
                dashPayContact = wallet.getFriendFromTransaction(tx) != null,
                special = specialTag(tx)
            )
        } catch (e: Exception) {
            failures++
            if (failures == 1) log.info("ParityBreakdown: could not classify {}", txid, e)
            DashjTxFacts(txid, height, dead)
        }
    }
    return DashjBreakdownFacts(lastBlockSeenHeight, out, failures)
}

private fun specialTag(tx: Transaction): String? = when {
    tx.isCoinBase -> "coinbase"
    tx.type.isSpecial -> tx.type.name.removePrefix("TRANSACTION_").lowercase()
    else -> null
}

// ── Keeping the latest result ─────────────────────────────────────────

/** The latest breakdown as the support report shows it: when, and the [parityBreakdownLog] text. */
internal data class StoredParityBreakdown(val computedAtMs: Long, val text: String)

/**
 * The latest [StoredParityBreakdown] in one small file (a few KB), so a
 * support report has something to fall back on after a restart, or when a
 * fresh run is not possible. Line 1 is the computed-at time in ms; the rest
 * is the text. Never throws: an unreadable file reads as none.
 */
internal class ParityBreakdownStore(private val file: File) {

    fun load(): StoredParityBreakdown? = try {
        if (!file.isFile) {
            null
        } else {
            val content = file.readText()
            val newline = content.indexOf('\n')
            val computedAtMs = if (newline > 0) content.substring(0, newline).toLongOrNull() else null
            computedAtMs?.let { StoredParityBreakdown(it, content.substring(newline + 1)) }
        }
    } catch (e: Exception) {
        log.info("ParityBreakdown: could not read {}", file, e)
        null
    }

    fun save(stored: StoredParityBreakdown) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText("${stored.computedAtMs}\n${stored.text}")
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            log.info("ParityBreakdown: could not write {}", file, e)
        }
    }
}

/** Why a breakdown ran: hourly alongside the diagnostic parity probe, or for a support report. */
internal enum class ParityBreakdownTrigger(val label: String) {
    PROBE("probe"),
    REPORT("report")
}

/**
 * Whether the hourly [ParityBreakdownTrigger.PROBE] run is due, given when
 * this process last completed one (null = not yet).
 */
internal fun parityBreakdownDue(
    nowMs: Long,
    lastRunMs: Long?,
    intervalMs: Long = PARITY_BREAKDOWN_PROBE_INTERVAL_MS
): Boolean =
    lastRunMs == null ||
        nowMs < lastRunMs || // the clock went back: don't wait out a bogus interval
        nowMs - lastRunMs >= intervalMs

/**
 * Whether a support report gets a breakdown: always once the cutover is
 * committed (dashj held or diagnostic), and before it only with the dashj
 * sync diagnostic on — the same audience the parity log is for.
 */
internal fun parityBreakdownAppliesToReport(cutoverCommitted: Boolean, dashjDiagnosticEnabled: Boolean): Boolean =
    cutoverCommitted || dashjDiagnosticEnabled

/** How one breakdown run ended. */
internal sealed class ParityBreakdownRunResult {
    data class Done(val stored: StoredParityBreakdown) : ParityBreakdownRunResult()

    /** One side could not be read, e.g. `dashj wallet not available`. */
    data class Unavailable(val what: String) : ParityBreakdownRunResult()

    /** The run threw; [what] is the exception's class and message. */
    data class Failed(val what: String) : ParityBreakdownRunResult()
}

/**
 * The breakdown a support report shows.
 *
 * @property stored the result shown: fresh when [notRefreshedReason] is null,
 *   otherwise the last stored one (or none).
 * @property notRefreshedReason why no fresh result: `SDK not synced`,
 *   `timed out after 30s`, `failed (…)`. Null for a fresh result.
 * @property applicable false when this report gets no breakdown at all
 *   ([parityBreakdownAppliesToReport]); [notRefreshedReason] then says why.
 */
internal data class ReportParityBreakdown(
    val stored: StoredParityBreakdown?,
    val notRefreshedReason: String?,
    val applicable: Boolean = true
) {
    companion object {
        const val NOT_APPLICABLE_REASON = "not computed before the cutover with the dashj sync diagnostic off"

        val NOT_APPLICABLE = ReportParityBreakdown(null, NOT_APPLICABLE_REASON, applicable = false)
    }
}

/** The `--- latest parity breakdown ---` section of `dashJ-kotlin-parity-log.txt`. */
internal fun parityBreakdownReportSection(
    report: ReportParityBreakdown,
    formatDate: (Long) -> String
): String = buildString {
    append("\n--- latest parity breakdown ---\n")
    val stored = if (report.applicable) report.stored else null
    val reason = report.notRefreshedReason
    when {
        stored == null -> append("none (").append(reason ?: "never computed").append(")\n")
        else -> {
            if (reason == null) {
                append("computed: ").append(formatDate(stored.computedAtMs)).append(" (fresh, for this report)\n")
            } else {
                append("not refreshed: ").append(reason).append(" — showing the last stored result\n")
                append("computed: ").append(formatDate(stored.computedAtMs)).append('\n')
            }
            append(stored.text).append('\n')
        }
    }
}

/** The hourly cadence of the diagnostic run ([ParityBreakdownTrigger.PROBE]). */
internal const val PARITY_BREAKDOWN_PROBE_INTERVAL_MS = 60 * 60_000L

/** How long a support report waits for a fresh breakdown before falling back. */
internal const val PARITY_BREAKDOWN_REPORT_TIMEOUT_MS = 30_000L

internal const val PARITY_BREAKDOWN_MAX_MONEY_TXS = 50
internal const val PARITY_BREAKDOWN_MAX_SDK_EXAMPLES = 20
