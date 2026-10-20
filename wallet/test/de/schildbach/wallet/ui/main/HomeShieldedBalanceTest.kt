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

package de.schildbach.wallet.ui.main

import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shielded amount the home header adds to the L1 total
 * ([homeShieldedBalance]): the live balance when READY and settled, the
 * last-known balance while not READY, otherwise unknown (null) — never the
 * transient zero the pool reads while not READY, and never a known-stale
 * amount after a local spend.
 */
class HomeShieldedBalanceTest {

    private val live = Dash.parse("2.5")
    private val cached = Dash.parse("1.25")

    @Test
    fun `READY uses the live balance even when a cache exists`() {
        assertEquals(live, homeShieldedBalance(ShieldedSyncStatus.READY, live, cached, false))
    }

    @Test
    fun `READY with nothing shielded is zero`() {
        assertEquals(Dash.ZERO, homeShieldedBalance(ShieldedSyncStatus.READY, Dash.ZERO, null, false))
    }

    @Test
    fun `a funded READY wallet counts regardless of platform identity`() {
        // identity-free wallets bind when the shielded flag is on (fund → shield → create identity);
        // the selector has no identity input at all
        assertEquals(live, homeShieldedBalance(ShieldedSyncStatus.READY, live, null, false))
    }

    @Test
    fun `READY during post-spend reconciliation is unknown`() {
        // READY persists through later sync passes, so it does not mean the spend has settled
        assertNull(homeShieldedBalance(ShieldedSyncStatus.READY, live, cached, true))
    }

    @Test
    fun `not ready uses the last-known balance instead of the transient zero`() {
        assertEquals(cached, homeShieldedBalance(ShieldedSyncStatus.NOT_READY, Dash.ZERO, cached, false))
        assertEquals(cached, homeShieldedBalance(ShieldedSyncStatus.SYNCING, Dash.ZERO, cached, false))
    }

    @Test
    fun `not ready with no cache is unknown`() {
        assertNull(homeShieldedBalance(ShieldedSyncStatus.NOT_READY, Dash.ZERO, null, false))
        assertNull(homeShieldedBalance(ShieldedSyncStatus.SYNCING, Dash.ZERO, null, false))
    }

    @Test
    fun `after a local spend the known-stale cache is not used`() {
        // e.g. cached 10 DASH, 4 DASH just spent, pool re-syncing
        assertNull(homeShieldedBalance(ShieldedSyncStatus.SYNCING, Dash.parse("6"), Dash.parse("10"), true))
    }

    // ── homeShieldedBalanceFlow: fail closed on read errors ──────────────

    private fun flowUnder(enabledFlag: kotlinx.coroutines.flow.Flow<Boolean>) = homeShieldedBalanceFlow(
        enabledFlag = enabledFlag,
        live = { flowOf(Dash(500L)) },
        status = { flowOf(ShieldedSyncStatus.READY) },
        cachedDuffs = { flowOf(null) },
        maybeStale = { flowOf(false) },
        onError = { _, _ -> },
        retryDelayMs = { 0L }
    )

    /**
     * thepastaclaw (7005fe2b1): an unreadable feature flag is unknown — never
     * "disabled" — so the header shows pending (null), not a zero, and the
     * branch recovers once the flag reads.
     */
    @Test
    fun anUnreadableFlag_isPending_notZero_andRecovers() = runBlocking {
        var reads = 0
        val flag = flow {
            if (reads++ == 0) throw java.io.IOException("datastore unreadable")
            emit(true)
        }
        assertEquals(listOf(null, Dash(500L)), flowUnder(flag).take(2).toList())
    }

    @Test
    fun aFlagReadAsDisabled_isZero() = runBlocking {
        assertEquals(listOf(Dash.ZERO), flowUnder(flowOf(false)).take(1).toList())
    }

    @Test
    fun aFailingLiveRead_isPending_andRecovers() = runBlocking {
        var reads = 0
        val result = homeShieldedBalanceFlow(
            enabledFlag = flowOf(true),
            live = {
                flow {
                    if (reads++ == 0) throw IllegalStateException("room closed")
                    emit(Dash(700L))
                }
            },
            status = { flowOf(ShieldedSyncStatus.READY) },
            cachedDuffs = { flowOf(null) },
            maybeStale = { flowOf(false) },
            onError = { _, _ -> },
            retryDelayMs = { 0L }
        ).take(2).toList()
        assertEquals(listOf(null, Dash(700L)), result)
    }
}
