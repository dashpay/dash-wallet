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
import android.content.Context
import androidx.room.Room
import de.schildbach.wallet.Constants
import io.mockk.mockk
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder.AccountType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The address-pool backfill's database halves
 * ([DashSdkServiceImpl.planAddressPoolBackfill] /
 * [DashSdkServiceImpl.commitAddressPoolBackfill]) against an in-memory
 * instance of the AAR's own Room schema: the SQL reads and bindings, holes
 * versus widened tail across several pools, an existing used row surviving
 * untouched, one transaction across every pool, the key check, and which
 * pools count as pending.
 *
 * Robolectric runner: needs a real SQLite (Room in-memory), the same
 * `sdk = 29` host setup [SdkTxidHeightsQueryTest] uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class AddressPoolBackfillDbTest {

    private lateinit var db: DashDatabase
    private lateinit var service: DashSdkServiceImpl

    private val walletIdHex = "11".repeat(32)
    private val walletId = requireNotNull(walletIdFromHex(walletIdHex))
    private val params = Constants.NETWORK_PARAMETERS

    /** Three distinct account keys (the xpub column is unique), from the BIP32 test-vector seed. */
    private val master = HDKeyDerivation.createMasterPrivateKey(
        "000102030405060708090a0b0c0d0e0f".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    )
    private fun accountKey(n: Int): DeterministicKey = HDKeyDerivation.deriveChildKey(master, ChildNumber(n, true))

    /** key-wallet's bincode `ExtendedPubKey` layout, as the SDK stores it. */
    private fun sdkXpub(key: DeterministicKey) =
        byteArrayOf(1, 3) + ByteArray(4) + byteArrayOf(1, 0) + key.pubKey + key.chainCode

    private val bip44Key = accountKey(44)
    private val bip32Key = accountKey(32)
    private val coinJoinKey = accountKey(9)

    private var bip44 = 0L
    private var bip32 = 0L
    private var coinJoin = 0L

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DashDatabase::class.java)
            .allowMainThreadQueries().build()
        service = DashSdkServiceImpl(
            context = mockk<Context>(relaxed = true),
            mnemonicProvider = SecurityGuardMnemonicProvider(mockk(relaxed = true)),
            secretWriteFence = WalletSecretWriteFence(),
            protocolVersionLatch = inMemoryProtocolVersionLatch()
        )
        exec(
            "INSERT INTO wallets (walletId, walletGroupId, birthHeight, syncedHeight, lastSynced, " +
                "isImported, createdAt, lastUpdated) VALUES (?, ?, 0, 0, 0, 0, 0, 0)",
            walletId, walletId
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun exec(sql: String, vararg args: Any?) = db.openHelper.writableDatabase.execSQL(sql, args)

    private fun insertAccount(type: Int, tag: Int, name: String, key: DeterministicKey?): Long {
        exec(
            "INSERT INTO accounts (walletId, accountType, accountIndex, accountTypeName, balanceConfirmed, " +
                "balanceUnconfirmed, externalHighestUsed, internalHighestUsed, standardTag, registrationIndex, " +
                "keyClass, userIdentityId, friendIdentityId, accountExtendedPubKeyBytes, createdAt, lastUpdated) " +
                "VALUES (?, ?, 0, ?, 0, 0, -1, -1, ?, 0, 0, x'', x'', ?, 0, 0)",
            walletId, type, name, tag, key?.let(::sdkXpub)
        )
        return db.openHelper.writableDatabase.query("SELECT last_insert_rowid()").use { it.moveToFirst(); it.getLong(0) }
    }

    private fun prefix(key: DeterministicKey, pool: Int) = "m/test/${key.childNumber.num()}'/$pool/"

    /** Stores the real address of [indices] on [pool] of [key]'s account. */
    private fun store(
        accountId: Long,
        key: DeterministicKey,
        pool: Int,
        indices: Iterable<Int>,
        used: Set<Int> = emptySet()
    ) {
        for (i in indices) {
            val row = SdkAddressPoolBackfill.derive(key, pool, i, prefix(key, pool), params)
            exec(
                "INSERT INTO core_addresses (address, publicKey, poolTypeTag, addressIndex, derivationPath, " +
                    "isUsed, firstSeenHeight, lastSeenHeight, balance, createdAt, lastUpdated, accountId) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?)",
                row.address, row.publicKey, pool, i, row.derivationPath,
                if (i in used) 1 else 0, if (i in used) 100 else 0, if (i in used) 200 else 0,
                if (i in used) 777L else 0L, accountId
            )
        }
    }

    private fun indices(accountId: Long, pool: Int): List<Int> =
        db.openHelper.writableDatabase.query(
            "SELECT addressIndex FROM core_addresses WHERE accountId = ? AND poolTypeTag = ? ORDER BY addressIndex",
            arrayOf<Any?>(accountId, pool)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }

    private fun rowCount(): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM core_addresses").use { it.moveToFirst(); it.getInt(0) }

    /** Three widened accounts, small gaps so the planned window stays small. */
    private fun holedWallet() {
        bip44 = insertAccount(0, 0, "standardBip44", bip44Key)
        bip32 = insertAccount(0, 1, "standardBip32", bip32Key)
        coinJoin = insertAccount(1, 0, "coinJoin", coinJoinKey)
        store(bip44, bip44Key, 0, listOf(0, 1, 2, 5, 8), used = setOf(8))
        store(bip44, bip44Key, 1, listOf(0, 3))
        store(bip32, bip32Key, 0, 0..2)
        store(bip32, bip32Key, 1, 0..2)
        store(coinJoin, coinJoinKey, 0, listOf(0, 2))
        store(coinJoin, coinJoinKey, 1, listOf(0))
        service.recordAppliedGapLimits(
            walletIdHex,
            mapOf(AccountType.BIP44 to 3, AccountType.BIP32 to 3, AccountType.COIN_JOIN to 3)
        )
    }

    @Test
    fun repairsEveryPool_countsOnlyTrueHoles_andLeavesAUsedRowUntouched() {
        holedWallet()
        val plan = service.planAddressPoolBackfill(db, walletId, walletIdHex)

        // BIP44 ext: stored max 8, used 8, gap 3 -> 0..11; holes 3,4,6,7, tail 9,10,11.
        // BIP44 int: stored max 3 -> holes 1,2. BIP32: complete.
        // CoinJoin ext: hole 1. CoinJoin int: stored max 0, gap 3 -> tail 1,2.
        assertEquals(4 + 2 + 1, plan.holes)
        assertEquals(0, plan.poolsRefused)
        assertEquals(0, plan.poolsPending)
        assertEquals("nothing is written by the plan", 5 + 2 + 6 + 2 + 1, rowCount())

        val inserted = service.commitAddressPoolBackfill(db, plan, walletIdHex)

        assertEquals(7 + 2 + 1 + 2, inserted)
        assertEquals((0..11).toList(), indices(bip44, 0))
        assertEquals((0..3).toList(), indices(bip44, 1))
        assertEquals((0..2).toList(), indices(bip32, 0))
        assertEquals((0..2).toList(), indices(coinJoin, 0))
        assertEquals((0..2).toList(), indices(coinJoin, 1))

        // The used row keeps its usage and balance fields.
        db.openHelper.writableDatabase.query(
            "SELECT isUsed, firstSeenHeight, lastSeenHeight, balance, accountId FROM core_addresses " +
                "WHERE accountId = ? AND poolTypeTag = 0 AND addressIndex = 8",
            arrayOf<Any?>(bip44)
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(listOf(1L, 100L, 200L, 777L, bip44), (0..4).map { c.getLong(it) })
        }
        // An inserted row is the real address, unused, with the pool's path.
        val expected = SdkAddressPoolBackfill.derive(bip44Key, 0, 6, prefix(bip44Key, 0), params)
        db.openHelper.writableDatabase.query(
            "SELECT address, derivationPath, isUsed, balance FROM core_addresses WHERE accountId = ? " +
                "AND poolTypeTag = 0 AND addressIndex = 6",
            arrayOf<Any?>(bip44)
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(expected.address, c.getString(0))
            assertEquals(expected.derivationPath, c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals(0L, c.getLong(3))
        }

        // A second pass finds nothing left to do.
        val again = service.planAddressPoolBackfill(db, walletId, walletIdHex)
        assertEquals(0, again.holes)
        assertEquals(0, service.commitAddressPoolBackfill(db, again, walletIdHex))
    }

    @Test
    fun aFailureInALaterPool_rollsBackEveryEarlierPool() {
        holedWallet()
        val before = rowCount()
        // The CoinJoin pools are written after the BIP44 ones; fail there.
        exec(
            "CREATE TRIGGER fail_coinjoin BEFORE INSERT ON core_addresses WHEN NEW.accountId = $coinJoin " +
                "BEGIN SELECT RAISE(ABORT, 'injected'); END"
        )
        val plan = service.planAddressPoolBackfill(db, walletId, walletIdHex)

        assertThrows(Exception::class.java) { service.commitAddressPoolBackfill(db, plan, walletIdHex) }
        assertEquals("no pool keeps a partial repair", before, rowCount())
        assertEquals(listOf(0, 1, 2, 5, 8), indices(bip44, 0))
    }

    @Test
    fun aPoolTheStoredKeyDoesNotReproduce_isRefused() {
        holedWallet()
        // BIP32's stored key now names another account.
        exec("UPDATE accounts SET accountExtendedPubKeyBytes = ? WHERE id = ?", sdkXpub(accountKey(7)), bip32)
        exec("DELETE FROM core_addresses WHERE accountId = ? AND addressIndex = 1", bip32)

        val plan = service.planAddressPoolBackfill(db, walletId, walletIdHex)

        // Both of BIP32's pools now miss index 1, so both reach the key check.
        assertEquals(2, plan.poolsRefused)
        service.commitAddressPoolBackfill(db, plan, walletIdHex)
        assertEquals("refused pools are left as they are", listOf(0, 2), indices(bip32, 0))
        assertEquals(listOf(0, 2), indices(bip32, 1))
        assertEquals((0..11).toList(), indices(bip44, 0))
    }

    @Test
    fun pending_countsOnlyWidenedFamilies() {
        // Review finding: a family the wallet lacks is skipped by the widening
        // and must not keep the retry pending forever.
        bip44 = insertAccount(0, 0, "standardBip44", bip44Key)
        bip32 = insertAccount(0, 1, "standardBip32", bip32Key)
        store(bip44, bip44Key, 0, 0..2)
        store(bip44, bip44Key, 1, 0..2)
        store(bip32, bip32Key, 0, 0..2)
        store(bip32, bip32Key, 1, 0..2)
        service.recordAppliedGapLimits(walletIdHex, mapOf(AccountType.BIP44 to 3, AccountType.BIP32 to 3))
        assertEquals(0, service.planAddressPoolBackfill(db, walletId, walletIdHex).poolsPending)

        // A widened family whose rows have not arrived yet does keep it pending.
        service.recordAppliedGapLimits(
            walletIdHex,
            mapOf(AccountType.BIP44 to 3, AccountType.BIP32 to 3, AccountType.COIN_JOIN to 3)
        )
        assertEquals(2, service.planAddressPoolBackfill(db, walletId, walletIdHex).poolsPending)
    }
}
