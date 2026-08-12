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
    val inputCount: Int
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
        inputCount = tx.inputs.size
    )
} catch (t: Throwable) {
    null
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
 * The caller only invokes this for records whose stored shape is IMPOSSIBLE:
 * `direction == INCOMING` on a Standard (classic) tx that demonstrably SPENT
 * the wallet's own TXOs ([spentOwnedDuffs] > 0 over the foreign-excluded
 * mirror — an incoming tx never spends our outputs). The truth is then
 * recomputed from the TXO mirror's (correct, verified) linkage:
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
     */
    internal data class Fingerprint(
        val txoCount: Long,
        val txoMaxRowid: Long,
        val spendCount: Long,
        val spendMaxRowid: Long,
        val txCount: Long,
        val txMaxRowid: Long,
        val tailContextSum: Long
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
     * Four queries, no row materialization: three index-only aggregates
     * (`index_txos_walletId`, `index_txos_spendingTxid`, and the smallest
     * `transactions` index for COUNT) plus one bounded scan of the newest
     * [FINGERPRINT_CONTEXT_TAIL] `transactions` rows for the context sum.
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
        return Fingerprint(txoCount, txoMaxRowid, spendCount, spendMaxRowid, txCount, txMaxRowid, tailContextSum)
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
     */
    private fun reattributed(rows: List<RecordRow>): List<L1TxUiRecord> {
        val flagged = rows.filter {
            it.record.direction == L1TxUiDirection.INCOMING &&
                it.typeKind == TX_TYPE_KIND_STANDARD &&
                it.spentOwnedDuffs > 0L
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
            (fundedOwned[it.record.txidHex] ?: 0L) - it.spentOwnedDuffs < 0L
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

        val correctedByHex = HashMap<String, L1TxUiRecord>(flagged.size)
        val toPersist = ArrayList<Pair<RecordRow, L1TxUiRecord>>()
        for (row in flagged) {
            val hex = row.record.txidHex
            val corrected = reattributeIncomingRecord(
                record = row.record,
                spentOwnedCount = row.spentOwnedCount,
                spentOwnedDuffs = row.spentOwnedDuffs,
                fundedOwnedDuffs = fundedOwned[hex] ?: 0L,
                fundedForeignDuffs = fundedForeign[hex] ?: 0L,
                payload = facts[hex]
            )
            correctedByHex[hex] = corrected
            if (corrected != row.record) toPersist += row to corrected
            if (corrected.direction != row.record.direction &&
                reattributionLogged.add(hex)
            ) {
                log.info(
                    "stored SDK record for {} reattributed {} → {} (stored net {} → {} duffs; " +
                        "spentOwned={} over {} input(s), fundedOwned={}, fundedForeign={})",
                    hex, row.record.direction, corrected.direction,
                    row.record.netAmountDuffs, corrected.netAmountDuffs,
                    row.spentOwnedDuffs, row.spentOwnedCount,
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
     * [queryTxoPage]'s ref rule).
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
                "AND NOT (${txoIsForeignSql("t2")}))",
            arrayOf(walletId, wire, walletId, wire)
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
