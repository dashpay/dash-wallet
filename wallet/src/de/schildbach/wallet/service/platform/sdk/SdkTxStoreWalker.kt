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
 * Wallet membership rule is unchanged from the old snapshot code: a
 * transaction is wallet-relevant iff it FUNDED a wallet TXO (`txos.txid`)
 * or SPENT one (`txos.spendingTxid`).
 *
 * NOT thread-safe: watermark/fingerprint state is confined to the single
 * collector that owns the walker (each flow builds its own instance). The
 * synchronous point lookups ([recordFor], [isMineOutpoint], [spenderOf],
 * [allRecordsNow]) are stateless and safe from any thread.
 *
 * @param onQuery test hook, invoked once per SQL statement issued — the
 *        query-count spy the paging/skip regression tests assert against.
 */
internal class SdkTxStoreWalker(
    private val db: DashDatabase,
    private val walletId: ByteArray,
    private val pageTxoRows: Int = PAGE_TXO_ROWS,
    private val tailRows: Int = RECENT_TAIL_ROWS,
    private val pageThrottleMs: Long = PAGE_THROTTLE_MS,
    private val onQuery: ((String) -> Unit)? = null
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

    /** One keyset page of wallet TXO refs past [afterRowid] — at most [pageTxoRows] rows. */
    private fun queryTxoPage(afterRowid: Long): TxoRefPage =
        rawQuery(
            "SELECT rowid, txid, spendingTxid FROM txos WHERE walletId = ? AND rowid > ? " +
                "ORDER BY rowid ASC LIMIT $pageTxoRows",
            arrayOf(walletId, afterRowid)
        ) { c ->
            val wire = LinkedHashMap<String, ByteArray>()
            var rows = 0
            var maxRowid = afterRowid
            while (c.moveToNext()) {
                rows++
                maxRowid = c.getLong(0)
                if (!c.isNull(1)) c.getBlob(1).let { wire[wireHexOf(it)] = it }
                if (!c.isNull(2)) c.getBlob(2).let { wire[wireHexOf(it)] = it }
            }
            TxoRefPage(wire, rows, maxRowid)
        }

    private class TxRowPage(val records: List<L1TxUiRecord>, val txidWireByHex: Map<String, ByteArray>, val rows: Int, val maxRowid: Long)

    /** One keyset page of `transactions` rows past [afterRowid] (payload column deliberately excluded). */
    private fun queryTxPage(afterRowid: Long): TxRowPage =
        rawQuery(
            "SELECT rowid, txid, netAmount, fee, context, direction, firstSeen, blockTimestamp " +
                "FROM transactions WHERE rowid > ? ORDER BY rowid ASC LIMIT $pageTxoRows",
            arrayOf(afterRowid)
        ) { c ->
            val records = ArrayList<L1TxUiRecord>()
            val wireByHex = HashMap<String, ByteArray>()
            var rows = 0
            var maxRowid = afterRowid
            while (c.moveToNext()) {
                rows++
                maxRowid = c.getLong(0)
                val txid = c.getBlob(1)
                val record = l1TxUiRecord(
                    txidWireBytes = txid,
                    netAmountDuffs = c.getLong(2),
                    feeDuffs = if (c.isNull(3)) null else c.getLong(3),
                    contextCode = c.getInt(4),
                    directionCode = c.getInt(5),
                    firstSeenSec = c.getLong(6),
                    blockTimestampSec = c.getInt(7)
                )
                records += record
                wireByHex[record.txidHex] = txid
            }
            TxRowPage(records, wireByHex, rows, maxRowid)
        }

    /**
     * The wallet-relevant `transactions` rows for [wireTxids] (no payload
     * column), chunked under SQLite's 999-variable cap and sorted
     * `firstSeen DESC` for parity with the store's DAO ordering. Bounded by
     * the caller: every caller passes at most one page's worth of txids.
     */
    private fun queryTxRecords(wireTxids: Collection<ByteArray>): List<L1TxUiRecord> {
        val out = ArrayList<L1TxUiRecord>(wireTxids.size)
        for (chunk in wireTxids.chunked(TXID_IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            rawQuery(
                "SELECT txid, netAmount, fee, context, direction, firstSeen, blockTimestamp " +
                    "FROM transactions WHERE txid IN ($placeholders)",
                chunk.toTypedArray<Any?>()
            ) { c ->
                while (c.moveToNext()) {
                    out += l1TxUiRecord(
                        txidWireBytes = c.getBlob(0),
                        netAmountDuffs = c.getLong(1),
                        feeDuffs = if (c.isNull(2)) null else c.getLong(2),
                        contextCode = c.getInt(3),
                        directionCode = c.getInt(4),
                        firstSeenSec = c.getLong(5),
                        blockTimestampSec = c.getInt(6)
                    )
                }
            }
        }
        out.sortByDescending { it.timestampMs }
        return out
    }

    /** Which of [txidWireByHex]'s txids fund or spend a wallet TXO — chunked membership probe. */
    private fun walletRelevantSubset(txidWireByHex: Map<String, ByteArray>): Set<String> {
        if (txidWireByHex.isEmpty()) return emptySet()
        val relevant = HashSet<String>()
        for (chunk in txidWireByHex.entries.chunked(TXID_IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = ArrayList<Any?>(1 + chunk.size)
            args.add(walletId)
            chunk.forEach { args.add(it.value) }
            rawQuery(
                "SELECT DISTINCT txid FROM txos WHERE walletId = ? AND txid IN ($placeholders)",
                args.toTypedArray()
            ) { c -> while (c.moveToNext()) if (!c.isNull(0)) relevant += displayHexOf(c.getBlob(0)) }
            rawQuery(
                "SELECT DISTINCT spendingTxid FROM txos WHERE walletId = ? AND spendingTxid IN ($placeholders)",
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
            "SELECT txid FROM txos WHERE walletId = ? AND txid IS NOT NULL ORDER BY rowid DESC LIMIT $overSample",
            arrayOf(walletId)
        ) { c ->
            while (c.moveToNext() && wire.size < tailRows) {
                val txid = c.getBlob(0)
                wire.putIfAbsent(wireHexOf(txid), txid)
            }
        }
        val fundedCap = wire.size + tailRows
        rawQuery(
            "SELECT spendingTxid FROM txos WHERE walletId = ? AND spendingTxid IS NOT NULL " +
                "ORDER BY rowid DESC LIMIT $overSample",
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

        // New transaction rows (membership-filtered).
        while (true) {
            val page = withContext(Dispatchers.IO) { queryTxPage(txWatermark) }
            if (page.rows == 0) break
            txWatermark = page.maxRowid
            val relevant = withContext(Dispatchers.IO) { walletRelevantSubset(page.txidWireByHex) }
            if (relevant.isNotEmpty()) {
                onPage(page.records.filter { it.txidHex in relevant })
            }
            if (page.rows < pageTxoRows) break
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
            "SELECT EXISTS(SELECT 1 FROM txos WHERE walletId = ? AND txid = ?) " +
                "OR EXISTS(SELECT 1 FROM txos WHERE walletId = ? AND spendingTxid = ?)",
            arrayOf(walletId, wire, walletId, wire)
        ) { c -> c.moveToFirst() && c.getLong(0) != 0L }
        if (!relevant) return null
        return rawQuery(
            "SELECT txid, netAmount, fee, context, direction, firstSeen, blockTimestamp " +
                "FROM transactions WHERE txid = ? LIMIT 1",
            arrayOf(wire)
        ) { c ->
            if (!c.moveToFirst()) return@rawQuery null
            l1TxUiRecord(
                txidWireBytes = c.getBlob(0),
                netAmountDuffs = c.getLong(1),
                feeDuffs = if (c.isNull(2)) null else c.getLong(2),
                contextCode = c.getInt(3),
                directionCode = c.getInt(4),
                firstSeenSec = c.getLong(5),
                blockTimestampSec = c.getInt(6)
            )
        }
    }

    /** Whether the wallet owns output `displayHex:vout` — one indexed EXISTS. */
    fun isMineOutpoint(displayHex: String, vout: Int): Boolean {
        val wire = hexToBytesOrNull(displayHex.lowercase())?.reversedArray() ?: return false
        return rawQuery(
            "SELECT EXISTS(SELECT 1 FROM txos WHERE walletId = ? AND txid = ? AND vout = ?)",
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
