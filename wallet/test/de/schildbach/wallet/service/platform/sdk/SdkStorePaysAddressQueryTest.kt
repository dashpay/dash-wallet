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
import de.schildbach.wallet.Constants
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Transaction
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [sdkStorePaysAddress] — the chain check behind contact payment addresses
 * after a recovery (PR #1608 review, round 9) — against an in-memory instance
 * of the AAR's own Room schema: a stored transaction paying the address is
 * found from its raw bytes (whatever accounts existed when it was processed)
 * or from a `txos` row for the address; nothing else matches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SdkStorePaysAddressQueryTest {

    private lateinit var db: DashDatabase

    private val params = Constants.NETWORK_PARAMETERS
    private val walletId = requireNotNull(walletIdFromHex("11".repeat(32)))
    private val paid = Address.fromKey(params, ECKey())
    private val unpaid = Address.fromKey(params, ECKey())

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

    private fun exec(sql: String, vararg args: Any?) {
        db.openHelper.writableDatabase.execSQL(sql, args)
    }

    private var txSeed = 1

    /** Inserts a transaction row with [payload]; returns its wire txid. */
    private fun insertTx(payload: ByteArray): ByteArray {
        val txid = ByteArray(32).also {
            it[0] = (txSeed++).toByte()
            it[31] = 1
        }
        exec(
            "INSERT INTO transactions (txid, transactionData, context, blockHeight, blockTimestamp, " +
                "blockPosition, hasBlockPosition, direction, transactionType, transactionTypeKind, " +
                "netAmount, label, firstSeen, createdAt, lastUpdated) " +
                "VALUES (?, ?, 0, 900, 0, 0, 0, 1, 'standard', 0, 0, '', 0, 0, 0)",
            txid,
            payload
        )
        return txid
    }

    /** A transaction paying [to] (plus a change output to another address). */
    private fun payment(to: Address): ByteArray = Transaction(params).apply {
        addOutput(Coin.COIN, to)
        addOutput(Coin.CENT, Address.fromKey(params, ECKey()))
    }.bitcoinSerialize()

    @Test
    fun aStoredTransactionPayingTheAddress_isFoundFromItsRawBytes() {
        insertTx(ByteArray(0)) // a TXO-parent stub
        insertTx(payment(Address.fromKey(params, ECKey())))
        insertTx(payment(paid))

        assertTrue(sdkStorePaysAddress(db, paid))
        assertFalse(sdkStorePaysAddress(db, unpaid))
    }

    @Test
    fun aTxoRowForTheAddress_isFound() {
        // Its transaction is only a TXO-parent stub (no payload to search).
        val txid = insertTx(ByteArray(0))
        exec(
            "INSERT INTO txos (outpoint, vout, amount, address, scriptPubKey, height, isCoinbase, " +
                "isConfirmed, isInstantLocked, isLocked, isSpent, createdAt, lastUpdated, walletId, " +
                "txid, spendingTxid, spendingInputIndex, accountId, coreAddressId) " +
                "VALUES (?, 0, 1000, ?, ?, 0, 0, 1, 0, 0, 0, 0, 0, ?, ?, NULL, NULL, NULL, NULL)",
            ByteArray(36) { 3 },
            paid.toBase58(),
            ByteArray(0),
            walletId,
            txid
        )

        assertTrue(sdkStorePaysAddress(db, paid))
        assertFalse(sdkStorePaysAddress(db, unpaid))
    }

    @Test
    fun anEmptyStore_paysNothing() {
        assertFalse(sdkStorePaysAddress(db, paid))
    }
}
