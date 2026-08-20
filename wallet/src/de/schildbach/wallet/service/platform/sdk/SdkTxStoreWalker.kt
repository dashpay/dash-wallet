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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.dashfoundation.dashsdk.persistence.DashDatabase

/**
 * SQL fragment: the `txos` row aliased [alias] is FOREIGN — its address belongs
 * to a watch-only DIP-15 external friendship account
 * (`accounts.accountType = 13`, `dashpayExternalAccount`): the derivation chain
 * WE pay a contact on, whose private keys only the CONTACT holds. The SDK
 * mirrors those outputs into `txos` so an outgoing contact payment is
 * displayable/attributable, but they are NEVER this wallet's money — and their
 * `isSpent` can only ever be flipped by the CONTACT's own spend, so from this
 * wallet's point of view the row stays "unspent" forever.
 *
 * Verified live (S22 testnet `s22test63b`, 11.10.73): the 0.69998912 drain
 * output to the contact sat in `txos` as `isSpent = 0` on a type-13 account,
 * so every ownership-naive read over the mirror (SUM → the 1.69998912 ↔ 1.0
 * balance duel, COUNT, membership, isMine) silently counted the contact's
 * money as ours. Any read that means "this wallet's outputs" must exclude
 * these rows with `AND NOT (${txoIsForeignSql("t")})`.
 *
 * The classification routes through `core_addresses` (PK = address, its
 * `accountId` is populated) rather than `txos.accountId` because the
 * persistence layer writes friendship TXOs with a NULL `accountId` — the same
 * verified fallback [SdkTxContactResolver.signedNetsFor] uses. A row whose
 * address maps to no account (or no `core_addresses` row at all) is treated as
 * owned — the pre-existing behavior for unclassifiable rows.
 */
internal fun txoIsForeignSql(alias: String): String =
    "EXISTS (SELECT 1 FROM core_addresses fca JOIN accounts fa ON fa.id = fca.accountId " +
        "WHERE fca.address = $alias.address " +
        "AND fa.accountType = ${SdkTxContactResolver.ACCOUNT_TYPE_DASHPAY_EXTERNAL})"

/**
 * The real output/input shape of one transaction, parsed from the SDK store's
 * raw `transactionData` payload — the only ground truth that can tell a
 * wallet-internal self-move (EVERY output returns to owned addresses) apart
 * from an external send with change (some output value leaves the watched
 * set, so it never appears in `txos` at all).
 */
internal data class TxPayloadFacts(
    val outputsTotalDuffs: Long,
    val outputCount: Int,
    val inputCount: Int,
    /**
     * Per-output destination address (base58), index-aligned with vout; null
     * for an output with no resolvable address (OP_RETURN burns,
     * non-standard scripts). The mirror-completeness guard
     * ([firstUnmirroredKnownOutput]) matches these against `core_addresses`
     * to tell a `txos` row that is MISSING (the store dropped it) apart from
     * one that is legitimately absent (the output paid an external party).
     * Empty when the facts source cannot provide addresses — the guard is
     * then inert and the pre-guard behavior stands.
     */
    val outputAddresses: List<String?> = emptyList()
)

/**
 * Production [TxPayloadFacts] source: parse the consensus payload with dashj
 * (the same `Transaction(params, raw)` parse [SdkTxContactResolver] performs
 * on these very blobs). Null on any parse failure — the caller then degrades
 * to the payload-free classification (OUTGOING, never INTERNAL).
 */
internal fun dashjPayloadFacts(payload: ByteArray): TxPayloadFacts? = try {
    val tx = org.bitcoinj.core.Transaction(de.schildbach.wallet.Constants.NETWORK_PARAMETERS, payload)
    TxPayloadFacts(
        outputsTotalDuffs = tx.outputs.sumOf { it.value.value },
        outputCount = tx.outputs.size,
        inputCount = tx.inputs.size,
        outputAddresses = tx.outputs.map { out ->
            try {
                out.scriptPubKey.getToAddress(de.schildbach.wallet.Constants.NETWORK_PARAMETERS, true).toBase58()
            } catch (t: Throwable) {
                null
            }
        }
    )
} catch (t: Throwable) {
    null
}

/**
 * Mirror-completeness guard predicate: the vout of the FIRST payload output
 * that pays an address the wallet already tracks (`core_addresses`,
 * [knownWalletAddresses]) yet has NO `txos` row ([mirroredVouts]) — or null
 * when every wallet-known output is mirrored.
 *
 * A non-null result proves the TXO mirror is missing rows for this
 * transaction: an output to a tracked address is exactly what the store
 * mirrors into `txos` (owned chains and the watch-only DIP-15 contact
 * chains alike), so its absence means the rows were dropped or have not
 * landed yet — NOT that the value left the wallet. An output to an UNKNOWN
 * address never trips the guard: that is the ordinary external-payment
 * shape, whose absence from the mirror is what OUTGOING classification is
 * built on. Pure — host-testable.
 */
internal fun firstUnmirroredKnownOutput(
    payload: TxPayloadFacts,
    knownWalletAddresses: Set<String>,
    mirroredVouts: Set<Int>
): Int? {
    if (knownWalletAddresses.isEmpty()) return null
    payload.outputAddresses.forEachIndexed { vout, address ->
        if (address != null && address in knownWalletAddresses && vout !in mirroredVouts) return vout
    }
    return null
}

/**
 * Correct one PROVABLY-MISATTRIBUTED stored record. The SDK's persisted
 * `transactions.direction`/`netAmount` columns come pre-computed from the
 * Rust side, which counts outputs funded on the watch-only DIP-15
 * `dashpayExternalAccount` ([txoIsForeignSql]) as wallet receives — verified
 * live (S22 testnet, 11.10.73): a 4-input drain that PAID a contact
 * 0.69998912 was stored `direction=0 (incoming), netAmount=+69998912`, and
 * two account sweeps that never left the wallet were stored as incoming
 * receives of their own outputs. Rows authored from those records rendered
 * "Received" for money that was sent away, and no reconcile could ever fix
 * them because the "definitive" record itself was wrong.
 *
 * The caller invokes this for Standard (classic) records that demonstrably
 * SPENT the wallet's own TXOs ([spentOwnedDuffs] > 0 over the
 * foreign-excluded mirror), in two stored shapes:
 *  - `direction == INCOMING` — IMPOSSIBLE outright (an incoming tx never
 *    spends our outputs);
 *  - `direction == OUTGOING` — possibly correct, but the engine can persist
 *    a multi-account spend with the net of only ONE account's slice
 *    (dashpay/platform#4387; S22 field wallet: a 15-input sweep stored as
 *    −0.005 instead of −2.61920199). The recompute below yields the
 *    whole-wallet net; an already-correct row round-trips unchanged.
 * The truth is recomputed from the TXO mirror's (correct, verified) linkage:
 *
 *  - `net = fundedOwned − spentOwned` — the wallet's own Σout−Σin, the same
 *    convention [SdkTxContactResolver.signedNetsFor] computes;
 *  - net < 0 and EVERY output returns to owned addresses
 *    (`payload.outputsTotalDuffs == fundedOwnedDuffs`, no foreign output) →
 *    INTERNAL (the sweep shape, net ≈ −fee);
 *  - net < 0 otherwise → OUTGOING (value left the wallet: a foreign
 *    contact-payment output, or outputs absent from the mirror entirely);
 *  - net ≥ 0 → genuinely incoming after all (a co-funded receive) — the
 *    direction stands, only the net is corrected.
 *
 * The fee is recovered (`spentOwned − outputsTotal`) only when the payload
 * proves ALL inputs were ours (`inputCount == spentOwnedCount`) — otherwise
 * other participants funded part of the tx and the difference is not our fee.
 * Pure — host-testable.
 */
internal fun reattributeIncomingRecord(
    record: L1TxUiRecord,
    spentOwnedCount: Int,
    spentOwnedDuffs: Long,
    fundedOwnedDuffs: Long,
    fundedForeignDuffs: Long,
    payload: TxPayloadFacts?
): L1TxUiRecord {
    if (spentOwnedDuffs <= 0L) return record
    val net = fundedOwnedDuffs - spentOwnedDuffs
    if (net >= 0L) {
        return if (record.netAmountDuffs == net) record else record.copy(netAmountDuffs = net)
    }
    val allInputsOurs = payload != null && payload.inputCount == spentOwnedCount
    val fee = if (payload != null && allInputsOurs && spentOwnedDuffs >= payload.outputsTotalDuffs) {
        spentOwnedDuffs - payload.outputsTotalDuffs
    } else {
        null
    }
    val internal = fundedForeignDuffs == 0L && payload != null &&
        payload.outputsTotalDuffs == fundedOwnedDuffs
    return record.copy(
        direction = if (internal) L1TxUiDirection.INTERNAL else L1TxUiDirection.OUTGOING,
        netAmountDuffs = net,
        feeDuffs = fee ?: record.feeDuffs
    )
}

/**
 * Rust `TransactionType` discriminant for a classic (Standard) transaction —
 * the ONLY kind [reattributeIncomingRecord] is applied to. Special kinds
 * (asset lock/unlock, provider registrations, coinbase) keep their stored
 * record untouched: their display shape is owned by the asset-lock kind
 * resolver / special-kind paths, and `0xFF` (the not-yet-populated sentinel)
 * could be any of them.
 */
internal const val TX_TYPE_KIND_STANDARD = 0

/**
 * Rust `TransactionType` discriminant for an AssetLock (credit-funding)
 * transaction — admitted to reattribution ONLY in its known-bad
 * INTERNAL/net-0 persisted shape (dashpay/platform#4412); every other
 * special kind stays excluded per the note above.
 */
internal const val TX_TYPE_KIND_ASSET_LOCK = 6

/**
 * BOUNDED reader over the Kotlin SDK's L1 Room store (`txos` +
 * `transactions`) for the post-cutover display/seam pipelines — the fix for
 * the release-blocking scalability defect where every snapshot rebuild
 * materialized the WHOLE wallet (a ~100k-tx CoinJoin mainnet wallet crossed
 * the OS kill threshold at ~97% sync and crash-looped on every launch:
 * `queryWalletTxoRefs` read the full TXO table into an ArrayList, then every
 * `transactions` row was fetched and re-planned, up to once per second).
 *
 * Design invariants (each method documents its own bound):
 * - **No full-table materialization, ever.** Every read is either a SQL
 *   aggregate, a point lookup, or a keyset-paginated window of at most
 *   [pageTxoRows] rows ([drainChanges], [walkAll]).
 * - **O(1)-class change detection.** [drainChanges] first computes a cheap
 *   [fingerprint] (three index-only aggregates plus one O(tail) context
 *   probe); an unchanged store costs exactly those four queries and touches
 *   zero rows.
 * - **Incremental by watermark.** New rows are found via `rowid >
 *   watermark` keyset pages; context/lock flips on already-known rows are
 *   found via a bounded RECENT-TAIL re-read ([tailRecords]) — recent
 *   transactions are the only ones whose context realistically still moves
 *   (mempool→islock→block→chainlock completes within minutes), and the
 *   pipeline's periodic FULL paged reconcile ([walkAll], the 60s ticker)
 *   converges any straggler.
 * - **Cooperative.** Page queries run on [Dispatchers.IO]; the paged walks
 *   suspend for [pageThrottleMs] between pages so a full walk over an
 *   arbitrarily large table never saturates a core or blocks first frame.
 *
 * Wallet membership rule: a transaction is wallet-relevant iff it FUNDED a
 * wallet-OWNED TXO (`txos.txid`) or SPENT one (`txos.spendingTxid`).
 * Watch-only DIP-15 contact-payment rows ([txoIsForeignSql]) are excluded
 * from membership on BOTH sides: a tx whose only mirror trace is the
 * contact-side output it funded (or spent — the CONTACT's own later spend of
 * such an output) is the contact's activity, not this wallet's, and
 * enumerating it produced garbage "Received" rows from the misattributed
 * store records (see [reattributeIncomingRecord]).
 *
 * Records materialized here are additionally REATTRIBUTED when the stored
 * direction is provably wrong ([reattributeIncomingRecord]) — every consumer
 * (display pipeline, reconcile walk, seam point reads) sees the corrected
 * shape from one place — and each correction is persisted back into the
 * store exactly once ([persistCorrections]), so an already-corrected record
 * is never re-processed on later passes or later launches.
 *
 * NOT thread-safe: watermark/fingerprint state is confined to the single
 * collector that owns the walker (each flow builds its own instance). The
 * synchronous point lookups ([recordFor], [isMineOutpoint], [spenderOf],
 * [allRecordsNow]) are stateless and safe from any thread.
 *
 * @param onQuery test hook, invoked once per SQL statement issued — the
 *        query-count spy the paging/skip regression tests assert against.
 * @param payloadFacts the [TxPayloadFacts] source for [reattributeIncomingRecord]'s
 *        internal-vs-outgoing discrimination (production: [dashjPayloadFacts];
 *        tests inject a deterministic fake).
 */
internal class SdkTxStoreWalker(
    private val db: DashDatabase,
    private val walletId: ByteArray,
    private val pageTxoRows: Int = PAGE_TXO_ROWS,
    private val tailRows: Int = RECENT_TAIL_ROWS,
    private val pageThrottleMs: Long = PAGE_THROTTLE_MS,
    private val onQuery: ((String) -> Unit)? = null,
    private val payloadFacts: (ByteArray) -> TxPayloadFacts? = ::dashjPayloadFacts
) {

    /**
     * Cheap change fingerprint of the wallet-relevant store state. Every
     * component is monotone non-decreasing under the SDK writer's patterns
     * (row inserts; context upgrades 0→1→2→3; spendingTxid null→set), so two
     * equal fingerprints mean "nothing the display pipeline cares about
     * changed". [tailContextSum] covers context-only UPDATEs (which move no
     * count/rowid) for the newest [FINGERPRINT_CONTEXT_TAIL] transactions —
     * the only rows whose context realistically still changes; older
     * stragglers converge via the periodic full walk.
     *
     * [pendingCount]/[pendingMaxRowid] track the `pending_inputs` side table
     * (outpoints RESERVED by an in-flight local spend at broadcast — see
     * [pendingSpentAggregates]): a reservation landing after the spender's
     * tx row was already drained must still re-trigger a pass, or the
     * display keeps serving the uncorrected INCOMING record until the 60s
     * reconcile. These two are not strictly monotone (rows are CASCADE-
     * deleted with their tx), but any insert/delete moves at least one of
     * them, which is all change DETECTION needs.
     */
    internal data class Fingerprint(
        val txoCount: Long,
        val txoMaxRowid: Long,
        val spendCount: Long,
        val spendMaxRowid: Long,
        val txCount: Long,
        val txMaxRowid: Long,
        val tailContextSum: Long,
        val pendingCount: Long,
        val pendingMaxRowid: Long
    )

    private var txoWatermark = 0L
    private var txWatermark = 0L
    private var lastFingerprint: Fingerprint? = null

    // ── Low-level query plumbing ──────────────────────────────────────

    private fun <T> rawQuery(sql: String, args: Array<Any?>, read: (android.database.Cursor) -> T): T {
        onQuery?.invoke(sql)
        return db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use(read)
    }

    private fun twoLongs(sql: String, args: Array<Any?>): Pair<Long, Long> =
        rawQuery(sql, args) { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L }

    // ── Fingerprint ───────────────────────────────────────────────────

    /**
     * Five queries, no row materialization: four index-only aggregates
     * (`index_txos_walletId`, `index_txos_spendingTxid`, the smallest
     * `transactions` index for COUNT, and `index_pending_inputs_walletId`)
     * plus one bounded scan of the newest [FINGERPRINT_CONTEXT_TAIL]
     * `transactions` rows for the context sum.
     */
    internal fun fingerprint(): Fingerprint {
        val (txoCount, txoMaxRowid) = twoLongs(
            "SELECT COUNT(*), COALESCE(MAX(rowid), 0) FROM txos WHERE walletId = ?",
            arrayOf(walletId)
        )
        val (spendCount, spendMaxRowid) = twoLongs(
            "SELECT COUNT(*), COALESCE(MAX(rowid), 0) FROM txos WHERE spendingTxid IS NOT NULL",
            emptyArray()
        )
        val (txCount, txMaxRowid) = twoLongs(
            "SELECT COUNT(*), COALESCE(MAX(rowid), 0) FROM transactions",
            emptyArray()
        )
        val tailContextSum = rawQuery(
            "SELECT COALESCE(SUM(context), 0) FROM " +
                "(SELECT context FROM transactions ORDER BY rowid DESC LIMIT $FINGERPRINT_CONTEXT_TAIL)",
            emptyArray()
        ) { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val (pendingCount, pendingMaxRowid) = twoLongs(
            "SELECT COUNT(*), COALESCE(MAX(id), 0) FROM pending_inputs WHERE walletId = ?",
            arrayOf(walletId)
        )
        return Fingerprint(
            txoCount, txoMaxRowid, spendCount, spendMaxRowid, txCount, txMaxRowid, tailContextSum,
            pendingCount, pendingMaxRowid
        )
    }

    /**
     * Skip history: start the incremental watermarks at the store's CURRENT
     * max rowids, so [drainChanges] only ever surfaces rows written after
     * this call. Historical convergence belongs to the pipeline's paged
     * [walkAll] reconcile, not the change feed — without this split, a
     * fresh process with a fully-populated store would run the whole-table
     * walk TWICE at startup.
     */
    suspend fun primeWatermarks() = withContext(Dispatchers.IO) {
        txoWatermark = twoLongs(
            "SELECT COALESCE(MAX(rowid), 0), 0 FROM txos WHERE walletId = ?",
            arrayOf(walletId)
        ).first
        txWatermark = twoLongs("SELECT COALESCE(MAX(rowid), 0), 0 FROM transactions", emptyArray()).first
    }

    // ── Row projections ───────────────────────────────────────────────

    private class TxoRefPage(val fundedOrSpentWire: LinkedHashMap<String, ByteArray>, val rows: Int, val maxRowid: Long)

    /**
     * One keyset page of wallet TXO refs past [afterRowid] — at most
     * [pageTxoRows] rows. FOREIGN rows ([txoIsForeignSql]) still count toward
     * the page/watermark (paging semantics unchanged) but contribute NO refs:
     * neither the tx that funded the contact's output nor the contact's own
     * later spend of it gains wallet membership through such a row.
     */
    private fun queryTxoPage(afterRowid: Long): TxoRefPage =
        rawQuery(
            "SELECT rowid, txid, spendingTxid, (${txoIsForeignSql("txos")}) " +
                "FROM txos WHERE walletId = ? AND rowid > ? " +
                "ORDER BY rowid ASC LIMIT $pageTxoRows",
            arrayOf(walletId, afterRowid)
        ) { c ->
            val wire = LinkedHashMap<String, ByteArray>()
            var rows = 0
            var maxRowid = afterRowid
            while (c.moveToNext()) {
                rows++
                maxRowid = c.getLong(0)
                if (c.getInt(3) != 0) continue // foreign: no membership refs
                if (!c.isNull(1)) c.getBlob(1).let { wire[wireHexOf(it)] = it }
                if (!c.isNull(2)) c.getBlob(2).let { wire[wireHexOf(it)] = it }
            }
            TxoRefPage(wire, rows, maxRowid)
        }

    /**
     * One materialized `transactions` row plus the side facts the
     * reattribution pass needs ([reattributeIncomingRecord]): the stored type
     * kind and the owned-spend aggregates (computed IN the row query as
     * correlated subqueries, so the pass adds no statements for the common
     * all-clean case).
     */
    private class RecordRow(
        val record: L1TxUiRecord,
        val wireTxid: ByteArray,
        val typeKind: Int,
        val spentOwnedCount: Int,
        val spentOwnedDuffs: Long
    )

    /** The shared row projection of [queryTxPage]/[queryTxRecords]/[recordFor]. */
    private fun recordRowFrom(c: android.database.Cursor, startCol: Int): RecordRow {
        val txid = c.getBlob(startCol)
        return RecordRow(
            record = l1TxUiRecord(
                txidWireBytes = txid,
                netAmountDuffs = c.getLong(startCol + 1),
                feeDuffs = if (c.isNull(startCol + 2)) null else c.getLong(startCol + 2),
                contextCode = c.getInt(startCol + 3),
                directionCode = c.getInt(startCol + 4),
                firstSeenSec = c.getLong(startCol + 5),
                blockTimestampSec = c.getInt(startCol + 6)
            ),
            wireTxid = txid,
            typeKind = c.getInt(startCol + 7),
            spentOwnedCount = c.getInt(startCol + 8),
            spentOwnedDuffs = c.getLong(startCol + 9)
        )
    }

    /**
     * The record columns + reattribution facts, aliased `tx`. The two
     * correlated subqueries aggregate the wallet's OWN TXOs this tx spent
     * (foreign rows excluded) — indexed on `spendingTxid`, so a tx that
     * spent nothing costs two index misses.
     */
    private fun recordColumnsSql(): String =
        "tx.txid, tx.netAmount, tx.fee, tx.context, tx.direction, tx.firstSeen, " +
            "tx.blockTimestamp, tx.transactionTypeKind, " +
            "(SELECT COUNT(*) FROM txos t WHERE t.walletId = ? AND t.spendingTxid = tx.txid " +
            "AND NOT (${txoIsForeignSql("t")})), " +
            "(SELECT COALESCE(SUM(t.amount), 0) FROM txos t WHERE t.walletId = ? " +
            "AND t.spendingTxid = tx.txid AND NOT (${txoIsForeignSql("t")}))"

    private class TxRowPage(val rows: List<RecordRow>, val txidWireByHex: Map<String, ByteArray>, val rowCount: Int, val maxRowid: Long)

    /** One keyset page of `transactions` rows past [afterRowid] (payload column deliberately excluded). */
    private fun queryTxPage(afterRowid: Long): TxRowPage =
        rawQuery(
            "SELECT tx.rowid, ${recordColumnsSql()} " +
                "FROM transactions tx WHERE tx.rowid > ? ORDER BY tx.rowid ASC LIMIT $pageTxoRows",
            arrayOf(walletId, walletId, afterRowid)
        ) { c ->
            val rows = ArrayList<RecordRow>()
            val wireByHex = HashMap<String, ByteArray>()
            var rowCount = 0
            var maxRowid = afterRowid
            while (c.moveToNext()) {
                rowCount++
                maxRowid = c.getLong(0)
                val row = recordRowFrom(c, startCol = 1)
                rows += row
                wireByHex[row.record.txidHex] = row.wireTxid
            }
            TxRowPage(rows, wireByHex, rowCount, maxRowid)
        }

    /**
     * The wallet-relevant `transactions` rows for [wireTxids] (no payload
     * column), reattributed ([reattributeIncomingRecord]), chunked under
     * SQLite's 999-variable cap and sorted `firstSeen DESC` for parity with
     * the store's DAO ordering. Bounded by the caller: every caller passes at
     * most one page's worth of txids.
     */
    private fun queryTxRecords(wireTxids: Collection<ByteArray>): List<L1TxUiRecord> {
        val rows = ArrayList<RecordRow>(wireTxids.size)
        for (chunk in wireTxids.chunked(TXID_IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = ArrayList<Any?>(2 + chunk.size)
            args.add(walletId)
            args.add(walletId)
            args.addAll(chunk)
            rawQuery(
                "SELECT ${recordColumnsSql()} FROM transactions tx WHERE tx.txid IN ($placeholders)",
                args.toTypedArray()
            ) { c ->
                while (c.moveToNext()) rows += recordRowFrom(c, startCol = 0)
            }
        }
        val out = ArrayList(reattributed(rows))
        out.sortByDescending { it.timestampMs }
        return out
    }

    // ── Pending-input reservations (in-flight local spends) ───────────

    /** Owned-TXO aggregate a spender tx has RESERVED via `pending_inputs`. */
    internal class PendingSpent(val count: Int, val duffs: Long)

    /**
     * DISPLAY-side spent evidence for txs the store has not confirmed yet:
     * per spender txid (display hex), the count/sum of this wallet's OWN
     * TXOs whose outpoint the spender RESERVED in `pending_inputs` at
     * broadcast. The SDK only writes `txos.isSpent`/`spendingTxid` at
     * CONFIRMATION, so between broadcast and confirm a send's only
     * spent-evidence is its reservation — without it the stored
     * `INCOMING +change` record passes [reattributed]'s flag unchallenged
     * and the history renders a phantom receive for money already spent,
     * lingering until the block lands (the on-device "lingering receive").
     *
     * The display predicate, exactly: a TXO counts as PENDING-SPENT iff
     * `isSpent == 0` AND `spendingTxid IS NULL` AND its outpoint is
     * reserved in `pending_inputs` for this wallet. The two NULL/0 guards
     * are the precedence rule that makes stale reservations harmless: the
     * SDK never cleans `pending_inputs` up post-confirm (verified
     * on-device), but once the real marks land the row fails the guards,
     * the confirmed aggregates in [recordColumnsSql] take over, and nothing
     * is double-counted.
     *
     * ONE bounded query per [reattributed] batch (never per record — the
     * denormalized `pending_inputs.spendingTxid` has no index, so a
     * per-record correlated probe would scan the side table once per row):
     * an indexed walk of this wallet's `pending_inputs` rows joined to
     * `txos` on its PRIMARY KEY (`outpoint`). The inner GROUP BY dedups the
     * doc-permitted duplicate reservations of one outpoint. Foreign
     * (watch-only contact-account) rows are excluded like every other
     * owned-money read. Fail-soft: an empty map keeps today's behavior.
     */
    private fun pendingSpentAggregates(): Map<String, PendingSpent> =
        rawQuery(
            "SELECT s.spendingTxid, COUNT(*), COALESCE(SUM(s.amount), 0) FROM (" +
                "SELECT pi.spendingTxid AS spendingTxid, pi.outpoint AS outpoint, MAX(t.amount) AS amount " +
                "FROM pending_inputs pi JOIN txos t ON t.outpoint = pi.outpoint " +
                "WHERE pi.walletId = ? AND t.walletId = ? " +
                "AND t.isSpent = 0 AND t.spendingTxid IS NULL " +
                "AND NOT (${txoIsForeignSql("t")}) " +
                "GROUP BY pi.spendingTxid, pi.outpoint" +
                ") s GROUP BY s.spendingTxid",
            arrayOf(walletId, walletId)
        ) { c ->
            val out = HashMap<String, PendingSpent>()
            while (c.moveToNext()) {
                out[displayHexOf(c.getBlob(0))] = PendingSpent(c.getInt(1), c.getLong(2))
            }
            out
        }

    // ── Reattribution of provably-wrong stored records ────────────────

    /**
     * Apply [reattributeIncomingRecord] to the rows whose stored shape is
     * impossible (a Standard tx recorded INCOMING that spent the wallet's own
     * TXOs). Costs nothing when no row is flagged; a flagged set pays one
     * chunked funded-split aggregate plus (for the net<0 subset only) one
     * chunked payload fetch (internal-vs-send discrimination and fee recovery
     * need the tx's real shape — see [TxPayloadFacts]).
     *
     * ## Durable — corrections are WRITTEN BACK to the store
     *
     * A computed correction is persisted into the SDK's own `transactions`
     * row ([persistCorrections]), so the corrected row itself is the durable
     * per-record "already attributed" flag: on every later pass — this
     * process or any future launch — the row no longer matches the flag
     * condition (`direction == INCOMING`) and costs NOTHING beyond the
     * correlated subqueries every row pays. Before this, the same ~1.4k
     * provably-wrong records were re-flagged, re-aggregated and re-parsed on
     * EVERY reconcile walk of every launch (field log: three identical
     * ~1.5k-line reattribution storms across three launches).
     *
     * Wipe/restore and armed-rescan re-runs need NO invalidation plumbing:
     * the only thing that can restore the wrong stored shape is the engine
     * itself rewriting the row (a re-scan re-persisting the misattributed
     * Rust record), which re-satisfies the flag condition and is simply
     * re-corrected and re-persisted — the invalidation is structural, not
     * keyed off time or a rescan marker.
     *
     * ## Guarded — no correction from a provably-INCOMPLETE mirror
     *
     * The recompute is only as good as the `txos` rows it sums. During the
     * 2026-08-19 device investigation the store had DROPPED change-output
     * rows (platform ecc6740ed4, owned-role index collisions in the
     * same-txid record fold), so the walker summed an incomplete funded set
     * and durably stamped born-wrong nets (5e4d7282: −0.98771 stamped when
     * the true net was −0.00000227) — turning a transient store gap into a
     * persisted wrong value that then FOUGHT any later correct engine
     * upsert (the still-incomplete mirror keeps "proving" the wrong net).
     * The guard: before applying a payload-informed correction, every
     * payload output that pays a wallet-tracked address (`core_addresses`)
     * must have a `txos` row ([firstUnmirroredKnownOutput]); a missing one
     * proves the mirror dropped or has not yet written rows for this tx, so
     * the record is SKIPPED — stored shape served, nothing persisted — and
     * simply retried on every later pass until the mirror is complete
     * (rows landing late is the common transient case; the flag condition
     * re-fires structurally). Outputs paying UNKNOWN addresses never trip
     * the guard — that is the ordinary external send, whose mirror absence
     * is exactly what OUTGOING classification rests on. The guard needs
     * payload facts: the payload-free degrade path and the net≥0 branch
     * (which stays flagged and is recomputed every pass anyway, so a gap
     * self-heals) are unchanged.
     */
    private fun reattributed(rows: List<RecordRow>): List<L1TxUiRecord> {
        // Pending-input reservations ([pendingSpentAggregates]) supplement the
        // confirmed spent marks for DISPLAY, so a just-broadcast send is
        // corrected the moment it is planned instead of after its block lands.
        // Fetched once per batch, and only when a row could be flagged at all.
        val pending = if (
            rows.any {
                it.typeKind == TX_TYPE_KIND_STANDARD && (
                    it.record.direction == L1TxUiDirection.INCOMING ||
                        it.record.direction == L1TxUiDirection.OUTGOING
                    )
            }
        ) {
            pendingSpentAggregates()
        } else {
            emptyMap()
        }
        fun pendingOf(row: RecordRow): PendingSpent? = pending[row.record.txidHex]

        // INCOMING rows are flagged on the impossible shape (an incoming tx
        // never spends our outputs). OUTGOING rows are flagged on the SAME
        // spent-evidence gate but for a different defect: the engine can
        // persist a multi-account spend with the net of only ONE account's
        // slice (dashpay/platform#4387 — verified on the S22 field wallet,
        // where a 15-input full-balance sweep was stored as −0.005 instead of
        // −2.619). [reattributeIncomingRecord]'s mirror math recomputes the
        // whole-wallet net either way, and a row whose stored net already
        // matches produces an EQUAL record — no persist, no log, no churn —
        // so flagging every spent-evidenced Standard row is idempotent.
        val flagged = rows.filter {
            (
                (
                    it.record.direction == L1TxUiDirection.INCOMING ||
                        it.record.direction == L1TxUiDirection.OUTGOING
                    ) &&
                    it.typeKind == TX_TYPE_KIND_STANDARD &&
                    it.spentOwnedDuffs + (pendingOf(it)?.duffs ?: 0L) > 0L
                ) ||
                // The known-bad AssetLock shape (dashpay/platform#4412): a credit
                // purchase persisted INTERNAL/net-0 because its value-bearing
                // OP_RETURN burn has no address and everything address-bearing is
                // change. The mirror math corrects it to OUTGOING/−(burn+fee)
                // (the burn is absent from the mirror, so `internal` can't
                // trigger), making the spend visible to EVERY consumer — UI,
                // CSV, balance reconciliation — from the one store row. Gated
                // narrowly on the exact defective shape so a store-side rust fix
                // (non-zero persisted net) makes this arm inert.
                (
                    it.typeKind == TX_TYPE_KIND_ASSET_LOCK &&
                        it.record.direction == L1TxUiDirection.INTERNAL &&
                        it.record.netAmountDuffs == 0L &&
                        it.spentOwnedDuffs > 0L
                    )
        }
        if (flagged.isEmpty()) return rows.map { it.record }

        val fundedOwned = HashMap<String, Long>()
        val fundedForeign = HashMap<String, Long>()
        for (chunk in flagged.chunked(TXID_IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = ArrayList<Any?>(1 + chunk.size)
            args.add(walletId)
            chunk.forEach { args.add(it.wireTxid) }
            rawQuery(
                "SELECT t.txid, " +
                    "SUM(CASE WHEN ${txoIsForeignSql("t")} THEN 0 ELSE t.amount END), " +
                    "SUM(CASE WHEN ${txoIsForeignSql("t")} THEN t.amount ELSE 0 END) " +
                    "FROM txos t WHERE t.walletId = ? AND t.txid IN ($placeholders) GROUP BY t.txid",
                args.toTypedArray()
            ) { c ->
                while (c.moveToNext()) {
                    val hex = displayHexOf(c.getBlob(0))
                    fundedOwned[hex] = c.getLong(1)
                    fundedForeign[hex] = c.getLong(2)
                }
            }
        }

        // Payload facts only for flagged rows whose recomputed net is
        // NEGATIVE: [reattributeIncomingRecord]'s net>=0 branch (a co-funded
        // receive — direction stands, only the net is corrected) never
        // consults the payload, and those rows stay flagged forever (their
        // stored direction legitimately remains INCOMING), so fetching +
        // dashj-parsing their payloads every pass was pure waste.
        val needsFacts = flagged.filter {
            val recomputed = (fundedOwned[it.record.txidHex] ?: 0L) -
                (it.spentOwnedDuffs + (pendingOf(it)?.duffs ?: 0L))
            recomputed < 0L &&
                // An OUTGOING row whose stored net already equals the mirror
                // recompute is CORRECT: no correction will be produced, so
                // fetching + dashj-parsing its payload every pass would be
                // pure waste — and would violate the durability rule that a
                // corrected store does no further reattribution work.
                (
                    it.record.direction != L1TxUiDirection.OUTGOING ||
                        it.record.netAmountDuffs != recomputed
                    )
        }
        val facts = HashMap<String, TxPayloadFacts>()
        for (chunk in needsFacts.chunked(TXID_IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            rawQuery(
                "SELECT txid, transactionData FROM transactions WHERE txid IN ($placeholders)",
                chunk.map<RecordRow, Any?> { it.wireTxid }.toTypedArray()
            ) { c ->
                while (c.moveToNext()) {
                    if (c.isNull(1)) continue
                    payloadFacts(c.getBlob(1))?.let { facts[displayHexOf(c.getBlob(0))] = it }
                }
            }
        }

        // Mirror-completeness probes for the payload-informed subset: which
        // of the payload output addresses the wallet tracks, and which vouts
        // the mirror actually holds rows for. Two bounded chunked queries,
        // paid only when a correction is pending at all.
        val knownAddressCandidates = HashSet<String>()
        for (row in needsFacts) {
            facts[row.record.txidHex]?.outputAddresses?.forEach { if (it != null) knownAddressCandidates += it }
        }
        val knownWalletAddresses = HashSet<String>()
        for (chunk in knownAddressCandidates.chunked(TXID_IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            rawQuery(
                "SELECT address FROM core_addresses WHERE address IN ($placeholders)",
                chunk.toTypedArray<Any?>()
            ) { c -> while (c.moveToNext()) knownWalletAddresses += c.getString(0) }
        }
        val mirroredVouts = HashMap<String, MutableSet<Int>>()
        if (knownWalletAddresses.isNotEmpty()) {
            for (chunk in needsFacts.chunked(TXID_IN_CHUNK)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = ArrayList<Any?>(1 + chunk.size)
                args.add(walletId)
                chunk.forEach { args.add(it.wireTxid) }
                // Foreign rows count as mirrored here: the store mirrors
                // contact-chain outputs into txos too, so their presence is
                // part of the completeness evidence.
                rawQuery(
                    "SELECT t.txid, t.vout FROM txos t WHERE t.walletId = ? AND t.txid IN ($placeholders)",
                    args.toTypedArray()
                ) { c ->
                    while (c.moveToNext()) {
                        mirroredVouts.getOrPut(displayHexOf(c.getBlob(0))) { HashSet() } += c.getInt(1)
                    }
                }
            }
        }

        val correctedByHex = HashMap<String, L1TxUiRecord>(flagged.size)
        val toPersist = ArrayList<Pair<RecordRow, L1TxUiRecord>>()
        for (row in flagged) {
            val hex = row.record.txidHex
            val fact = facts[hex]
            val unmirroredVout = if (fact == null) {
                null
            } else {
                firstUnmirroredKnownOutput(fact, knownWalletAddresses, mirroredVouts[hex] ?: emptySet())
            }
            if (unmirroredVout != null) {
                // The mirror is missing rows for this tx — any net summed
                // from it would be born wrong (the 2026-08-19 shape). Serve
                // the stored record, write nothing, retry next pass.
                if (reattributionDeferredLogged.add(hex)) {
                    log.info(
                        "reattribution of {} deferred: output {} pays wallet-tracked address {} " +
                            "but has no txos row — the TXO mirror is incomplete for this tx, so a " +
                            "recomputed net would be wrong; serving the stored record and retrying " +
                            "once the mirror rows land",
                        hex, unmirroredVout, fact?.outputAddresses?.getOrNull(unmirroredVout).orEmpty()
                    )
                }
                continue
            }
            val pend = pendingOf(row)
            // DISPLAY shape: confirmed spent marks PLUS in-flight reservations,
            // so the correction lands at broadcast, not at confirmation.
            val corrected = reattributeIncomingRecord(
                record = row.record,
                spentOwnedCount = row.spentOwnedCount + (pend?.count ?: 0),
                spentOwnedDuffs = row.spentOwnedDuffs + (pend?.duffs ?: 0L),
                fundedOwnedDuffs = fundedOwned[hex] ?: 0L,
                fundedForeignDuffs = fundedForeign[hex] ?: 0L,
                payload = facts[hex]
            )
            correctedByHex[hex] = corrected
            // PERSISTED shape: CONFIRMED evidence only. A reservation is an
            // in-flight claim a re-org/drop can void, so a pending-derived
            // correction is served from memory each pass and never written
            // back — the store is only rewritten once the real spent marks
            // prove the same thing (the exact pre-existing durable rule).
            val persistCorrected = if (row.spentOwnedDuffs > 0L) {
                reattributeIncomingRecord(
                    record = row.record,
                    spentOwnedCount = row.spentOwnedCount,
                    spentOwnedDuffs = row.spentOwnedDuffs,
                    fundedOwnedDuffs = fundedOwned[hex] ?: 0L,
                    fundedForeignDuffs = fundedForeign[hex] ?: 0L,
                    payload = facts[hex]
                )
            } else {
                row.record
            }
            if (persistCorrected != row.record) toPersist += row to persistCorrected
            if (corrected.direction != row.record.direction &&
                reattributionLogged.add(hex)
            ) {
                log.info(
                    "stored SDK record for {} reattributed {} → {} (stored net {} → {} duffs; " +
                        "spentOwned={} over {} input(s), pendingReserved={} over {} input(s), " +
                        "fundedOwned={}, fundedForeign={})",
                    hex, row.record.direction, corrected.direction,
                    row.record.netAmountDuffs, corrected.netAmountDuffs,
                    row.spentOwnedDuffs, row.spentOwnedCount,
                    pend?.duffs ?: 0L, pend?.count ?: 0,
                    fundedOwned[hex] ?: 0L, fundedForeign[hex] ?: 0L
                )
            }
        }
        persistCorrections(toPersist)
        return rows.map { correctedByHex[it.record.txidHex] ?: it.record }
    }

    /** The SDK `transactions.direction` column code for [d] — the 0..3 convention [l1TxUiRecord] reads. */
    private fun directionCode(d: L1TxUiDirection): Int = when (d) {
        L1TxUiDirection.INCOMING -> 0
        L1TxUiDirection.OUTGOING -> 1
        L1TxUiDirection.INTERNAL -> 2
        L1TxUiDirection.COINJOIN -> 3
    }

    /**
     * Write computed corrections back to the SDK's `transactions` rows —
     * the durable stop for the every-launch reattribution churn (see
     * [reattributed]'s KDoc). Compare-and-set on the OLD (direction, net):
     * a row the engine concurrently rewrote no longer matches and is left
     * alone (the next pass re-evaluates the fresh shape). One transaction
     * per batch; a failed write only costs re-correcting in memory next
     * pass — the read path never depends on the write having landed.
     * Precedent for app-side raw writes to this store:
     * [L1ShadowSource.clearSdkL1Rows]'s reset deletes.
     */
    private fun persistCorrections(corrections: List<Pair<RecordRow, L1TxUiRecord>>) {
        if (corrections.isEmpty()) return
        try {
            val writable = db.openHelper.writableDatabase
            writable.beginTransaction()
            try {
                for ((row, corrected) in corrections) {
                    val sql = "UPDATE transactions SET direction = ?, netAmount = ?, fee = ? " +
                        "WHERE txid = ? AND direction = ? AND netAmount = ?"
                    onQuery?.invoke(sql)
                    writable.execSQL(
                        sql,
                        arrayOf(
                            directionCode(corrected.direction),
                            corrected.netAmountDuffs,
                            corrected.feeDuffs,
                            row.wireTxid,
                            directionCode(row.record.direction),
                            row.record.netAmountDuffs
                        )
                    )
                }
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }
            log.info(
                "persisted {} reattribution correction(s) into the SDK store — these records " +
                    "are attributed durably and will not be re-processed",
                corrections.size
            )
        } catch (t: Throwable) {
            log.warn(
                "failed to persist reattribution corrections; the corrected shapes still " +
                    "served from memory, re-corrected next pass",
                t
            )
        }
    }

    /**
     * Which of [txidWireByHex]'s txids fund or spend a wallet-OWNED TXO —
     * chunked membership probe (foreign rows grant no membership, matching
     * [queryTxoPage]'s ref rule). A tx whose only trace is a `pending_inputs`
     * RESERVATION of an owned TXO is a member too: a just-broadcast
     * change-less send (max-send/drain) creates no owned TXO row and its
     * spent marks only land at confirmation, so without the reservation
     * probe the tx would be invisible to the store walk until its block —
     * the same lingering-spend window [pendingSpentAggregates] closes.
     */
    private fun walletRelevantSubset(txidWireByHex: Map<String, ByteArray>): Set<String> {
        if (txidWireByHex.isEmpty()) return emptySet()
        val relevant = HashSet<String>()
        for (chunk in txidWireByHex.entries.chunked(TXID_IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = ArrayList<Any?>(1 + chunk.size)
            args.add(walletId)
            chunk.forEach { args.add(it.value) }
            rawQuery(
                "SELECT DISTINCT t.txid FROM txos t WHERE t.walletId = ? AND t.txid IN ($placeholders) " +
                    "AND NOT (${txoIsForeignSql("t")})",
                args.toTypedArray()
            ) { c -> while (c.moveToNext()) if (!c.isNull(0)) relevant += displayHexOf(c.getBlob(0)) }
            rawQuery(
                "SELECT DISTINCT t.spendingTxid FROM txos t WHERE t.walletId = ? " +
                    "AND t.spendingTxid IN ($placeholders) AND NOT (${txoIsForeignSql("t")})",
                args.toTypedArray()
            ) { c -> while (c.moveToNext()) if (!c.isNull(0)) relevant += displayHexOf(c.getBlob(0)) }
            rawQuery(
                "SELECT DISTINCT pi.spendingTxid FROM pending_inputs pi " +
                    "JOIN txos t ON t.outpoint = pi.outpoint " +
                    "WHERE pi.walletId = ? AND pi.spendingTxid IN ($placeholders) " +
                    "AND t.walletId = pi.walletId AND NOT (${txoIsForeignSql("t")})",
                args.toTypedArray()
            ) { c -> while (c.moveToNext()) if (!c.isNull(0)) relevant += displayHexOf(c.getBlob(0)) }
        }
        return relevant
    }

    // ── The recent tail ───────────────────────────────────────────────

    /**
     * Fresh records of the RECENTLY-TOUCHED wallet transactions: the newest
     * TXO rows' funded txids plus the newest spend marks' spender txids
     * (each side over-sampled 4× before dedup — a CoinJoin transaction
     * creates many TXO rows per txid — then capped at [tailRows] txids).
     * Bounded: at most `8 × tailRows` index-ordered rows scanned, at most
     * `2 × tailRows` transaction rows fetched.
     */
    suspend fun tailRecords(): List<L1TxUiRecord> = withContext(Dispatchers.IO) { tailRecordsBlocking() }

    private fun tailRecordsBlocking(): List<L1TxUiRecord> {
        val wire = LinkedHashMap<String, ByteArray>()
        val overSample = tailRows * 4
        rawQuery(
            "SELECT t.txid FROM txos t WHERE t.walletId = ? AND t.txid IS NOT NULL " +
                "AND NOT (${txoIsForeignSql("t")}) ORDER BY t.rowid DESC LIMIT $overSample",
            arrayOf(walletId)
        ) { c ->
            while (c.moveToNext() && wire.size < tailRows) {
                val txid = c.getBlob(0)
                wire.putIfAbsent(wireHexOf(txid), txid)
            }
        }
        val fundedCap = wire.size + tailRows
        rawQuery(
            "SELECT t.spendingTxid FROM txos t WHERE t.walletId = ? AND t.spendingTxid IS NOT NULL " +
                "AND NOT (${txoIsForeignSql("t")}) ORDER BY t.rowid DESC LIMIT $overSample",
            arrayOf(walletId)
        ) { c ->
            while (c.moveToNext() && wire.size < fundedCap) {
                val txid = c.getBlob(0)
                wire.putIfAbsent(wireHexOf(txid), txid)
            }
        }
        // Newest RESERVING spenders (`pending_inputs`) — the recently-touched
        // set must include an in-flight change-less send, whose only store
        // trace pre-confirm is its reservation (no owned TXO row, no spent
        // marks). Keeps the pending-corrected record re-emitted while status
        // still moves, exactly like the confirmed spenders above. A wallet's
        // reservations are its OWN spends by construction, so no ownership
        // filter is needed beyond the wallet scope.
        val pendingCap = wire.size + tailRows
        rawQuery(
            "SELECT pi.spendingTxid FROM pending_inputs pi WHERE pi.walletId = ? " +
                "ORDER BY pi.id DESC LIMIT $overSample",
            arrayOf(walletId)
        ) { c ->
            while (c.moveToNext() && wire.size < pendingCap) {
                val txid = c.getBlob(0)
                wire.putIfAbsent(wireHexOf(txid), txid)
            }
        }
        return if (wire.isEmpty()) emptyList() else queryTxRecords(wire.values)
    }

    // ── Incremental drain ─────────────────────────────────────────────

    /**
     * ONE bounded incremental pass:
     * 1. [fingerprint] — unchanged store returns false after exactly the
     *    four fingerprint queries (the O(1)-class skip);
     * 2. new `txos` rows past the watermark, keyset-paged → the records of
     *    the txids they fund/spend;
     * 3. new `transactions` rows past the watermark, keyset-paged and
     *    membership-filtered — catches a send that only SPENDS existing
     *    TXOs (no change output ⇒ no new TXO row);
     * 4. one bounded recent-tail re-read — catches context/lock flips
     *    (mempool→islock→block→chainlock) on already-emitted rows.
     *
     * Per-pass bound: `newRows + 2×tailRows` records across pages of at
     * most `2×pageTxoRows` records each, throttled [pageThrottleMs] apart.
     *
     * @return true when a change was detected and drained.
     */
    suspend fun drainChanges(onPage: suspend (List<L1TxUiRecord>) -> Unit): Boolean {
        val fp = withContext(Dispatchers.IO) { fingerprint() }
        if (fp == lastFingerprint) return false

        // New TXO rows → the txids they introduce.
        while (true) {
            val page = withContext(Dispatchers.IO) { queryTxoPage(txoWatermark) }
            if (page.rows == 0) break
            txoWatermark = page.maxRowid
            if (page.fundedOrSpentWire.isNotEmpty()) {
                val records = withContext(Dispatchers.IO) { queryTxRecords(page.fundedOrSpentWire.values) }
                if (records.isNotEmpty()) onPage(records)
            }
            if (page.rows < pageTxoRows) break
            delay(pageThrottleMs)
        }

        // New transaction rows (membership-filtered, then reattributed —
        // only the wallet-relevant subset pays the reattribution probes).
        while (true) {
            val page = withContext(Dispatchers.IO) { queryTxPage(txWatermark) }
            if (page.rowCount == 0) break
            txWatermark = page.maxRowid
            val relevant = withContext(Dispatchers.IO) { walletRelevantSubset(page.txidWireByHex) }
            if (relevant.isNotEmpty()) {
                val rows = page.rows.filter { it.record.txidHex in relevant }
                onPage(withContext(Dispatchers.IO) { reattributed(rows) })
            }
            if (page.rowCount < pageTxoRows) break
            delay(pageThrottleMs)
        }

        // Recent-tail refresh: context flips on already-known recent rows.
        val tail = withContext(Dispatchers.IO) { tailRecordsBlocking() }
        if (tail.isNotEmpty()) onPage(tail)

        lastFingerprint = fp
        return true
    }

    // ── Full paged reconcile ──────────────────────────────────────────

    /**
     * The FULL wallet walk as a sequence of bounded pages — the periodic
     * (60s-ticker) convergence reconcile. Never materializes the table:
     * each iteration reads one keyset page of TXO refs (≤ [pageTxoRows]
     * rows), fetches those txids' transaction rows (≤ `2×pageTxoRows`),
     * hands them to [onPage], then yields for [pageThrottleMs]. A bounded
     * recency LRU dedups txids that fund many TXO rows (CoinJoin
     * denominations) so most txids are emitted once per walk; a re-emission
     * past the LRU horizon is safe — every consumer pass is idempotent.
     */
    suspend fun walkAll(onPage: suspend (List<L1TxUiRecord>) -> Unit) {
        var watermark = 0L
        val seen = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                size > WALK_DEDUP_LRU_MAX
        }
        while (true) {
            val page = withContext(Dispatchers.IO) { queryTxoPage(watermark) }
            if (page.rows == 0) break
            watermark = page.maxRowid
            val fresh = page.fundedOrSpentWire.filterKeys { seen.put(it, true) == null }
            if (fresh.isNotEmpty()) {
                val records = withContext(Dispatchers.IO) { queryTxRecords(fresh.values) }
                if (records.isNotEmpty()) onPage(records)
            }
            if (page.rows < pageTxoRows) break
            delay(pageThrottleMs)
        }
    }

    // ── Synchronous point/enumeration reads (seam lazy views) ─────────

    /**
     * Point lookup of ONE wallet-relevant record — fresh from the store,
     * three indexed queries, zero retained state. Null when the tx is
     * unknown to the store or not wallet-relevant (parity with the old
     * materialized snapshot map, which only held wallet-relevant rows).
     */
    fun recordFor(displayHex: String): L1TxUiRecord? {
        val wire = hexToBytesOrNull(displayHex.lowercase())?.reversedArray() ?: return null
        val relevant = rawQuery(
            "SELECT EXISTS(SELECT 1 FROM txos t WHERE t.walletId = ? AND t.txid = ? " +
                "AND NOT (${txoIsForeignSql("t")})) " +
                "OR EXISTS(SELECT 1 FROM txos t2 WHERE t2.walletId = ? AND t2.spendingTxid = ? " +
                "AND NOT (${txoIsForeignSql("t2")})) " +
                // A pending_inputs RESERVATION of an owned TXO grants membership
                // too — pre-confirm a change-less send has no other trace (see
                // walletRelevantSubset).
                "OR EXISTS(SELECT 1 FROM pending_inputs pi JOIN txos t3 ON t3.outpoint = pi.outpoint " +
                "WHERE pi.walletId = ? AND pi.spendingTxid = ? " +
                "AND t3.walletId = pi.walletId AND NOT (${txoIsForeignSql("t3")}))",
            arrayOf(walletId, wire, walletId, wire, walletId, wire)
        ) { c -> c.moveToFirst() && c.getLong(0) != 0L }
        if (!relevant) return null
        val row = rawQuery(
            "SELECT ${recordColumnsSql()} FROM transactions tx WHERE tx.txid = ? LIMIT 1",
            arrayOf(walletId, walletId, wire)
        ) { c ->
            if (!c.moveToFirst()) return@rawQuery null
            recordRowFrom(c, startCol = 0)
        } ?: return null
        return reattributed(listOf(row)).single()
    }

    /**
     * Whether the wallet owns output `displayHex:vout` — one indexed EXISTS.
     * A watch-only contact-payment output ([txoIsForeignSql]) is NOT mine:
     * dashj's `isMine` (the seam contract) means spendable-by-this-wallet,
     * and the contact's money never is.
     */
    fun isMineOutpoint(displayHex: String, vout: Int): Boolean {
        val wire = hexToBytesOrNull(displayHex.lowercase())?.reversedArray() ?: return false
        return rawQuery(
            "SELECT EXISTS(SELECT 1 FROM txos t WHERE t.walletId = ? AND t.txid = ? AND t.vout = ? " +
                "AND NOT (${txoIsForeignSql("t")}))",
            arrayOf(walletId, wire, vout)
        ) { c -> c.moveToFirst() && c.getLong(0) != 0L }
    }

    /** Display txid hex of the tx spending output `displayHex:vout`, or null — one indexed lookup. */
    fun spenderOf(displayHex: String, vout: Int): String? {
        val wire = hexToBytesOrNull(displayHex.lowercase())?.reversedArray() ?: return null
        return rawQuery(
            "SELECT spendingTxid FROM txos WHERE walletId = ? AND txid = ? AND vout = ? " +
                "AND spendingTxid IS NOT NULL LIMIT 1",
            arrayOf(walletId, wire, vout)
        ) { c -> if (c.moveToFirst() && !c.isNull(0)) displayHexOf(c.getBlob(0)) else null }
    }

    /**
     * ON-DEMAND full enumeration of the wallet-relevant records, for the
     * seam's rare `values`/`keys`/`getTransactions()` reads. Still paged at
     * the query level (bounded windows, never a whole-table cursor read)
     * and TRANSIENT — the list lives only as long as the caller's call, and
     * nothing on the 1 Hz pipeline path ever invokes it. Synchronous
     * because the `Map` facade it backs is synchronous; callers are
     * background-thread integrations.
     */
    fun allRecordsNow(): List<L1TxUiRecord> {
        var watermark = 0L
        val seen = HashSet<String>()
        val out = ArrayList<L1TxUiRecord>()
        while (true) {
            val page = queryTxoPage(watermark)
            if (page.rows == 0) break
            watermark = page.maxRowid
            val fresh = page.fundedOrSpentWire.filterKeys { seen.add(it) }
            if (fresh.isNotEmpty()) out += queryTxRecords(fresh.values)
            if (page.rows < pageTxoRows) break
        }
        out.sortByDescending { it.timestampMs }
        return out
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(SdkTxStoreWalker::class.java)

        /**
         * Direction-changing reattributions already logged, process-wide
         * (walker instances are transient — one per walk/flow — so a
         * per-instance set would re-log every txid once a minute forever).
         * Bounded; an evicted txid merely re-logs, it never re-corrupts.
         */
        private val reattributionLogged: MutableSet<String> =
            java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(
                    object : LinkedHashMap<String, Boolean>() {
                        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                            size > 1_024
                    }
                )
            )

        /**
         * Incomplete-mirror deferrals already logged, process-wide — same
         * rationale and bound as [reattributionLogged]: the deferral repeats
         * every pass until the mirror rows land, and one line per txid is
         * all a log reader needs.
         */
        private val reattributionDeferredLogged: MutableSet<String> =
            java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(
                    object : LinkedHashMap<String, Boolean>() {
                        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                            size > 1_024
                    }
                )
            )

        /** TXO rows per keyset page — the bounded window every walk reads at a time. */
        const val PAGE_TXO_ROWS = 1_000

        /** Recent-tail txids re-read per incremental pass (per side, funded + spenders). */
        const val RECENT_TAIL_ROWS = 256

        /** Newest `transactions` rows covered by the fingerprint's context sum. */
        const val FINGERPRINT_CONTEXT_TAIL = 512

        /** Suspension between pages — keeps a full walk from saturating a core. */
        const val PAGE_THROTTLE_MS = 100L

        /** Chunk size for raw `txid IN (…)` queries (SQLite's variable cap is 999). */
        const val TXID_IN_CHUNK = 500

        /** [walkAll]'s txid-dedup LRU — bounds memory; a past-horizon repeat is idempotent. */
        const val WALK_DEDUP_LRU_MAX = 8_192
    }
}
