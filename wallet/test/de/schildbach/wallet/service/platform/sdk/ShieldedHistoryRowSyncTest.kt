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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.database.AppDatabase
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet.service.DisplayCacheRefreshBus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shielded-row writer against the REAL `tx_display_cache` schema (schema 23):
 * the not-ready / empty distinction, the L1-row isolation in both directions, and
 * the post-wipe hold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [29], manifest = Config.NONE)
class ShieldedHistoryRowSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: TxDisplayCacheDao
    private lateinit var scope: CoroutineScope
    private val activity = MutableStateFlow<ShieldedActivitySnapshot?>(null)
    private val walletA = "aa".repeat(32)
    private val walletB = "bb".repeat(32)
    private val l1Txid = "cd".repeat(32)
    @Volatile
    private var ownership = CounterpartyOwnership.OWN
    private val bus = DisplayCacheRefreshBus()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.txDisplayCacheDao()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun sync(): ShieldedHistoryRowSync {
        val service = mockk<ShieldedBalanceService>()
        every { service.observeShieldedActivityOrNotReady() } returns activity
        coEvery { service.counterpartyOwnership(any(), any()) } answers { ownership }
        return ShieldedHistoryRowSync(
            service, dao, bus, { "res:$it" }, scope, Dispatchers.IO, retryDelayMs = 10L, replanDebounceMs = 10L
        )
    }

    private fun entry(
        id: String,
        kindTag: Int = ShieldedActivityKindTag.IDENTITY_CREATE,
        status: ShieldedActivityStatus = ShieldedActivityStatus.CONFIRMED
    ) = ShieldedActivityEntry(
        id = id,
        direction = ShieldedActivityDirection.OUT,
        timestampMs = 1_000L,
        memo = null,
        kindTag = kindTag,
        accountIndex = 0,
        amountCredits = 2_796_148_800L,
        feeCredits = 203_851_200L,
        status = status
    )

    private fun l1Row(rowId: String) = TxDisplayCacheEntry(
        rowId = rowId,
        title = "Received",
        valueSatoshis = 100L,
        iconType = TxDisplayCacheEntry.ICON_RECEIVED,
        iconBgType = TxDisplayCacheEntry.BG_RECEIVED,
        statusText = "",
        comment = "",
        transactionAmount = 1,
        time = 500L,
        hasErrors = false,
        service = null,
        exchangeRateFiatCode = null,
        exchangeRateFiatValue = null,
        contactUsername = null,
        contactDisplayName = null,
        contactAvatarUrl = null,
        contactUserId = null
    )

    private suspend fun ids() = dao.getAll().map { it.rowId }.toSet()

    @Test
    fun notReady_doesNotDelete_realEmptyListDeletesShieldedRowsOnly() = runBlocking {
        val sync = sync()
        dao.insertAll(listOf(l1Row(l1Txid)))
        assertTrue(sync.applySnapshot(ShieldedActivitySnapshot(walletA, listOf(entry("01".repeat(32))))))
        assertEquals(setOf(l1Txid, "shielded:0:${"01".repeat(32)}"), ids())

        // An identical snapshot is a no-op: the stored set already matches.
        assertFalse(sync.applySnapshot(ShieldedActivitySnapshot(walletA, listOf(entry("01".repeat(32))))))
        assertEquals(setOf(l1Txid, "shielded:0:${"01".repeat(32)}"), ids())

        // Not ready (unknown) is not empty: nothing changes.
        assertFalse(sync.applySnapshot(null))
        assertEquals(setOf(l1Txid, "shielded:0:${"01".repeat(32)}"), ids())

        // A READY wallet's real empty list removes the shielded rows, never the L1 row.
        assertTrue(sync.applySnapshot(ShieldedActivitySnapshot(walletA, emptyList())))
        assertEquals(setOf(l1Txid), ids())
    }

    @Test
    fun failedEntryDisappearsFromTheFeed_rowIsRemoved_othersUpdatedInPlace() = runBlocking {
        val sync = sync()
        val a = "01".repeat(32)
        val b = "02".repeat(32)
        sync.applySnapshot(
            ShieldedActivitySnapshot(walletA, listOf(entry(a, status = ShieldedActivityStatus.PENDING), entry(b)))
        )
        assertEquals("res:${de.schildbach.wallet_test.R.string.transaction_row_status_processing}",
            dao.getEntriesByIds(listOf("shielded:0:$a")).single().statusText)

        // b failed (the mapping drops Failed rows), a confirmed.
        sync.applySnapshot(ShieldedActivitySnapshot(walletA, listOf(entry(a))))
        val rows = dao.getAll()
        assertEquals(listOf("shielded:0:$a"), rows.map { it.rowId })
        assertEquals("", rows.single().statusText)
        assertEquals(-3_000_000L, rows.single().valueSatoshis)
    }

    @Test
    fun l1Writers_neverDeleteOrCountShieldedRows() = runBlocking {
        val sync = sync()
        sync.applySnapshot(ShieldedActivitySnapshot(walletA, listOf(entry("01".repeat(32)))))
        dao.insertAll(listOf(l1Row(l1Txid)))
        assertEquals(1, dao.getCount())
        assertEquals(500L, dao.oldestTimeMs())

        // The dashj rebuild's replaceAll and the L1-only clear keep the shielded row.
        dao.replaceAll(listOf(l1Row("ef".repeat(32))))
        assertEquals(setOf("ef".repeat(32), "shielded:0:${"01".repeat(32)}"), ids())
        dao.deleteAllL1()
        assertEquals(setOf("shielded:0:${"01".repeat(32)}"), ids())
        assertEquals(0, dao.getCount())

        // The wipe's deleteAll clears everything.
        dao.deleteAll()
        assertEquals(emptySet<String>(), ids())
    }

    @Test(expected = IllegalArgumentException::class)
    fun replaceShieldedRows_refusesAnL1Row() = runBlocking {
        dao.replaceShieldedRows(listOf(l1Row(l1Txid)))
        Unit
    }

    @Test
    fun replaceShieldedRows_handlesMoreRowsThanSqliteBindsInOneStatement() = runBlocking {
        val sync = sync()
        val many = (0 until 1_200).map { entry("%064x".format(it)) }
        sync.applySnapshot(ShieldedActivitySnapshot(walletA, many))
        assertEquals(1_200, dao.getAll().size)
        sync.applySnapshot(ShieldedActivitySnapshot(walletA, many.take(3)))
        assertEquals(3, dao.getAll().size)
    }

    @Test
    fun collector_writesLiveEmissions_andTheWipeHoldsTheWipedWalletOff() = runBlocking {
        val sync = sync()
        sync.start()
        sync.start() // idempotent
        val a = "01".repeat(32)
        activity.value = ShieldedActivitySnapshot(walletA, listOf(entry(a)))
        awaitIds(setOf("shielded:0:$a"))

        sync.stopForWalletWipe()
        dao.deleteAll() // the wipe's clearDatabase
        // While fenced, emissions are not collected at all.
        activity.value = ShieldedActivitySnapshot(walletA, listOf(entry(a), entry("02".repeat(32))))
        sync.start() // a start during the wipe is refused
        sync.resumeAfterWalletWipe()
        // The shielded service still reports the wiped wallet as ready: held off.
        repeat(20) { yield() }
        Thread.sleep(100)
        assertEquals(emptySet<String>(), ids())

        // Another wallet becomes ready: its rows are written.
        val c = "03".repeat(32)
        activity.value = ShieldedActivitySnapshot(walletB, listOf(entry(c)))
        awaitIds(setOf("shielded:0:$c"))
    }

    @Test
    fun wipeHold_releasedByNotReady_soASameSeedRestoreShowsItsRows() = runBlocking {
        val sync = sync()
        val a = "01".repeat(32)
        sync.applySnapshot(ShieldedActivitySnapshot(walletA, listOf(entry(a))))
        sync.stopForWalletWipe()
        dao.deleteAll()
        sync.resumeAfterWalletWipe()
        assertFalse(sync.applySnapshot(ShieldedActivitySnapshot(walletA, listOf(entry(a)))))
        assertFalse(sync.applySnapshot(null)) // runtime torn down
        assertTrue(sync.applySnapshot(ShieldedActivitySnapshot(walletA, listOf(entry(a)))))
        assertEquals(setOf("shielded:0:$a"), ids())
    }

    private suspend fun awaitIds(expected: Set<String>) {
        withTimeout(5_000) {
            while (ids() != expected) kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun ownershipIsResolvedAgainstTheSnapshotsWallet_foreignWithdrawalGetsASentRow() = runBlocking {
        val service = mockk<ShieldedBalanceService>()
        every { service.observeShieldedActivityOrNotReady() } returns activity
        coEvery { service.counterpartyOwnership(walletA, any()) } returns CounterpartyOwnership.EXTERNAL
        val sync = ShieldedHistoryRowSync(
            service, dao, DisplayCacheRefreshBus(), { "res:$it" }, scope, Dispatchers.IO, retryDelayMs = 10L
        )
        val w = "05".repeat(32)
        sync.applySnapshot(
            ShieldedActivitySnapshot(walletA, listOf(entry(w, kindTag = ShieldedActivityKindTag.WITHDRAWAL)))
        )
        val row = dao.getAll().single()
        assertEquals("shielded:0:$w", row.rowId)
        assertEquals("res:${de.schildbach.wallet_test.R.string.transaction_row_status_sent}", row.title)
        assertEquals(-3_000_000L, row.valueSatoshis)
        coVerify { service.counterpartyOwnership(walletA, any()) }
    }

    @Test
    fun unknownWithdrawalDestination_getsNoRow() = runBlocking {
        ownership = CounterpartyOwnership.UNKNOWN
        val sync = sync()
        sync.applySnapshot(
            ShieldedActivitySnapshot(walletA, listOf(entry("05".repeat(32), kindTag = ShieldedActivityKindTag.WITHDRAWAL)))
        )
        assertEquals(emptySet<String>(), ids())
    }

    @Test
    fun ownWithdrawal_unshieldedUntilPaidOnCore_thenAnL1WriteReplansItAway() = runBlocking {
        val w = "05".repeat(32)
        val sync = sync()
        sync.start()
        // In Platform's withdrawal queue: no Core output yet → an own "Unshielded" move.
        activity.value = ShieldedActivitySnapshot(walletA, listOf(entry(w, kindTag = ShieldedActivityKindTag.WITHDRAWAL)))
        awaitIds(setOf("shielded:0:$w"))
        val row = dao.getAll().single()
        assertEquals("res:${de.schildbach.wallet_test.R.string.transaction_row_unshielded}", row.title)
        assertEquals(TxDisplayCacheEntry.ICON_INTERNAL, row.iconType)
        assertEquals(-3_000_000L, row.valueSatoshis)

        // The payout lands on Core (txo held) and the L1 writer inserts its row and
        // signals the bus — no shielded_activities change at all.
        ownership = CounterpartyOwnership.OWN_PAID_ON_CORE
        dao.insertAll(listOf(l1Row(l1Txid)))
        bus.signalChanged()
        awaitIds(setOf(l1Txid))
    }

    @Test
    fun ownWithdrawalAlreadyPaidOnCore_getsNoRow() = runBlocking {
        ownership = CounterpartyOwnership.OWN_PAID_ON_CORE
        val sync = sync()
        sync.applySnapshot(
            ShieldedActivitySnapshot(walletA, listOf(entry("05".repeat(32), kindTag = ShieldedActivityKindTag.WITHDRAWAL)))
        )
        assertEquals(emptySet<String>(), ids())
    }
}
