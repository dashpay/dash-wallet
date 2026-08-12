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
        tailRows: Int = 32,
        payloadFacts: (ByteArray) -> TxPayloadFacts? = ::dashjPayloadFacts
    ) = SdkTxStoreWalker(
        db = db,
        walletId = walletId,
        pageTxoRows = pageRows,
        tailRows = tailRows,
        pageThrottleMs = 0L,
        onQuery = { queryLog += it },
        payloadFacts = payloadFacts
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

    // ── Misattributed-record reattribution + foreign-row exclusion ────
    //
    // Replica of the live S22 testnet corruption (s22test63b, 11.10.73):
    // the SDK stored EVERY tx `direction=0 (incoming)` with a positive
    // netAmount — including a drain that PAID a contact (its only output
    // sits on the watch-only type-13 dashpayExternalAccount) and two
    // account sweeps that never left the wallet. The walker must serve the
    // corrected shapes, and the contact-side rows must grant no ownership
    // or membership.

    private val bip44Account = 100L
    private val recvAccount = 114L // type 12: contact pays US — our money
    private val extAccount = 115L // type 13: we pay the CONTACT — their money

    private fun insertAccount(id: Long, type: Int) {
        exec(
            "INSERT INTO accounts (id, walletId, accountType, accountIndex, accountTypeName, " +
                "balanceConfirmed, balanceUnconfirmed, externalHighestUsed, internalHighestUsed, " +
                "standardTag, registrationIndex, keyClass, userIdentityId, friendIdentityId, " +
                "createdAt, lastUpdated) VALUES (?, ?, ?, 0, '', 0, 0, 0, 0, 0, 0, 0, ?, ?, 0, 0)",
            id,
            walletId,
            type,
            ByteArray(32).also { it[0] = id.toByte() },
            ByteArray(32).also { it[0] = id.toByte(); it[1] = 1 }
        )
    }

    private fun insertCoreAddress(address: String, accountId: Long) {
        exec(
            "INSERT INTO core_addresses (address, publicKey, poolTypeTag, addressIndex, " +
                "derivationPath, isUsed, firstSeenHeight, lastSeenHeight, balance, createdAt, " +
                "lastUpdated, accountId) VALUES (?, ?, 0, 0, '', 1, 0, 0, 0, 0, 0, ?)",
            address,
            ByteArray(0),
            accountId
        )
    }

    private fun insertTx(id: ByteArray, direction: Int, netAmount: Long, payload: ByteArray, firstSeen: Long) {
        exec(
            "INSERT INTO transactions (txid, transactionData, context, blockHeight, blockTimestamp, " +
                "blockPosition, hasBlockPosition, direction, transactionType, transactionTypeKind, " +
                "netAmount, label, firstSeen, createdAt, lastUpdated) " +
                "VALUES (?, ?, 3, 0, 0, 0, 0, ?, 'Standard', 0, ?, '', ?, 0, 0)",
            id,
            payload,
            direction,
            netAmount,
            firstSeen
        )
    }

    private var outpointSeed = 100

    private fun insertTxo(
        txid: ByteArray,
        vout: Int,
        amount: Long,
        address: String,
        spendingTxid: ByteArray? = null
    ) {
        exec(
            "INSERT INTO txos (outpoint, vout, amount, address, scriptPubKey, height, isCoinbase, " +
                "isConfirmed, isInstantLocked, isLocked, isSpent, createdAt, lastUpdated, walletId, " +
                "txid, spendingTxid, spendingInputIndex, accountId, coreAddressId) " +
                "VALUES (?, ?, ?, ?, ?, 0, 0, 1, 0, 0, ?, 0, 0, ?, ?, ?, NULL, NULL, ?)",
            ByteArray(36).also { it[0] = (outpointSeed and 0xff).toByte(); it[1] = (outpointSeed++ shr 8).toByte(); it[35] = 7 },
            vout,
            amount,
            address,
            ByteArray(0),
            if (spendingTxid != null) 1 else 0,
            walletId,
            txid,
            spendingTxid,
            address
        )
    }

    /**
     * The S22 store shape. Amounts follow the live wallet: a 0.5 contact
     * receive, a sweep of it into BIP44 (fee 226), a drain of the sweep
     * outputs paying the contact (fee 636), a 1.0 faucet receive — every
     * `transactions` row stored `direction=0` with a positive net, exactly
     * as pulled from the device. [contactSpendsDrainOutput] additionally
     * appends the CONTACT's own later spend of the drain output.
     */
    private fun seedS22Shape(): S22Txids {
        insertAccount(bip44Account, 0)
        insertAccount(recvAccount, 12)
        insertAccount(extAccount, 13)
        insertCoreAddress("bip44_0", bip44Account)
        insertCoreAddress("bip44_1", bip44Account)
        insertCoreAddress("chg_0", bip44Account)
        insertCoreAddress("recv12_a", recvAccount)
        insertCoreAddress("ext13_a", extAccount)

        val receive = txid(11)
        val sweep = txid(12)
        val drain = txid(13)
        val faucet = txid(14)

        // Transactions first (txos.txid/spendingTxid both FK-reference them):
        // - the contact paid us 0.5 on the type-12 receiving chain (OUR money);
        // - the sweep moved it to BIP44, never leaving the wallet — stored as
        //   a +0.49999774 "receive" of its own outputs (the live corruption);
        // - the drain paid the CONTACT (single output on the watch-only
        //   type-13 chain) — stored as a +0.49999138 "receive" of the
        //   contact's money;
        // - the faucet receive is a genuine incoming tx, must stay untouched.
        insertTx(receive, direction = 0, netAmount = 50_000_000, payload = ByteArray(0), firstSeen = 1_700_000_001)
        insertTx(sweep, direction = 0, netAmount = 49_999_774, payload = byteArrayOf(2), firstSeen = 1_700_000_002)
        insertTx(drain, direction = 0, netAmount = 49_999_138, payload = byteArrayOf(3), firstSeen = 1_700_000_003)
        insertTx(faucet, direction = 0, netAmount = 100_000_000, payload = ByteArray(0), firstSeen = 1_700_000_004)

        insertTxo(receive, 1, 50_000_000, "recv12_a", spendingTxid = sweep)
        insertTxo(sweep, 0, 49_990_000, "bip44_0", spendingTxid = drain)
        insertTxo(sweep, 1, 9_774, "chg_0", spendingTxid = drain)
        insertTxo(drain, 0, 49_999_138, "ext13_a")
        insertTxo(faucet, 0, 100_000_000, "bip44_1")

        return S22Txids(receive, sweep, drain, faucet)
    }

    private data class S22Txids(
        val receive: ByteArray,
        val sweep: ByteArray,
        val drain: ByteArray,
        val faucet: ByteArray
    )

    /** The contact spends the drain output from THEIR wallet — not our tx. */
    private fun contactSpendsDrainOutput(drain: ByteArray): ByteArray {
        val contactSpend = txid(15)
        insertTx(contactSpend, direction = 0, netAmount = 49_998_500, payload = ByteArray(0), firstSeen = 1_700_000_005)
        exec("UPDATE txos SET spendingTxid = ?, isSpent = 1 WHERE txid = ? AND vout = 0", contactSpend, drain)
        return contactSpend
    }

    /** Deterministic facts for the marker payloads [seedS22Shape] writes. */
    private val markerPayloadFacts: (ByteArray) -> TxPayloadFacts? = { payload ->
        when (payload.firstOrNull()?.toInt()) {
            2 -> TxPayloadFacts(outputsTotalDuffs = 49_999_774, outputCount = 2, inputCount = 1)
            3 -> TxPayloadFacts(outputsTotalDuffs = 49_999_138, outputCount = 1, inputCount = 2)
            else -> null
        }
    }

    @Test
    fun reattribution_s22Shape_sweepInternal_drainOutgoing_receivesUntouched() = runBlocking {
        val ids = seedS22Shape()
        val w = walker(payloadFacts = markerPayloadFacts)

        val byHex = HashMap<String, L1TxUiRecord>()
        w.walkAll { page -> page.forEach { byHex[it.txidHex] = it } }

        // The sweep: every output returned to owned addresses → INTERNAL,
        // net = −fee, fee recovered (payload proves all inputs were ours).
        val sweep = requireNotNull(byHex[displayHexOf(ids.sweep)])
        assertEquals(L1TxUiDirection.INTERNAL, sweep.direction)
        assertEquals(-226L, sweep.netAmountDuffs)
        assertEquals(226L, sweep.feeDuffs)

        // The drain: its only output is the CONTACT's → OUTGOING, net =
        // −(inputs we spent), fee = inputs − outputs.
        val drain = requireNotNull(byHex[displayHexOf(ids.drain)])
        assertEquals(L1TxUiDirection.OUTGOING, drain.direction)
        assertEquals(-49_999_774L, drain.netAmountDuffs)
        assertEquals(636L, drain.feeDuffs)

        // Genuine receives keep their stored shape.
        val receive = requireNotNull(byHex[displayHexOf(ids.receive)])
        assertEquals(L1TxUiDirection.INCOMING, receive.direction)
        assertEquals(50_000_000L, receive.netAmountDuffs)
        val faucet = requireNotNull(byHex[displayHexOf(ids.faucet)])
        assertEquals(L1TxUiDirection.INCOMING, faucet.direction)
        assertEquals(100_000_000L, faucet.netAmountDuffs)
    }

    @Test
    fun reattribution_withoutPayloadFacts_sweepDegradesToOutgoing_neverInternal() = runBlocking {
        val ids = seedS22Shape()
        // No payload facts at all: internal-vs-send cannot be proven, so the
        // sweep must degrade to OUTGOING (sign-correct) — never stay a fake
        // "Received", never claim INTERNAL without proof.
        val w = walker(payloadFacts = { null })
        val byHex = HashMap<String, L1TxUiRecord>()
        w.walkAll { page -> page.forEach { byHex[it.txidHex] = it } }
        val sweep = requireNotNull(byHex[displayHexOf(ids.sweep)])
        assertEquals(L1TxUiDirection.OUTGOING, sweep.direction)
        assertEquals(-226L, sweep.netAmountDuffs)
        val drain = requireNotNull(byHex[displayHexOf(ids.drain)])
        assertEquals(L1TxUiDirection.OUTGOING, drain.direction)
        assertEquals(-49_999_774L, drain.netAmountDuffs)
    }

    @Test
    fun foreignRows_grantNoOwnershipOrMembership() = runBlocking {
        val ids = seedS22Shape()
        val contactSpend = contactSpendsDrainOutput(ids.drain)
        val w = walker(payloadFacts = markerPayloadFacts)

        // The contact-side output is not OURS…
        assertFalse(w.isMineOutpoint(displayHexOf(ids.drain), 0))
        // …but the wallet's own outputs are.
        assertTrue(w.isMineOutpoint(displayHexOf(ids.sweep), 0))
        assertTrue(w.isMineOutpoint(displayHexOf(ids.sweep), 1))

        // The contact's own spend of it is THEIR activity: never enumerated…
        val walked = HashSet<String>()
        w.walkAll { page -> page.forEach { walked += it.txidHex } }
        assertFalse(displayHexOf(contactSpend) in walked)
        // …never a point-readable wallet record…
        assertNull(w.recordFor(displayHexOf(contactSpend)))
        // …and never in the recent tail.
        assertTrue(w.tailRecords().none { it.txidHex == displayHexOf(contactSpend) })
        // The wallet's own txs all remain members.
        for (member in listOf(ids.receive, ids.sweep, ids.drain, ids.faucet)) {
            assertTrue(displayHexOf(member) in walked)
        }
    }

    @Test
    fun reattribution_pointLookup_servesTheCorrectedShape() = runBlocking {
        val ids = seedS22Shape()
        val w = walker(payloadFacts = markerPayloadFacts)
        val drain = requireNotNull(w.recordFor(displayHexOf(ids.drain)))
        assertEquals(L1TxUiDirection.OUTGOING, drain.direction)
        assertEquals(-49_999_774L, drain.netAmountDuffs)
        val sweep = requireNotNull(w.recordFor(displayHexOf(ids.sweep)))
        assertEquals(L1TxUiDirection.INTERNAL, sweep.direction)
    }

    // ── Durable write-back: corrected once, never re-processed ────────

    /** Raw (direction, netAmount, fee) of one stored `transactions` row. */
    private fun storedShape(txid: ByteArray): Triple<Int, Long, Long?> =
        db.openHelper.readableDatabase.query(
            androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT direction, netAmount, fee FROM transactions WHERE txid = ?",
                arrayOf<Any?>(txid)
            )
        ).use { c ->
            assertTrue(c.moveToFirst())
            Triple(c.getInt(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2))
        }

    @Test
    fun reattribution_persistsCorrectionsDurably_nextLaunchDoesNoReattributionWork() = runBlocking {
        val ids = seedS22Shape()
        walker(payloadFacts = markerPayloadFacts).walkAll { }

        // The corrections landed in the STORE (the durable per-record flag):
        // sweep → INTERNAL(2) net=−fee fee recovered; drain → OUTGOING(1).
        assertEquals(Triple(2, -226L, 226L), storedShape(ids.sweep))
        assertEquals(Triple(1, -49_999_774L, 636L), storedShape(ids.drain))
        // Genuine receives untouched.
        assertEquals(Triple(0, 50_000_000L, null), storedShape(ids.receive))
        assertEquals(Triple(0, 100_000_000L, null), storedShape(ids.faucet))

        // "Next launch": a FRESH walker instance (no in-memory state). The
        // rows no longer match the flag condition, so the walk issues NO
        // reattribution statements — no funded-split aggregate, no payload
        // fetch, no UPDATE — the every-launch storm is gone.
        val w2 = walker(payloadFacts = { error("payload parse must not run on a corrected store") })
        queryLog.clear()
        val byHex = HashMap<String, L1TxUiRecord>()
        w2.walkAll { page -> page.forEach { byHex[it.txidHex] = it } }
        assertTrue(queryLog.none { it.contains("transactionData") })
        assertTrue(queryLog.none { it.contains("GROUP BY t.txid") })
        assertTrue(queryLog.none { it.startsWith("UPDATE transactions") })
        // …and the served shapes are still the corrected ones.
        assertEquals(L1TxUiDirection.INTERNAL, byHex[displayHexOf(ids.sweep)]?.direction)
        assertEquals(L1TxUiDirection.OUTGOING, byHex[displayHexOf(ids.drain)]?.direction)
        assertEquals(L1TxUiDirection.INCOMING, byHex[displayHexOf(ids.faucet)]?.direction)
    }

    @Test
    fun reattribution_engineRewriteReflagsAndRecorrects() = runBlocking {
        // The wipe/restore/armed-rescan re-run contract: when the engine
        // re-persists the misattributed Rust record (the only way the wrong
        // shape can come back), the walker must re-correct AND re-persist —
        // no invalidation plumbing, the flag condition is structural.
        val ids = seedS22Shape()
        walker(payloadFacts = markerPayloadFacts).walkAll { }
        assertEquals(Triple(1, -49_999_774L, 636L), storedShape(ids.drain))

        // Engine rewrite: the rescan re-stores the wrong INCOMING shape.
        exec(
            "UPDATE transactions SET direction = 0, netAmount = 49999138, fee = NULL WHERE txid = ?",
            ids.drain
        )

        val byHex = HashMap<String, L1TxUiRecord>()
        walker(payloadFacts = markerPayloadFacts).walkAll { page -> page.forEach { byHex[it.txidHex] = it } }
        assertEquals(L1TxUiDirection.OUTGOING, byHex[displayHexOf(ids.drain)]?.direction)
        assertEquals(Triple(1, -49_999_774L, 636L), storedShape(ids.drain))
    }

    // ── reattributeIncomingRecord (pure) ──────────────────────────────

    private fun incomingRecord(net: Long) = l1TxUiRecord(
        txidWireBytes = txid(77),
        netAmountDuffs = net,
        feeDuffs = null,
        contextCode = 3,
        directionCode = 0,
        firstSeenSec = 1_700_000_000,
        blockTimestampSec = 0
    )

    @Test
    fun reattribute_noOwnedSpend_returnsRecordUntouched() {
        val r = incomingRecord(100)
        assertEquals(r, reattributeIncomingRecord(r, 0, 0, 100, 0, null))
    }

    @Test
    fun reattribute_netStillPositive_keepsIncomingButCorrectsNet() {
        // A co-funded receive: we spent 100 of our own but gained 400 —
        // genuinely incoming, only the stored net (which counted foreign
        // outputs) is corrected.
        val r = incomingRecord(900)
        val out = reattributeIncomingRecord(r, 1, 100, 500, 400, null)
        assertEquals(L1TxUiDirection.INCOMING, out.direction)
        assertEquals(400L, out.netAmountDuffs)
    }

    @Test
    fun reattribute_feeOnlyWhenPayloadProvesAllInputsOurs() {
        val r = incomingRecord(500)
        // Payload says 3 inputs, we only spent 2 rows: someone else co-funded
        // — the outputs/inputs difference is NOT our fee.
        val coFunded = reattributeIncomingRecord(
            r, 2, 1_000, 500, 0,
            TxPayloadFacts(outputsTotalDuffs = 900, outputCount = 2, inputCount = 3)
        )
        assertEquals(L1TxUiDirection.OUTGOING, coFunded.direction)
        assertNull(coFunded.feeDuffs)
        // Same shape but all inputs ours → fee = spent − outputs.
        val allOurs = reattributeIncomingRecord(
            r, 3, 1_000, 500, 0,
            TxPayloadFacts(outputsTotalDuffs = 900, outputCount = 2, inputCount = 3)
        )
        assertEquals(100L, allOurs.feeDuffs)
    }

    @Test
    fun reattribute_internalRequiresEveryOutputOwned() {
        val r = incomingRecord(500)
        // All outputs owned (payload total == owned funded) → INTERNAL.
        val internal = reattributeIncomingRecord(
            r, 1, 1_000, 990, 0,
            TxPayloadFacts(outputsTotalDuffs = 990, outputCount = 2, inputCount = 1)
        )
        assertEquals(L1TxUiDirection.INTERNAL, internal.direction)
        assertEquals(-10L, internal.netAmountDuffs)
        // One output value missing from the mirror → an external send.
        val send = reattributeIncomingRecord(
            r, 1, 1_000, 400, 0,
            TxPayloadFacts(outputsTotalDuffs = 990, outputCount = 2, inputCount = 1)
        )
        assertEquals(L1TxUiDirection.OUTGOING, send.direction)
        assertEquals(-600L, send.netAmountDuffs)
        // A foreign (contact) output present → OUTGOING even if the mirror
        // sums happen to line up.
        val contactPay = reattributeIncomingRecord(
            r, 1, 1_000, 0, 990,
            TxPayloadFacts(outputsTotalDuffs = 990, outputCount = 1, inputCount = 1)
        )
        assertEquals(L1TxUiDirection.OUTGOING, contactPay.direction)
        assertEquals(-1_000L, contactPay.netAmountDuffs)
    }

    // ── dashjPayloadFacts against the REAL device payloads ────────────

    /** The S22 drain `5538f908…` exactly as pulled from `dash-sdk.db`: 4 inputs, one 0.69998912 output. */
    private val realDrainPayloadHex =
        "0300000004940BFC85D8D4A0A9BB70BB47AF508945DDCB4AB1FBA0EE6BF2780AFA6C4D86F1000000006B48304502" +
            "21008F83D2AC1EAE44619F5D70964B115FEF4CF895D36D7954621A29DA37E1B0413402206354850A86C9B714" +
            "5F6B475B6DEACEC41740706C8C9AEAC2C7D189253F15D1F3012103EC1D737702CCDB3132638ABA4FB7374586" +
            "79676564B74CCD4EC44F0D396D8F63FFFFFFFF940BFC85D8D4A0A9BB70BB47AF508945DDCB4AB1FBA0EE6BF2" +
            "780AFA6C4D86F1010000006B483045022100C6EED361E986191D129923616673E94288253AE3572FED4DC3FC" +
            "0FC27A74DFFD0220057A4E343EA35EA985226339CEAE31DCBE6661507075FF0DD569E6635083A0680121036E" +
            "247D793057F6B8157AE2FAD0A3303C0906DD9CF099B7A34BCC7190FC20CBBCFFFFFFFFBAFCD5EC2CEA426E6D" +
            "BC1E742BFDE57E4DA879F6572EA313D110C2FFDF0449EB000000006B483045022100AB0D51A71AEDFD91A269" +
            "62D731325D71725A5DBC568DD1011598062F8309130C022020515A9F117C08C0F62F2AB1EDDFAD1D379DB429" +
            "77E4C3DED9CFD19772ACB979012103ABA9BCB27FE823605F4E88F53727332CD8C498D1B6CBEBEB4073E43752" +
            "6F65CBFFFFFFFFBAFCD5EC2CEA426E6DBC1E742BFDE57E4DA879F6572EA313D110C2FFDF0449EB010000006A" +
            "47304402207D4E24ACC491F956D8DB3A0707D541191684D44FA83D2323823B14BF5999E41002205611F39A0C" +
            "0B615E54E87CC61A1D3ADB49A6ACD68573B5A09E321B814F8BB3A501210383549EA9B29BA857C983C9D78A5D" +
            "C3BECA1B5BD5FC6FBAC838E5F77D8FCA24EFFFFFFFFF0140192C04000000001976A9140FF03CDA88402842A4" +
            "8B5EA94E037B236CC09DBA88AC00000000"

    @Test
    fun dashjPayloadFacts_parsesTheRealDrainPayload() {
        val payload = realDrainPayloadHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val facts = requireNotNull(dashjPayloadFacts(payload)) { "real payload failed to parse" }
        assertEquals(69_998_912L, facts.outputsTotalDuffs)
        assertEquals(1, facts.outputCount)
        assertEquals(4, facts.inputCount)
        // Garbage never parses to facts.
        assertNull(dashjPayloadFacts(ByteArray(0)))
        assertNull(dashjPayloadFacts(byteArrayOf(1, 2, 3)))
    }
}
