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

package de.schildbach.wallet.ui.dashpay.utils

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The last-known shielded balance cache vs a local spend's reconciliation,
 * on a real DataStore: a balance sampled before a spend must never be written
 * back after the spend dropped the cache — in either transaction order — and
 * the invalidation must survive a restart (a fresh config instance).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class DashPayConfigShieldedBalanceCacheTest {

    // ONE instance per test: DataStore allows a single active store per file.
    private lateinit var cfg: DashPayConfig

    @Before
    fun setUp() = runBlocking {
        cfg = DashPayConfig(RuntimeEnvironment.getApplication(), mockk<WalletDataProvider>(relaxed = true))
        // The constructor seeds debug flags on Dispatchers.IO. Left running, that write can
        // land mid-test (under full-suite load) and drop this test's keys.
        cfg.initialSeeding.join()
        cfg.clearAll()
    }

    @Test
    fun noReconcile_persistsTheBalance() = runBlocking {
        assertTrue(cfg.setLastShieldedBalanceDuffsUnlessReconciling(1_000_000_000L, WALLET_A))
        assertEquals(1_000_000_000L, cfg.getLastShieldedBalanceDuffs(WALLET_A))
        // Written with it: the value is wallet A's, never another wallet's.
        assertEquals(WALLET_A, cfg.get(DashPayConfig.LAST_SHIELDED_BALANCE_WALLET))
        assertNull(cfg.getLastShieldedBalanceDuffs(WALLET_B))
    }

    @Test
    fun aValueAnEarlierVersionStored_isNotRead() = runBlocking {
        cfg.set(DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS, 5_000L)
        assertNull(cfg.getLastShieldedBalanceDuffs(WALLET_A))
    }

    @Test
    fun theSpendDropsTheWalletTagWithTheValue() = runBlocking {
        assertTrue(cfg.setLastShieldedBalanceDuffsUnlessReconciling(1_000_000_000L, WALLET_A))
        cfg.markShieldedSpendReconcilePending(1_000L)
        assertNull(cfg.get(DashPayConfig.LAST_SHIELDED_BALANCE_WALLET))
    }

    @Test
    fun aPreSpendSnapshot_writtenAfterTheSpend_isRejected_andStaysGoneAfterRestart() = runBlocking {
        assertTrue(cfg.setLastShieldedBalanceDuffsUnlessReconciling(1_000_000_000L, WALLET_A))

        // the spend lands first: drops the cache, sets the marker
        cfg.markShieldedSpendReconcilePending(1_000L)
        // the delayed persistence write of the PRE-spend sample lands second
        assertFalse(cfg.setLastShieldedBalanceDuffsUnlessReconciling(1_000_000_000L, WALLET_A))
        assertNull(cfg.getLastShieldedBalanceDuffs(WALLET_A))

        // what a restart reads back: the cache stays gone, the marker stays set
        assertNull("the pre-spend cache must not come back", cfg.getLastShieldedBalanceDuffs(WALLET_A))
        assertEquals(1_000L, cfg.shieldedSpendReconcilePendingSinceMs())
    }

    @Test
    fun aPreSpendSnapshot_writtenBeforeTheSpend_isRemovedByIt() = runBlocking {
        // the persistence write lands first, the spend second
        assertTrue(cfg.setLastShieldedBalanceDuffsUnlessReconciling(1_000_000_000L, WALLET_A))
        cfg.markShieldedSpendReconcilePending(1_000L)
        assertNull(cfg.getLastShieldedBalanceDuffs(WALLET_A))
    }

    @Test
    fun afterSettlement_theSettledBalanceIsPersistedAgain() = runBlocking {
        cfg.markShieldedSpendReconcilePending(1_000L)
        cfg.clearShieldedSpendReconcilePending()
        assertNull(cfg.shieldedSpendReconcilePendingSinceMs())
        assertTrue(cfg.setLastShieldedBalanceDuffsUnlessReconciling(600_000_000L, WALLET_A))
        assertEquals(600_000_000L, cfg.getLastShieldedBalanceDuffs(WALLET_A))
    }

    /** Overlapping spends: the reconciliation keeps the EARLIEST since until it settles. */
    @Test
    fun overlappingSpends_keepTheEarliestSince() = runBlocking {
        cfg.markShieldedSpendReconcilePending(2_000L)
        cfg.markShieldedSpendReconcilePending(3_000L)
        assertEquals(2_000L, cfg.shieldedSpendReconcilePendingSinceMs())
        cfg.markShieldedSpendReconcilePending(1_500L)
        assertEquals(1_500L, cfg.shieldedSpendReconcilePendingSinceMs())

        // settled: marker and since go together; the next spend starts fresh
        cfg.clearShieldedSpendReconcilePending()
        assertNull(cfg.shieldedSpendReconcilePendingSinceMs())
        assertNull(cfg.get(DashPayConfig.SHIELDED_SPEND_RECONCILE_SINCE_MS))
        cfg.markShieldedSpendReconcilePending(9_000L)
        assertEquals(9_000L, cfg.shieldedSpendReconcilePendingSinceMs())
    }

    /** A marker written by an older build has no since: every activity row counts (0), and stays so. */
    @Test
    fun aMarkerWithoutASince_readsAsZero_andKeepsZero() = runBlocking {
        cfg.set(DashPayConfig.SHIELDED_SPEND_RECONCILE_PENDING, true)
        assertEquals(0L, cfg.shieldedSpendReconcilePendingSinceMs())
        cfg.markShieldedSpendReconcilePending(5_000L)
        assertEquals(0L, cfg.shieldedSpendReconcilePendingSinceMs())
    }

    /**
     * An unreadable store must THROW from the marker read, not read as "not
     * pending" ([BaseConfig]'s `data` rescues IOException as empty preferences).
     */
    @Test
    fun anUnreadableStore_makesTheMarkerReadThrow() = runBlocking {
        val broken = spyk(cfg)
        every { broken.observePreservingErrors(DashPayConfig.SHIELDED_SPEND_RECONCILE_PENDING) } returns
            flow { throw IOException("unreadable") }
        try {
            broken.shieldedSpendReconcilePendingSinceMs()
            fail("expected the IOException")
        } catch (expected: IOException) {
        }
    }

    /** Likewise for the since: an unreadable since must not read as "no cutoff known yet". */
    @Test
    fun anUnreadableSince_makesTheReadThrow() = runBlocking {
        cfg.markShieldedSpendReconcilePending(1_000L)
        val broken = spyk(cfg)
        every { broken.observePreservingErrors(DashPayConfig.SHIELDED_SPEND_RECONCILE_SINCE_MS) } returns
            flow { throw IOException("unreadable") }
        try {
            broken.shieldedSpendReconcilePendingSinceMs()
            fail("expected the IOException")
        } catch (expected: IOException) {
        }
    }

    private companion object {
        const val WALLET_A = "aaaa"
        const val WALLET_B = "bbbb"
    }
}
