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

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SQL-level regression tests for [SdkTxStoreWalker] — the fix for the
 * release-blocking scalability defect where the post-cutover display
 * pipeline materialized the WHOLE `txos`/`transactions` store per snapshot
 * (a ~100k-tx CoinJoin mainnet wallet crash-looped at ~97% sync). The
 * production queries run against an in-memory instance of the AAR's own
 * Room schema ([DashDatabase]) seeded LARGE (50k wallet transactions), and
 * every claim the fix makes is asserted mechanically:
 *
 * - a full walk covers every row while never holding more than one bounded
 *   page, in a bounded number of queries;
 * - an unchanged store is confirmed with the fingerprint queries ONLY —
 *   the O(1)-class skip (verified by counting queries via the walker's
 *   query spy);
 * - an incremental drain emits new rows + the bounded recent tail, never
 *   the table;
 * - context-only UPDATEs (no count/rowid movement) are still detected;
 * - a spend-only send (no change output ⇒ no new TXO row) is discovered.
 *
 * Robolectric runner: the queries need a real SQLite (Room in-memory) —
 * the same `sdk = 29` host setup [SdkAssetLockFundingPreflightQueryTest]
 * uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SdkTxStoreWalkerTest {

    private lateinit var db: DashDatabase

    private val walletIdHex = "11".repeat(32)
    private val walletId = requireNotNull(walletIdFromHex(walletIdHex))

    private val queryLog = mutableListOf<String>()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DashDatabase::class.java
        ).allowMainThreadQueries().build()
        exec(
            "INSERT INTO wallets (walletId, walletGroupId, birthHeight, syncedHeight, lastSynced, " +
                "isImported, createdAt, lastUpdated) VALUES (?, ?, 0, 0, 0, 0, 0, 0)",
            walletId,
            walletId
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Harness ───────────────────────────────────────────────────────

    private fun exec(sql: String, vararg args: Any?) {
        db.openHelper.writableDatabase.execSQL(sql, args)
    }

    private fun walker(
        pageRows: Int = 1_000,
        tailRows: Int = 32
    ) = SdkTxStoreWalker(
        db = db,
        walletId = walletId,
        pageTxoRows = pageRows,
        tailRows = tailRows,
        pageThrottleMs = 0L,
        onQuery = { queryLog += it }
    )

    /** Deterministic 32-byte wire txid for index [n]. */
    private fun txid(n: Int): ByteArray = ByteArray(32).also {
        it[0] = (n and 0xff).toByte()
        it[1] = ((n shr 8) and 0xff).toByte()
        it[2] = ((n shr 16) and 0xff).toByte()
        it[31] = 1 // never all-zero
    }

    private fun displayHex(n: Int) = displayHexOf(txid(n))

    /**
     * Seed [count] wallet transactions, each funding ONE wallet TXO — via a
     * compiled statement inside one transaction so 50k rows seed fast.
     */
    private fun seedWalletTxs(count: Int, startAt: Int = 0, context: Int = 3) {
        val sqldb = db.openHelper.writableDatabase
        sqldb.beginTransaction()
        try {
            val txStmt = sqldb.compileStatement(
                "INSERT INTO transactions (txid, transactionData, context, blockHeight, blockTimestamp, " +
                    "blockPosition, hasBlockPosition, direction, transactionType, transactionTypeKind, " +
                    "netAmount, label, firstSeen, createdAt, lastUpdated) " +
                    "VALUES (?, ?, ?, 0, 0, 0, 0, 0, 'standard', 0, ?, '', ?, 0, 0)"
            )
            val txoStmt = sqldb.compileStatement(
                "INSERT INTO txos (outpoint, vout, amount, address, scriptPubKey, height, isCoinbase, " +
                    "isConfirmed, isInstantLocked, isLocked, isSpent, createdAt, lastUpdated, walletId, " +
                    "txid, spendingTxid, spendingInputIndex, accountId, coreAddressId) " +
                    "VALUES (?, 0, 1000, 'addr', ?, 0, 0, 1, 0, 0, 0, 0, 0, ?, ?, NULL, NULL, NULL, NULL)"
            )
            for (i in startAt until startAt + count) {
                val id = txid(i)
                txStmt.clearBindings()
                txStmt.bindBlob(1, id)
                txStmt.bindBlob(2, ByteArray(0))
                txStmt.bindLong(3, context.toLong())
                txStmt.bindLong(4, 1000L)
                txStmt.bindLong(5, 1_700_000_000L + i)
                txStmt.executeInsert()
                val outpoint = ByteArray(36).also { o -> id.copyInto(o); o[35] = 1 }
                txoStmt.clearBindings()
                txoStmt.bindBlob(1, outpoint)
                txoStmt.bindBlob(2, ByteArray(0))
                txoStmt.bindBlob(3, walletId)
                txoStmt.bindBlob(4, id)
                txoStmt.executeInsert()
            }
            sqldb.setTransactionSuccessful()
        } finally {
            sqldb.endTransaction()
        }
    }

    // ── (a) full walk: bounded pages, complete coverage, bounded queries ──

    @Test
    fun walkAll_50kRows_boundedPagesAndCompleteCoverage() = runBlocking {
        val n = 50_000
        val pageRows = 1_000
        seedWalletTxs(n)
        val w = walker(pageRows = pageRows)

        queryLog.clear()
        val pageSizes = mutableListOf<Int>()
        val seenTxids = HashSet<String>()
        w.walkAll { page ->
            pageSizes.add(page.size)
            page.forEach { seenTxids += it.txidHex }
        }

        // Complete coverage…
        assertEquals(n, seenTxids.size)
        // …with EVERY page inside the bounded window (a page of TXO rows can
        // introduce at most 2 txids per row: funder + spender).
        assertTrue("a page exceeded the bounded window", pageSizes.all { it <= pageRows * 2 })
        assertTrue("the walk was not paged at all", pageSizes.size >= n / pageRows)
        // Query bound: one TXO page query + at most ⌈2·pageRows/500⌉ IN-chunk
        // record fetches per page — nothing proportional to the table per pass.
        val pages = pageSizes.size + 1 // + the final short/empty page probe
        val maxQueriesPerPage = 1 + (2 * pageRows + 499) / 500
        assertTrue(
            "query count ${queryLog.size} exceeds the per-page bound",
            queryLog.size <= pages * maxQueriesPerPage
        )
        // The full-table reads of the old pipeline must be gone for good.
        assertTrue(
            "an unbounded (LIMIT-less) txos read crept back in",
            queryLog.none { it.startsWith("SELECT txid, vout, spendingTxid FROM txos") }
        )
    }

    @Test
    fun walkAll_dedupsMultiTxoTransactions() = runBlocking {
        // One transaction funding 5 TXOs (the CoinJoin denomination shape)
        // must be emitted once, not five times.
        seedWalletTxs(1)
        for (vout in 1..4) {
            exec(
                "INSERT INTO txos (outpoint, vout, amount, address, scriptPubKey, height, isCoinbase, " +
                    "isConfirmed, isInstantLocked, isLocked, isSpent, createdAt, lastUpdated, walletId, " +
                    "txid, spendingTxid, spendingInputIndex, accountId, coreAddressId) " +
                    "VALUES (?, ?, 1000, 'addr', ?, 0, 0, 1, 0, 0, 0, 0, 0, ?, ?, NULL, NULL, NULL, NULL)",
                ByteArray(36).also { it[0] = vout.toByte(); it[35] = 9 },
                vout,
                ByteArray(0),
                walletId,
                txid(0)
            )
        }
        val emitted = mutableListOf<String>()
        walker().walkAll { page -> page.forEach { emitted += it.txidHex } }
        assertEquals(listOf(displayHex(0)), emitted)
    }

    // ── (b) the O(1) no-change skip ───────────────────────────────────

    @Test
    fun drainChanges_unchangedStore_skipsAfterFingerprintQueriesOnly() = runBlocking {
        seedWalletTxs(5_000)
        val w = walker()
        w.primeWatermarks()
        // First drain establishes the fingerprint (and emits the bounded tail).
        assertTrue(w.drainChanges { })

        // UNCHANGED store: the drain must confirm and skip with the four
        // fingerprint queries — no row queries, no pages, no records.
        queryLog.clear()
        var pages = 0
        val drained = w.drainChanges { pages++ }
        assertFalse(drained)
        assertEquals(0, pages)
        assertEquals("expected exactly the 4 fingerprint queries", 4, queryLog.size)
        assertTrue(queryLog.all { it.startsWith("SELECT COUNT(*)") || it.startsWith("SELECT COALESCE(SUM(context)") })
    }

    // ── incremental drain: new rows + bounded tail, never the table ───

    @Test
    fun drainChanges_emitsOnlyNewRowsPlusBoundedTail() = runBlocking {
        val tailRows = 32
        seedWalletTxs(5_000)
        val w = walker(tailRows = tailRows)
        w.primeWatermarks()
        assertTrue(w.drainChanges { }) // baseline

        seedWalletTxs(count = 7, startAt = 5_000)

        val emitted = mutableListOf<L1TxUiRecord>()
        assertTrue(w.drainChanges { page -> emitted += page })
        val emittedTxids = emitted.map { it.txidHex }.toSet()
        // Every new row surfaced…
        for (i in 5_000 until 5_007) {
            assertTrue("new tx $i missing from the drain", displayHex(i) in emittedTxids)
        }
        // …and the total pass stayed bounded (new rows + funded/spender tails),
        // nowhere near the 5k-row table.
        assertTrue(
            "drain emitted ${emittedTxids.size} records — not bounded",
            emittedTxids.size <= 7 + 2 * tailRows
        )
    }

    @Test
    fun drainChanges_contextOnlyUpdate_isDetectedViaTheTail() = runBlocking {
        seedWalletTxs(2_000, context = 0) // everything pending
        val w = walker()
        w.primeWatermarks()
        assertTrue(w.drainChanges { }) // baseline

        // A context-only UPDATE moves no count and no rowid — the exact write
        // shape the old comment claimed was covered but wasn't bounded.
        val flipped = txid(1_999) // recent (highest rowid) — inside the tail window
        exec("UPDATE transactions SET context = 3 WHERE txid = ?", flipped)

        val emitted = mutableListOf<L1TxUiRecord>()
        assertTrue("context-only update went undetected", w.drainChanges { page -> emitted += page })
        val record = emitted.firstOrNull { it.txidHex == displayHex(1_999) }
        assertEquals(L1TxUiStatus.CHAINLOCKED, requireNotNull(record) { "flipped tx not re-emitted" }.status)
    }

    @Test
    fun drainChanges_spendOnlySend_discoversTheSpenderTx() = runBlocking {
        seedWalletTxs(1_000)
        val w = walker()
        w.primeWatermarks()
        assertTrue(w.drainChanges { }) // baseline

        // A max-send has NO change output: no new TXO row, only a new
        // `transactions` row + spendingTxid marks on existing TXOs.
        val spender = txid(700_000)
        exec(
            "INSERT INTO transactions (txid, transactionData, context, blockHeight, blockTimestamp, " +
                "blockPosition, hasBlockPosition, direction, transactionType, transactionTypeKind, " +
                "netAmount, label, firstSeen, createdAt, lastUpdated) " +
                "VALUES (?, ?, 1, 0, 0, 0, 0, 1, 'standard', 0, -1000, '', 1700009999, 0, 0)",
            spender,
            ByteArray(0)
        )
        exec("UPDATE txos SET spendingTxid = ?, isSpent = 1 WHERE txid = ?", spender, txid(3))

        val emitted = mutableListOf<L1TxUiRecord>()
        assertTrue(w.drainChanges { page -> emitted += page })
        val spenderHex = displayHexOf(spender)
        assertTrue(
            "the spend-only spender tx was not discovered",
            emitted.any { it.txidHex == spenderHex }
        )
    }

    // ── point lookups (the seam's lazy views) ─────────────────────────

    @Test
    fun pointLookups_freshAndWalletScoped() = runBlocking {
        seedWalletTxs(10)
        val w = walker()

        val record = requireNotNull(w.recordFor(displayHex(3)))
        assertEquals(L1TxUiStatus.CHAINLOCKED, record.status)
        assertEquals(1000L, record.netAmountDuffs)
        // Unknown / non-wallet txids resolve to null (dashj-fallback parity).
        assertNull(w.recordFor(displayHexOf(txid(999_999))))

        assertTrue(w.isMineOutpoint(displayHex(3), 0))
        assertFalse(w.isMineOutpoint(displayHex(3), 7))
        assertNull(w.spenderOf(displayHex(3), 0))

        val spender = txid(500_000)
        exec(
            "INSERT INTO transactions (txid, transactionData, context, blockHeight, blockTimestamp, " +
                "blockPosition, hasBlockPosition, direction, transactionType, transactionTypeKind, " +
                "netAmount, label, firstSeen, createdAt, lastUpdated) " +
                "VALUES (?, ?, 1, 0, 0, 0, 0, 1, 'standard', 0, -500, '', 1700009999, 0, 0)",
            spender,
            ByteArray(0)
        )
        exec("UPDATE txos SET spendingTxid = ? WHERE txid = ?", spender, txid(3))
        // Fresh reads — no caching layer to go stale.
        assertEquals(displayHexOf(spender), w.spenderOf(displayHex(3), 0))
        // The spender is wallet-relevant purely through spendingTxid.
        assertEquals(-500L, requireNotNull(w.recordFor(displayHexOf(spender))).netAmountDuffs)
    }

    @Test
    fun allRecordsNow_enumeratesEverythingOnce() = runBlocking {
        seedWalletTxs(2_500)
        val all = walker(pageRows = 512).allRecordsNow()
        assertEquals(2_500, all.size)
        assertEquals(2_500, all.map { it.txidHex }.toSet().size)
    }
}
