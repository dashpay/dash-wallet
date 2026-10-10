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

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Deferred
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
    /**
     * Whether [netDuffs] was actually read from the wallet. FALSE means "not
     * computed", which is NOT the same as a net of zero — the value comparison
     * skips these rather than treating them as 0 and reporting a false
     * mismatch. `getValue` can throw, and a row whose value threw must not be
     * read as agreeing with, or differing from, anything.
     */
    val valueKnown: Boolean = false,
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
 * What the SDK store holds for one txid: its block height, and its stored net.
 *
 * [netDuffs] is NULL when the txid is known from `txos`/`pending_inputs` but has
 * no `transactions` row, so no net is stored. Null means UNKNOWN, never zero —
 * the value comparison skips these.
 */
data class SdkTxFacts(val height: Int, val netDuffs: Long?)

/**
 * One shared txid whose two engines disagree on the money. [dashjDuffs] is
 * `tx.getValue(wallet)`; [sdkDuffs] is the SDK store's `transactions.netAmount`.
 */
internal data class ValueDiffTx(
    val txidHex: String,
    val height: Int,
    val dashjDuffs: Long,
    val sdkDuffs: Long
) {
    /** SDK minus dashj: positive means the SDK stores MORE than dashj computes. */
    val deltaDuffs: Long get() = sdkDuffs - dashjDuffs
}

/**
 * The result of [computeParityBreakdown].
 *
 * @property dashjConsidered dashj transactions at or below [dashjLastBlockSeenHeight] plus its unconfirmed ones.
 * @property dashjUnconfirmed how many of [dashjConsidered] are not in a block.
 * @property dashjAboveLastBlock dashj transactions in a block above its own last block — excluded (expected 0).
 * @property sdkTotal every distinct SDK txid of the wallet: its TXO rows' funding and
 *   spending txids plus its reserved (`pending_inputs`) spenders.
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
    val sdkOnlyExamples: List<SdkOnlyTx>,
    /**
     * Shared txids whose values could actually be compared — both engines had a
     * value, the row is confirmed, not dead, and at or below dashj's last block.
     * Reported alongside [valueDiffers] so that a zero cannot be read as "the
     * two engines agree" when the truth is "nothing was compared".
     */
    val valueCompared: Int = 0,
    /** Of [valueCompared], how many disagree. */
    val valueDiffers: Int = 0,
    /**
     * Eligible shared transactions whose value could NOT be compared because one
     * side had none — dashj's `getValue` threw, or the SDK has no `transactions`
     * row. Never counted as agreement.
     */
    val valueUnknown: Int = 0,
    /** Sum over the differing rows of (SDK net - dashj net). */
    val valueDeltaDuffs: Long = 0L,
    /** The largest disagreements, |delta| first. */
    val valueDiffExamples: List<ValueDiffTx> = emptyList()
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
 * @param sdkTxidHeights every SDK txid with its stored height and net; a height
 *   of 0 or less means no height, and a null net means no stored value.
 *
 * Shared txids are compared on VALUE, which is the whole point of a parity
 * check and what this breakdown originally skipped: a txid present in both
 * engines was counted and dropped, so a wallet whose two engines disagreed
 * about the money still reported a clean sheet.
 *
 * Only the net is compared. DIRECTION IS DELIBERATELY NOT COMPARED: it is a
 * derived label, and the two engines label the same money differently on a
 * large fraction of an ordinary CoinJoin wallet (4795 rows on one measured
 * mainnet wallet, every one of them with identical values). Comparing it would
 * bury a real finding under thousands of non-findings.
 */
internal fun computeParityBreakdown(
    dashjTxs: List<DashjTxFacts>,
    sdkTxidHeights: Map<Sha256Hash, SdkTxFacts>,
    dashjLastBlockSeenHeight: Int,
    maxMoneyTxs: Int = PARITY_BREAKDOWN_MAX_MONEY_TXS,
    maxSdkExamples: Int = PARITY_BREAKDOWN_MAX_SDK_EXAMPLES,
    maxValueDiffs: Int = PARITY_BREAKDOWN_MAX_VALUE_DIFFS
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
    var valueCompared = 0
    var valueUnknown = 0
    var valueDelta = 0L
    val valueDiffs = ArrayList<ValueDiffTx>()
    for (tx in dashjTxs) {
        dashjTxids += tx.txid
        if (tx.height != NO_BLOCK && tx.height > dashjLastBlockSeenHeight) {
            above++
            continue
        }
        considered++
        if (tx.height == NO_BLOCK) unconfirmed++
        val sdk = sdkTxidHeights[tx.txid]
        if (sdk != null) {
            shared++
            val sdkNet = sdk.netDuffs
            // Compare only where both sides genuinely have a value AND dashj
            // could know about the transaction at all. An unconfirmed or dead
            // row, or one above dashj's last block, proves nothing.
            if (!tx.dead && tx.height != NO_BLOCK) {
                // Eligible: dashj could be expected to agree about this one.
                // The SDK side must be confirmed too (height > 0). An SDK row
                // still carrying height 0 holds the net of an unconfirmed
                // transaction, which the engine revises when it lands; against
                // a confirmed dashj row that reads as a disagreement when it is
                // only a race.
                if (tx.valueKnown && sdkNet != null && sdk.height > 0) {
                    valueCompared++
                    if (tx.netDuffs != sdkNet) {
                        valueDelta += sdkNet - tx.netDuffs
                        valueDiffs += ValueDiffTx(tx.txid.toString(), tx.height, tx.netDuffs, sdkNet)
                    }
                } else {
                    // Eligible but unvalued, or not yet confirmed on the SDK
                    // side. NOT agreement — counted separately so a partly
                    // valued history cannot pass as parity.
                    valueUnknown++
                }
            }
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
    for ((txid, facts) in sdkTxidHeights) {
        if (txid in dashjTxids) continue
        val height = facts.height
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
            .take(maxSdkExamples),
        valueCompared = valueCompared,
        valueUnknown = valueUnknown,
        valueDiffers = valueDiffs.size,
        valueDeltaDuffs = valueDelta,
        valueDiffExamples = valueDiffs
            .sortedWith(
                compareByDescending<ValueDiffTx> { abs(it.deltaDuffs) }
                    .thenBy { it.height }
                    .thenBy { it.txidHex }
            )
            .take(maxValueDiffs)
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
    append("\n  value: compared=").append(b.valueCompared)
    append(" differs=").append(b.valueDiffers)
    if (b.valueUnknown > 0) append(" unknown=").append(b.valueUnknown)
    if (b.valueDiffers > 0) {
        append(" delta=").append(signed(b.valueDeltaDuffs))
        append(" (sdk-dashj, largest |delta| first):")
        for (d in b.valueDiffExamples) {
            append("\n    ").append(d.txidHex).append(" h=").append(d.height)
            append(" dashj=").append(signed(d.dashjDuffs))
            append(" sdk=").append(signed(d.sdkDuffs))
            append(" delta=").append(signed(d.deltaDuffs))
        }
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

// ── SDK side ──────────────────────────────────────────────────────────

/**
 * Every distinct SDK txid of wallet [walletId] (wire bytes) with its
 * `transactions.blockHeight` (0 = none: unconfirmed, or no transactions row),
 * keyed the way dashj keys its transactions. Blocking: call off the main
 * thread.
 *
 * The set is the wallet's TXO rows' funding and spending txids (what the
 * `L1Parity` line's `sdkTxCount` counts) plus the spenders the wallet has
 * RESERVED in `pending_inputs`: a change-less send has no TXO row of its own
 * and its spent marks only land at confirmation, so until then the
 * reservation is its only wallet-scoped trace — the same rule
 * `SdkTxStoreWalker` uses for membership. Not the whole `transactions`
 * table: it has no walletId column, so it is not this wallet's set.
 */
internal fun querySdkTxidHeights(db: SupportSQLiteDatabase, walletId: ByteArray): Map<Sha256Hash, SdkTxFacts> =
    db.query(
        SimpleSQLiteQuery(
            "SELECT u.t, tx.blockHeight, tx.netAmount FROM (" +
                "SELECT txid AS t FROM txos WHERE walletId = ? AND txid IS NOT NULL " +
                "UNION " +
                "SELECT spendingTxid AS t FROM txos WHERE walletId = ? AND spendingTxid IS NOT NULL " +
                "UNION " +
                "SELECT spendingTxid AS t FROM pending_inputs WHERE walletId = ?) u " +
                "LEFT JOIN transactions tx ON tx.txid = u.t",
            arrayOf<Any?>(walletId, walletId, walletId)
        )
    ).use { cursor ->
        // Read straight off the cursor into one map.
        val out = HashMap<Sha256Hash, SdkTxFacts>(cursor.count * 4 / 3 + 1)
        while (cursor.moveToNext()) {
            val wire = cursor.getBlob(0) ?: continue
            if (wire.size != 32) continue
            // Room stores wire order; dashj's Sha256Hash is display order.
            // A LEFT JOIN miss leaves BOTH null: no `transactions` row, so no
            // height and — importantly — no stored net. Null net means unknown,
            // which the comparison skips; it must not become a zero.
            out[Sha256Hash.wrapReversed(wire)] = SdkTxFacts(
                height = if (cursor.isNull(1)) 0 else cursor.getInt(1),
                netDuffs = if (cursor.isNull(2)) null else cursor.getLong(2)
            )
        }
        out
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
            // Shared with the SDK. The classification fields describe dashj-ONLY
            // rows and stay unread, but the VALUE is now needed: it is half of
            // the parity comparison. Reading it is the cost of this check, and
            // it is the cheap half — `getValue` walks the tx, the classifiers
            // walk the wallet.
            out += try {
                DashjTxFacts(txid, height, dead, netDuffs = tx.getValue(wallet).value, valueKnown = true)
            } catch (e: Exception) {
                failures++
                if (failures == 1) log.info("ParityBreakdown: could not value {}", txid, e)
                DashjTxFacts(txid, height, dead)
            }
            continue
        }
        out += try {
            DashjTxFacts(
                txid = txid,
                height = height,
                dead = dead,
                netDuffs = tx.getValue(wallet).value,
                valueKnown = true,
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

/**
 * The latest breakdown as the support report shows it: which SDK wallet it
 * was computed for, when, and the [parityBreakdownLog] text (txids and
 * amounts). A result is only ever shown for the wallet it came from.
 */
/**
 * The structured verdict of one breakdown, so a caller can ask "did this find
 * anything?" without parsing the log text.
 *
 * [clean] deliberately requires that the value half ACTUALLY RAN: a breakdown
 * that compared no values has not established parity, it has only failed to
 * disprove it. Treating those as equivalent is how a check comes to mean
 * nothing.
 */
data class ParityBreakdownFindings(
    val dashjOnly: Int,
    val sdkOnly: Int,
    val valueCompared: Int,
    val valueDiffers: Int,
    /**
     * Shared transactions that were ELIGIBLE for a value comparison — confirmed,
     * not dead, at or below dashj's last block — but where one side had no value
     * to compare. They are excluded from [valueDiffers] rather than counted as
     * agreement, and they block [clean]: a history that is only partly valued
     * cannot establish parity.
     */
    val valueUnknown: Int = 0
) {
    /**
     * The sets agree, every eligible shared value was compared, and they all
     * agreed.
     *
     * `valueCompared > 0` alone is NOT enough, and that was the original bug
     * here: with two eligible shared transactions, one compared equal and one
     * skipped because its value could not be read, the old condition reported a
     * clean sheet. The aggregate balance and count checks do not validate the
     * skipped transaction's stored net, so nothing else would have caught it.
     */
    val clean: Boolean
        get() = dashjOnly == 0 && sdkOnly == 0 && valueDiffers == 0 &&
            valueUnknown == 0 && valueCompared > 0

    /** Why this is not clean, for the log. Null when it is. */
    val reason: String?
        get() = when {
            dashjOnly > 0 || sdkOnly > 0 -> "sets differ (dashj-only=$dashjOnly sdk-only=$sdkOnly)"
            valueDiffers > 0 -> "$valueDiffers transaction value(s) differ"
            valueUnknown > 0 -> "$valueUnknown eligible value(s) could not be compared"
            valueCompared == 0 -> "no values could be compared"
            else -> null
        }
}

/** [ParityBreakdownFindings] with the moment it was computed, for freshness checks. */
data class TimedBreakdownFindings(val computedAtMs: Long, val findings: ParityBreakdownFindings)

/** The findings of [b], for a caller that needs the verdict rather than the text. */
internal fun findingsOf(b: ParityBreakdown): ParityBreakdownFindings = ParityBreakdownFindings(
    dashjOnly = b.dashjOnly,
    sdkOnly = b.sdkOnly,
    valueCompared = b.valueCompared,
    valueDiffers = b.valueDiffers,
    valueUnknown = b.valueUnknown
)

/**
 * @property findings null for a breakdown restored from [breakdownStore] after a
 *   restart, which carries only its text. A null findings set can never support
 *   a MATCH verdict — the conservative reading, on purpose.
 */
internal data class StoredParityBreakdown(
    val walletIdHex: String,
    val computedAtMs: Long,
    val text: String,
    val findings: ParityBreakdownFindings? = null
)

/**
 * The latest [StoredParityBreakdown] in one small file (a few KB), so a
 * support report has something to fall back on after a restart, or when a
 * fresh run is not possible. Line 1 is `<computed-at ms> <SDK wallet id>`;
 * the rest is the text. A file without a wallet id (an earlier format) reads
 * as none. Never throws: an unreadable file reads as none.
 *
 * Not thread-safe: the caller serializes [save] and [clear] (they share the
 * `.tmp` file).
 */
internal class ParityBreakdownStore(private val file: File) {

    private val tmp: File get() = File(file.path + ".tmp")

    fun load(): StoredParityBreakdown? = try {
        if (!file.isFile) {
            null
        } else {
            val content = file.readText()
            val newline = content.indexOf('\n')
            val header = if (newline > 0) content.substring(0, newline).split(' ') else emptyList()
            val computedAtMs = header.getOrNull(0)?.toLongOrNull()
            val walletIdHex = header.getOrNull(1)?.takeIf { header.size == 2 && it.isNotEmpty() }
            if (computedAtMs == null || walletIdHex == null) {
                log.info("ParityBreakdown: {} has no wallet id; ignoring it", file)
                null
            } else {
                StoredParityBreakdown(walletIdHex, computedAtMs, content.substring(newline + 1))
            }
        }
    } catch (e: Exception) {
        log.info("ParityBreakdown: could not read {}", file, e)
        null
    }

    fun save(stored: StoredParityBreakdown) {
        try {
            file.parentFile?.mkdirs()
            val tmp = this.tmp
            tmp.writeText("${stored.computedAtMs} ${stored.walletIdHex}\n${stored.text}")
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            log.info("ParityBreakdown: could not write {}", file, e)
        }
    }

    /** Delete the stored result (a wallet wipe). Never throws. */
    fun clear() {
        try {
            tmp.delete()
            if (file.exists() && !file.delete()) log.warn("ParityBreakdown: could not delete {}", file)
        } catch (e: Exception) {
            log.warn("ParityBreakdown: could not delete {}", file, e)
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
/**
 * Whether a finished breakdown is one the diagnostic verdict can actually use:
 * computed at or after the moment dashj reached 100 %.
 *
 * The same test `BlockchainServiceImpl` applies to the findings it reads, kept
 * here so the harness can apply it to its own cadence. A run that fails it is
 * not wrong — its dashj side is simply incomplete — but it can never become
 * the verdict, so it must not consume the hourly slot.
 */
internal fun parityBreakdownIsUsable(computedAtMs: Long, dashjCaughtUpAtMs: Long?): Boolean =
    dashjCaughtUpAtMs != null && computedAtMs >= dashjCaughtUpAtMs

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

/**
 * Why the SDK's transaction set is not yet complete up to dashj's comparison
 * height, or null when it is.
 *
 * The SDK's caught-up test (`ShadowSyncProgress.scanCaughtUpToTip`) watches
 * the filter scan only; the blocks it matched are downloaded and their
 * transactions imported afterwards. [sdkWalletHeight] is the wallet's
 * COMMITTED height (`ShadowSyncProgress.walletSyncedHeight`): every block at
 * or below it has been processed. Until it reaches
 * [dashjLastBlockSeenHeight], a transaction the SDK is still importing would
 * be counted dashj-only. An unknown height (0) cannot prove anything, so it
 * waits too. A dashj wallet that has seen no blocks is REFUSED outright: an
 * empty dashj side produces dashj-only=0 and sdk-only=0, which reads as
 * perfect parity and means nothing.
 */
internal fun sdkStillProcessingReason(sdkWalletHeight: Long, dashjLastBlockSeenHeight: Int): String? = when {
    // dashj has seen NO blocks. Previously this returned null ("nothing is
    // wrong, go ahead"), and the breakdown duly reported dashj-only=0,
    // sdk-only=0 against an empty dashj wallet — which reads as perfect parity
    // and means nothing at all. A verdict computed at 0% synced is worse than
    // no verdict, because it is indistinguishable from a good one.
    dashjLastBlockSeenHeight <= 0 ->
        "dashj wallet has seen no blocks (lastBlockSeenHeight=$dashjLastBlockSeenHeight)"
    sdkWalletHeight <= 0L ->
        "SDK still processing transactions (wallet height unknown, dashj $dashjLastBlockSeenHeight)"
    sdkWalletHeight < dashjLastBlockSeenHeight ->
        "SDK still processing transactions (wallet height $sdkWalletHeight < dashj $dashjLastBlockSeenHeight)"
    else -> null
}

/**
 * The breakdown run to join instead of starting another: any run that has not
 * COMPLETED. A run is published before it is started, and a LAZY deferred
 * reads `isActive == false` until then — testing `isActive` would let a
 * second caller start a second run in that gap.
 */
internal fun <T> inFlightParityBreakdown(run: Deferred<T>?): Deferred<T>? = run?.takeIf { !it.isCompleted }

/** [ParityBreakdownRunResult.Unavailable] for a run that finished after a wallet wipe. */
internal const val PARITY_BREAKDOWN_WIPED = "wallet wiped while computing"

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
 *   `SDK still processing transactions (…)`, `timed out after 30s`,
 *   `failed (…)`. Null for a fresh result.
 * @property applicable false when this report gets no breakdown at all
 *   ([parityBreakdownAppliesToReport], or [omitted]); [notRefreshedReason]
 *   then says why.
 * @property omitted the user chose not to share the application log, so the
 *   transaction-level breakdown (txids and amounts) is left out of the report
 *   and was not computed for it.
 */
internal data class ReportParityBreakdown(
    val stored: StoredParityBreakdown?,
    val notRefreshedReason: String?,
    val applicable: Boolean = true,
    val omitted: Boolean = false
) {
    companion object {
        const val NOT_APPLICABLE_REASON = "not computed before the cutover with the dashj sync diagnostic off"

        val NOT_APPLICABLE = ReportParityBreakdown(null, NOT_APPLICABLE_REASON, applicable = false)

        const val NOT_SHARED_REASON = "application log not shared"

        /** The application log is not shared: no breakdown, and it never attaches the parity log on its own. */
        val NOT_SHARED = ReportParityBreakdown(null, NOT_SHARED_REASON, applicable = false, omitted = true)
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
        report.omitted -> append("omitted (").append(reason ?: ReportParityBreakdown.NOT_SHARED_REASON).append(")\n")
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
internal const val PARITY_BREAKDOWN_MAX_VALUE_DIFFS = 20
