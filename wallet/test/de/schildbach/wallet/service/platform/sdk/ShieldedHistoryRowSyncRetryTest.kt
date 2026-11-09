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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.money.Dash
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
        amount = Dash(2_796_148L),
        timestampMs = 1_000L,
        memo = null,
        pending = false,
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
        coEvery { dao.replaceShieldedRows(any(), any()) } answers {
            calls++
            if (calls == 1) throw IllegalStateException("database is locked")
        }
        val sync = ShieldedHistoryRowSync(
            service, dao, DisplayCacheRefreshBus(), { "res:$it" }, backgroundScope,
            StandardTestDispatcher(testScheduler), retryDelayMs = 30_000L
        )

        sync.start()
        runCurrent()
        coVerify(exactly = 1) { dao.replaceShieldedRows(any(), any()) }

        // Nothing re-emits; only the retry path can bring the rows in.
        advanceTimeBy(29_999L)
        runCurrent()
        coVerify(exactly = 1) { dao.replaceShieldedRows(any(), any()) }
        advanceTimeBy(2L)
        runCurrent()
        coVerify(exactly = 2) {
            dao.replaceShieldedRows(match { rows -> rows.single().rowId == "shielded:0:${"01".repeat(32)}" }, any())
        }
    }
}
