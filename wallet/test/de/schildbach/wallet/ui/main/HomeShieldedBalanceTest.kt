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
import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shielded amount the home header adds to the L1 total
 * ([homeShieldedBalance]): never the transient zero the pool reads while it
 * is not READY — the last-known balance when cached, otherwise unknown
 * (null), except on a wallet with no shielded context where zero is honest.
 */
class HomeShieldedBalanceTest {

    private val live = Dash.parse("2.5")
    private val cached = Dash.parse("1.25")

    @Test
    fun `READY uses the live balance even when a cache exists`() {
        assertEquals(live, homeShieldedBalance(ShieldedSyncStatus.READY, live, cached, true))
    }

    @Test
    fun `READY with nothing shielded is zero`() {
        assertEquals(Dash.ZERO, homeShieldedBalance(ShieldedSyncStatus.READY, Dash.ZERO, null, true))
    }

    @Test
    fun `not ready uses the last-known balance instead of the transient zero`() {
        assertEquals(cached, homeShieldedBalance(ShieldedSyncStatus.NOT_READY, Dash.ZERO, cached, true))
        assertEquals(cached, homeShieldedBalance(ShieldedSyncStatus.SYNCING, Dash.ZERO, cached, true))
    }

    @Test
    fun `not ready with no cache is unknown`() {
        assertNull(homeShieldedBalance(ShieldedSyncStatus.NOT_READY, Dash.ZERO, null, true))
        assertNull(homeShieldedBalance(ShieldedSyncStatus.SYNCING, Dash.ZERO, null, true))
    }

    @Test
    fun `no shielded context with no cache is an honest zero, not unknown`() {
        assertEquals(Dash.ZERO, homeShieldedBalance(ShieldedSyncStatus.NOT_READY, Dash.ZERO, null, false))
    }

    @Test
    fun `no shielded context ignores a stale cache left by an identity reset`() {
        assertEquals(Dash.ZERO, homeShieldedBalance(ShieldedSyncStatus.NOT_READY, Dash.ZERO, cached, false))
    }
}
