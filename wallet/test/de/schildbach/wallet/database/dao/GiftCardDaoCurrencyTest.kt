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
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A card's details are polled by code that reads the row, waits on the network and writes the
 * whole row back. The currency can be filled in between that read and that write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], manifest = Config.NONE)
class GiftCardDaoCurrencyTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: GiftCardDao

    private val txId: Sha256Hash =
        Sha256Hash.wrap("00000000000000000000000000000000000000000000000000000000000000a1")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.giftCardDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a row read before the backfill does not erase the currency when written back`() = runBlocking {
        dao.insertGiftCard(GiftCard(txId = txId, merchantName = "Acme", price = 23.75, note = "order-1"))
        val readBeforeBackfill = dao.getCardForTransaction(txId).single()

        assertEquals(1, dao.backfillCurrency(txId, "GBP"))
        assertEquals(1, dao.updateGiftCard(readBeforeBackfill.copy(number = "1234")))

        val saved = dao.getCardForTransaction(txId).single()
        assertEquals("GBP", saved.currency)
        assertEquals("1234", saved.number)
    }

    @Test
    fun `an update that names a currency sets it`() = runBlocking {
        dao.insertGiftCard(GiftCard(txId = txId, merchantName = "Acme", price = 23.75, currency = "GBP"))

        dao.updateGiftCard(dao.getCardForTransaction(txId).single().copy(currency = "EUR"))

        assertEquals("EUR", dao.getCardForTransaction(txId).single().currency)
    }

    @Test
    fun `the backfill leaves a known currency alone`() = runBlocking {
        dao.insertGiftCard(GiftCard(txId = txId, merchantName = "Acme", price = 23.75, currency = "EUR"))

        assertEquals(0, dao.backfillCurrency(txId, "GBP"))

        assertEquals("EUR", dao.getCardForTransaction(txId).single().currency)
    }
}
