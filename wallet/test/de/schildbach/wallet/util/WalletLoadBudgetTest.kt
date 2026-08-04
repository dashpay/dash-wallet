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

package de.schildbach.wallet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WalletLoadBudgetTest {

    // ── isOverBudget ─────────────────────────────────────────────────────

    @Test
    fun isOverBudget_table() {
        assertFalse(WalletLoadBudget.isOverBudget(0, 20_000))
        assertFalse(WalletLoadBudget.isOverBudget(19_999, 20_000))
        assertTrue(WalletLoadBudget.isOverBudget(20_000, 20_000))
        assertTrue(WalletLoadBudget.isOverBudget(120_000, 20_000))
    }

    @Test
    fun defaultBudget_sitsAboveAHealthyLoadAndBelowEveryObservedDeath() {
        // The tester's dying launches were 20-50s inside readWallet; a healthy
        // load (with the friend-chain lookahead deferred) is a couple of seconds.
        assertTrue(WalletLoadBudget.DEFAULT_BUDGET_MS in 5_000..30_000)
    }

    // ── the watchdog ─────────────────────────────────────────────────────

    @Test
    fun watchdog_firesWhenTheGuardedWorkOverrunsTheBudget() {
        val fired = CountDownLatch(1)
        val watchdog = WalletLoadBudget.arm(30L) { fired.countDown() }
        try {
            assertTrue("the over-budget handler must run", fired.await(5, TimeUnit.SECONDS))
        } finally {
            watchdog.disarm()
        }
    }

    @Test
    fun watchdog_doesNotFireWhenDisarmedInTime() {
        val fires = AtomicInteger()
        val watchdog = WalletLoadBudget.arm(2_000L) { fires.incrementAndGet() }
        val elapsed = watchdog.disarm()
        Thread.sleep(200)
        assertEquals("a load that finished in time must not be flagged", 0, fires.get())
        assertTrue("disarm reports the guarded elapsed time", elapsed >= 0)
    }

    @Test
    fun watchdog_handlerThrowing_neverEscapes() {
        // A diagnostic must not be able to take a launch down.
        val watchdog = WalletLoadBudget.arm(20L) { throw RuntimeException("boom") }
        Thread.sleep(300)
        watchdog.disarm()
    }

    // ── integration with the crash-loop breaker ──────────────────────────

    @Test
    fun overBudgetLaunchThatDies_putsTheNextLaunchInSafeMode() {
        val dir = Files.createTempDirectory("budget-test").toFile()
        try {
            // Launch 1: normal start, blows the wallet-load budget, then dies.
            StartupBreadcrumbs.init(dir)
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN", "size=2535811")
            StartupBreadcrumbs.mark(
                StartupBreadcrumbs.STAGE_WALLET_LOAD_OVERBUDGET, "WALLET_LOAD_OVERBUDGET",
                "budgetMs=20000 pendingFriendChains=67"
            )
            StartupBreadcrumbs.armSafeModeOnNextDeath()
            // (killed here — no survival marker)

            // Launch 2: ONE death is now enough, because the budget guard armed it.
            StartupBreadcrumbs.init(dir)
            assertTrue("an over-budget death must open safe mode on the very next launch",
                StartupBreadcrumbs.isSafeModeAdvised())
            // …and the over-budget evidence rides along in the support report.
            assertTrue(StartupBreadcrumbs.reportText().contains("WALLET_LOAD_OVERBUDGET"))
            assertTrue(StartupBreadcrumbs.reportText().contains("pendingFriendChains=67"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun overBudgetLaunchThatSURVIVES_neverTripsSafeMode() {
        val dir = Files.createTempDirectory("budget-test").toFile()
        try {
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN")
            StartupBreadcrumbs.armSafeModeOnNextDeath()
            StartupBreadcrumbs.markLaunchSurvived()

            StartupBreadcrumbs.init(dir)
            assertFalse("a slow-but-successful launch must not be punished",
                StartupBreadcrumbs.isSafeModeAdvised())
            assertEquals("0", File(dir, "startup.failures").readText().trim())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun armSafeModeOnNextDeath_neverLowersAnExistingStrikeCount() {
        val dir = Files.createTempDirectory("budget-test").toFile()
        try {
            StartupBreadcrumbs.init(dir)
            File(dir, "startup.failures").writeText("5")
            StartupBreadcrumbs.armSafeModeOnNextDeath()
            assertEquals("5", File(dir, "startup.failures").readText().trim())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun armSafeModeOnNextDeath_beforeInit_neverThrows() {
        StartupBreadcrumbs.armSafeModeOnNextDeath()
    }
}
