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

import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.service.DisplayCacheRefreshBus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A failed shielded-row write is retried through the resubscribe path even when
 * the activity feed stays quiet (no new emission would ever re-trigger it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldedHistoryRowSyncRetryTest {

    private val entry = ShieldedActivityEntry(
        id = "01".repeat(32),
        direction = ShieldedActivityDirection.OUT,
        timestampMs = 1_000L,
        memo = null,
        kindTag = ShieldedActivityKindTag.IDENTITY_CREATE,
        accountIndex = 0,
        amountCredits = 2_796_148_800L,
        feeCredits = 203_851_200L,
        status = ShieldedActivityStatus.CONFIRMED
    )

    @Test
    fun failedWrite_isRetriedAfterTheRetryDelay_whileTheFeedIsQuiet() = runTest {
        val feed = MutableStateFlow<ShieldedActivitySnapshot?>(ShieldedActivitySnapshot("aa".repeat(32), listOf(entry)))
        val service = mockk<ShieldedBalanceService>()
        every { service.observeShieldedActivityOrNotReady() } returns feed
        coEvery { service.counterpartyOwnership(any(), any()) } returns CounterpartyOwnership.UNKNOWN
        val dao = mockk<TxDisplayCacheDao>()
        var calls = 0
        coEvery { dao.replaceShieldedRows(any()) } answers {
            calls++
            if (calls == 1) throw IllegalStateException("database is locked")
            true
        }
        val sync = ShieldedHistoryRowSync(
            service, dao, DisplayCacheRefreshBus(), { "res:$it" }, backgroundScope,
            StandardTestDispatcher(testScheduler), retryDelayMs = 30_000L
        )

        sync.start()
        runCurrent()
        coVerify(exactly = 1) { dao.replaceShieldedRows(any()) }

        // Nothing re-emits; only the retry path can bring the rows in.
        advanceTimeBy(29_999L)
        runCurrent()
        coVerify(exactly = 1) { dao.replaceShieldedRows(any()) }
        advanceTimeBy(2L)
        runCurrent()
        coVerify(exactly = 2) {
            dao.replaceShieldedRows(match { rows -> rows.single().rowId == "shielded:0:${"01".repeat(32)}" })
        }
    }

    @Test
    fun failedOwnershipRead_writesNothing_keepsRows_andRetries() = runTest {
        val withdrawal = entry.copy(kindTag = ShieldedActivityKindTag.WITHDRAWAL)
        val feed = MutableStateFlow<ShieldedActivitySnapshot?>(
            ShieldedActivitySnapshot("aa".repeat(32), listOf(withdrawal))
        )
        val service = mockk<ShieldedBalanceService>()
        every { service.observeShieldedActivityOrNotReady() } returns feed
        var reads = 0
        coEvery { service.counterpartyOwnership(any(), any()) } answers {
            reads++
            // The first pass's read fails (a txo / address read). Treating it as
            // UNKNOWN would re-plan the row away; it must fail the pass instead.
            if (reads == 1) throw ShieldedStoreReadException(IllegalStateException("db locked"))
            CounterpartyOwnership.EXTERNAL
        }
        val dao = mockk<TxDisplayCacheDao>()
        coEvery { dao.replaceShieldedRows(any()) } returns true
        val sync = ShieldedHistoryRowSync(
            service, dao, DisplayCacheRefreshBus(), { "res:$it" }, backgroundScope,
            StandardTestDispatcher(testScheduler), retryDelayMs = 30_000L
        )

        sync.start()
        runCurrent()
        coVerify(exactly = 0) { dao.replaceShieldedRows(any()) }

        advanceTimeBy(30_001L)
        runCurrent()
        coVerify(exactly = 1) { dao.replaceShieldedRows(match { it.size == 1 }) }
    }

    @Test
    fun anUnchangedPlan_writesAndSignalsNothing() = runTest {
        val feed = MutableStateFlow<ShieldedActivitySnapshot?>(ShieldedActivitySnapshot("aa".repeat(32), listOf(entry)))
        val service = mockk<ShieldedBalanceService>()
        every { service.observeShieldedActivityOrNotReady() } returns feed
        coEvery { service.counterpartyOwnership(any(), any()) } returns CounterpartyOwnership.UNKNOWN
        val planned = planShieldedHistoryRows(listOf(entry), { CounterpartyOwnership.UNKNOWN }, { "res:$it" }).rows
        val dao = mockk<TxDisplayCacheDao>()
        // The DAO reports "already exactly these rows": nothing written.
        coEvery { dao.replaceShieldedRows(any()) } returns false
        val bus = spyk(DisplayCacheRefreshBus())
        val sync = ShieldedHistoryRowSync(
            service, dao, bus, { "res:$it" }, backgroundScope,
            StandardTestDispatcher(testScheduler), retryDelayMs = 30_000L
        )
        sync.start()
        runCurrent()
        coVerify(exactly = 1) { dao.replaceShieldedRows(planned) }
        verify(exactly = 0) { bus.signalChanged() }
    }
}
