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
import org.bitcoinj.core.Sha256Hash
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The parity breakdown's SDK txid set ([querySdkTxidHeights]), run against an
 * in-memory instance of the AAR's own Room schema: the wallet's TXO funding
 * and spending txids plus its `pending_inputs` reserved spenders (a
 * change-less send has no TXO row of its own), each with its
 * `transactions.blockHeight`, and nothing from another wallet.
 *
 * Robolectric runner: the query needs a real SQLite (Room in-memory) — the
 * same `sdk = 29` host setup [SdkTxStoreWalkerTest] uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SdkTxidHeightsQueryTest {

    private lateinit var db: DashDatabase

    private val walletId = requireNotNull(walletIdFromHex("11".repeat(32)))
    private val otherWalletId = requireNotNull(walletIdFromHex("22".repeat(32)))

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DashDatabase::class.java
        ).allowMainThreadQueries().build()
        insertWallet(walletId)
        insertWallet(otherWalletId)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun exec(sql: String, vararg args: Any?) {
        db.openHelper.writableDatabase.execSQL(sql, args)
    }

    private fun insertWallet(id: ByteArray) {
        exec(
            "INSERT INTO wallets (walletId, walletGroupId, birthHeight, syncedHeight, lastSynced, " +
                "isImported, createdAt, lastUpdated) VALUES (?, ?, 0, 0, 0, 0, 0, 0)",
            id,
            id
        )
    }

    /** Deterministic 32-byte wire txid; its first byte is [n]. */
    private fun wire(n: Int): ByteArray = ByteArray(32).also {
        it[0] = n.toByte()
        it[31] = 1
    }

    /** The key [querySdkTxidHeights] uses: display order, the way dashj keys its transactions. */
    private fun key(n: Int): Sha256Hash = Sha256Hash.wrapReversed(wire(n))

    private fun insertTx(n: Int, blockHeight: Int) {
        exec(
            "INSERT INTO transactions (txid, transactionData, context, blockHeight, blockTimestamp, " +
                "blockPosition, hasBlockPosition, direction, transactionType, transactionTypeKind, " +
                "netAmount, label, firstSeen, createdAt, lastUpdated) " +
                "VALUES (?, ?, 0, ?, 0, 0, 0, 0, 'standard', 0, 0, '', 0, 0, 0)",
            wire(n),
            ByteArray(0),
            blockHeight
        )
    }

    private var outpointSeed = 1

    /** A TXO of [owner] funded by tx [funding], optionally spent by tx [spending]. */
    private fun insertTxo(owner: ByteArray, funding: Int, spending: Int? = null): ByteArray {
        val outpoint = ByteArray(36).also {
            it[0] = (outpointSeed++).toByte()
            it[35] = 7
        }
        exec(
            "INSERT INTO txos (outpoint, vout, amount, address, scriptPubKey, height, isCoinbase, " +
                "isConfirmed, isInstantLocked, isLocked, isSpent, createdAt, lastUpdated, walletId, " +
                "txid, spendingTxid, spendingInputIndex, accountId, coreAddressId) " +
                "VALUES (?, 0, 1000, 'addr', ?, 0, 0, 1, 0, 0, ?, 0, 0, ?, ?, ?, NULL, NULL, NULL)",
            outpoint,
            ByteArray(0),
            if (spending != null) 1 else 0,
            owner,
            wire(funding),
            spending?.let { wire(it) }
        )
        return outpoint
    }

    /** A `pending_inputs` reservation as the engine writes it at broadcast. */
    private fun insertReservation(owner: ByteArray, outpoint: ByteArray, spending: Int) {
        exec(
            "INSERT INTO pending_inputs (outpoint, inputIndex, spendingTxid, " +
                "spendingTransactionTxid, walletId, createdAt) VALUES (?, 0, ?, NULL, ?, 0)",
            outpoint,
            wire(spending),
            owner
        )
    }

    @Test
    fun theWalletsFundingSpendingAndReservedTxids_withTheirHeights_andNoOtherWallets() {
        // A receive (900) whose output was spent (950).
        insertTx(1, 900)
        insertTx(2, 950)
        insertTxo(walletId, funding = 1, spending = 2)
        // A receive (920) reserved by an in-flight change-less send: the send
        // has a transactions row (no height yet) but no TXO row of its own.
        insertTx(3, 920)
        insertTx(4, 0)
        val reserved = insertTxo(walletId, funding = 3)
        insertReservation(walletId, reserved, spending = 4)
        // A reservation whose spender has no transactions row: no height.
        insertReservation(walletId, reserved, spending = 5)
        // Another wallet's rows, and a transactions row nothing of ours refers to.
        insertTx(6, 990)
        val theirs = insertTxo(otherWalletId, funding = 6)
        insertReservation(otherWalletId, theirs, spending = 7)
        insertTx(8, 995)

        assertEquals(
            mapOf(key(1) to 900, key(2) to 950, key(3) to 920, key(4) to 0, key(5) to 0),
            querySdkTxidHeights(db.openHelper.readableDatabase, walletId)
        )
    }

    @Test
    fun anEmptyWallet_hasNoTxids() {
        assertEquals(emptyMap<Sha256Hash, Int>(), querySdkTxidHeights(db.openHelper.readableDatabase, walletId))
    }
}
