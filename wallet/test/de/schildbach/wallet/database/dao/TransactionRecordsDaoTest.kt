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

package de.schildbach.wallet.database.dao

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.database.AppDatabase
import de.schildbach.wallet.database.entity.TransactionMetadataCacheItem
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.transactions.TransactionCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real schema rather than a mock. The verifier's tests mock this DAO, so nothing
 * else would notice the @Transaction annotation being dropped or the deletes being reordered,
 * and both are load-bearing: a partial cleanup strands rows with nothing left to retry it.
 */
@RunWith(RobolectricTestRunner::class)
// a plain Application: this test needs only a Context for Room, and booting the real
// WalletApplication would drag in Hilt and Firebase
@Config(application = android.app.Application::class, sdk = [28], manifest = Config.NONE)
class TransactionRecordsDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: TransactionRecordsDao

    private val txId: Sha256Hash =
        Sha256Hash.wrap("00000000000000000000000000000000000000000000000000000000000000a1")
    private val otherTxId: Sha256Hash =
        Sha256Hash.wrap("00000000000000000000000000000000000000000000000000000000000000b2")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.transactionRecordsDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(id: Sha256Hash) {
        db.giftCardDao().insertGiftCard(GiftCard(txId = id, merchantName = "Acme", price = 25.0, note = "order-1"))
        db.transactionMetadataDao().insert(
            TransactionMetadata(
                txId = id,
                timestamp = 1_000L,
                value = Coin.COIN,
                type = TransactionCategory.Sent,
                taxCategory = TaxCategory.Expense,
                service = "CTXSpend"
            )
        )
        db.transactionMetadataCacheDao().insert(
            TransactionMetadataCacheItem(cacheTimestamp = 1_000L, txId = id, service = "CTXSpend")
        )
    }

    private suspend fun rowCounts(id: Sha256Hash): Triple<Int, Int, Int> = Triple(
        db.giftCardDao().getCardCountForTransaction(id),
        if (db.transactionMetadataDao().load(id) != null) 1 else 0,
        if (db.transactionMetadataCacheDao().exists(id)) 1 else 0
    )

    @Test
    fun `forgetTransaction clears all three tables for that transaction only`() = runBlocking {
        seed(txId)
        seed(otherTxId)

        val removedCards = dao.forgetTransaction(txId)

        assertEquals(1, removedCards)
        assertEquals(Triple(0, 0, 0), rowCounts(txId))
        // an unrelated purchase must be untouched
        assertEquals(Triple(1, 1, 1), rowCounts(otherTxId))
    }

    @Test
    fun `the three deletes roll back together when the surrounding transaction fails`() = runBlocking {
        seed(txId)

        // Joining an outer transaction that then fails proves the deletes are transactional:
        // without the @Transaction boundary the earlier ones would already have committed.
        try {
            db.withTransaction {
                dao.forgetTransaction(txId)
                throw RuntimeException("something after the cleanup failed")
            }
        } catch (expected: RuntimeException) {
            // rollback is the point of the test
        }

        assertEquals(Triple(1, 1, 1), rowCounts(txId))
    }

    @Test
    fun `forgetTransaction is harmless when nothing was recorded`() = runBlocking {
        assertEquals(0, dao.forgetTransaction(txId))
        assertEquals(Triple(0, 0, 0), rowCounts(txId))
    }
}
