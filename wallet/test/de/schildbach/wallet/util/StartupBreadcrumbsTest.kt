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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StartupBreadcrumbsTest {

    // ── parseStage ────────────────────────────────────────────────────

    @Test
    fun parseStage_readsLeadingNumber() {
        assertEquals(4, StartupBreadcrumbs.parseStage("4 WALLET_LOAD_BEGIN +312ms size=3000000000"))
        assertEquals(0, StartupBreadcrumbs.parseStage("0 APP_ONCREATE +1ms"))
        assertEquals(90, StartupBreadcrumbs.parseStage("90 WALLET_LOAD_FAILED +5000ms java.lang.OutOfMemoryError"))
    }

    @Test
    fun parseStage_ignoresHeadersAndGarbage() {
        assertNull(StartupBreadcrumbs.parseStage("# launch Mon Aug 03 (prevIncomplete=true failures=1 safeMode=false)"))
        assertNull(StartupBreadcrumbs.parseStage(""))
        assertNull(StartupBreadcrumbs.parseStage("   "))
        assertNull(StartupBreadcrumbs.parseStage("not a stage line"))
    }

    // ── isLaunchComplete / lastStage ──────────────────────────────────

    private fun trail(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    @Test
    fun launchWithSurvivalMarker_isComplete() {
        val content = trail(
            "# launch header",
            "0 APP_ONCREATE +1ms",
            "4 WALLET_LOAD_BEGIN +100ms",
            "11 ONCREATE_COMPLETE +9000ms",
            "12 MAIN_UI_SHOWN +40000ms"
        )
        assertTrue(StartupBreadcrumbs.isLaunchComplete(content))
    }

    @Test
    fun launchDyingInWalletLoad_isIncomplete_andLastStageIsTheDyingStage() {
        val content = trail(
            "# launch header",
            "0 APP_ONCREATE +1ms",
            "3 CONFIG_LOADED +50ms",
            "4 WALLET_LOAD_BEGIN +100ms size=3000000000"
        )
        assertFalse(StartupBreadcrumbs.isLaunchComplete(content))
        assertEquals(4, StartupBreadcrumbs.lastStage(content))
    }

    @Test
    fun launchDyingAfterOnCreate_beforeSurvivalMarker_isIncomplete() {
        // The native-crash shape: onCreate completed, the SDK engine started,
        // the process died before the delayed survival marker fired.
        val content = trail(
            "0 APP_ONCREATE +1ms",
            "11 ONCREATE_COMPLETE +8000ms",
            "20 SDK_BIND_KICKED +8100ms",
            "21 SDK_L1_ENGINE_STARTING +9000ms"
        )
        assertFalse(StartupBreadcrumbs.isLaunchComplete(content))
        assertEquals(21, StartupBreadcrumbs.lastStage(content))
    }

    @Test
    fun asyncLaneMayInterleave_survivalStillDetectedAnywhereInTrail() {
        // MAIN_UI_SHOWN is not necessarily the last line — async engine-lane
        // marks can land after it. Completion is containment, not last-line.
        val content = trail(
            "11 ONCREATE_COMPLETE +8000ms",
            "12 MAIN_UI_SHOWN +38000ms",
            "22 SDK_L1_ENGINE_STARTED +39000ms"
        )
        assertTrue(StartupBreadcrumbs.isLaunchComplete(content))
    }

    @Test
    fun emptyTrail_hasNoLastStage() {
        assertNull(StartupBreadcrumbs.lastStage("# header only\n"))
    }

    // ── classifyPrevious ──────────────────────────────────────────────

    @Test
    fun classifyPrevious_table() {
        assertEquals(StartupBreadcrumbs.PreviousLaunch.NONE, StartupBreadcrumbs.classifyPrevious(null))
        assertEquals(
            StartupBreadcrumbs.PreviousLaunch.COMPLETE,
            StartupBreadcrumbs.classifyPrevious(trail("# launch h (previous=NONE failures=0 safeMode=false)", "12 MAIN_UI_SHOWN +30s"))
        )
        assertEquals(
            StartupBreadcrumbs.PreviousLaunch.SAFE_MODE,
            StartupBreadcrumbs.classifyPrevious(trail("# launch h (previous=INCOMPLETE failures=1 safeMode=true)", "0 APP_ONCREATE +1ms"))
        )
        assertEquals(
            StartupBreadcrumbs.PreviousLaunch.INCOMPLETE,
            StartupBreadcrumbs.classifyPrevious(trail("# launch h (previous=NONE failures=0 safeMode=false)", "4 WALLET_LOAD_BEGIN +100ms"))
        )
    }

    // ── nextCounterAndSafeMode: the crash-loop breaker table ──────────

    @Test
    fun completePreviousLaunch_resetsCounter_noSafeMode() {
        assertEquals(0 to false, StartupBreadcrumbs.nextCounterAndSafeMode(StartupBreadcrumbs.PreviousLaunch.COMPLETE, 0))
        assertEquals(0 to false, StartupBreadcrumbs.nextCounterAndSafeMode(StartupBreadcrumbs.PreviousLaunch.COMPLETE, 5))
        assertEquals(0 to false, StartupBreadcrumbs.nextCounterAndSafeMode(StartupBreadcrumbs.PreviousLaunch.NONE, 3))
    }

    @Test
    fun firstIncompleteLaunch_countsButDoesNotTripSafeMode() {
        assertEquals(1 to false, StartupBreadcrumbs.nextCounterAndSafeMode(StartupBreadcrumbs.PreviousLaunch.INCOMPLETE, 0))
    }

    @Test
    fun secondConsecutiveIncompleteLaunch_tripsSafeMode_andDecaysCounter() {
        // Reaching the threshold advises safe mode AND stores threshold-1, so
        // ONE more death after the safe-mode launch re-trips it immediately.
        assertEquals(1 to true, StartupBreadcrumbs.nextCounterAndSafeMode(StartupBreadcrumbs.PreviousLaunch.INCOMPLETE, 1))
    }

    @Test
    fun safeModePreviousLaunch_isNeutral_normalRetryWithCounterKept() {
        // The safe-mode launch never shows the main UI by design — it must
        // neither count as a death nor clear the strikes.
        assertEquals(1 to false, StartupBreadcrumbs.nextCounterAndSafeMode(StartupBreadcrumbs.PreviousLaunch.SAFE_MODE, 1))
    }

    @Test
    fun crashLoopAlternates_safeModeEveryOtherLaunch_neverPermanentLockout() {
        // Persistent launch-killer: crash → crash → SAFE → crash → SAFE → …
        var counter = 0
        val verdicts = mutableListOf<Boolean>()
        var previous = StartupBreadcrumbs.PreviousLaunch.NONE
        repeat(7) {
            val (next, safeMode) = StartupBreadcrumbs.nextCounterAndSafeMode(previous, counter)
            verdicts += safeMode
            counter = next
            // a safe-mode launch survives (by construction); a normal launch
            // dies in this scenario
            previous = if (safeMode) StartupBreadcrumbs.PreviousLaunch.SAFE_MODE
            else StartupBreadcrumbs.PreviousLaunch.INCOMPLETE
        }
        assertEquals(listOf(false, false, true, false, true, false, true), verdicts)
        // The app alternates: every other launch OPENS with the report dialog,
        // and every non-safe launch retries a full normal start.
    }

    @Test
    fun recoveredLaunch_afterSafeMode_clearsTheLoopState() {
        // SAFE-mode launch → user relaunches → normal start survives → reset.
        val (afterRecovery, safeMode) = StartupBreadcrumbs.nextCounterAndSafeMode(StartupBreadcrumbs.PreviousLaunch.COMPLETE, 1)
        assertEquals(0, afterRecovery)
        assertFalse(safeMode)
    }

    // ── init(): end-to-end file behaviour ─────────────────────────────

    private fun freshDir(): File = Files.createTempDirectory("breadcrumbs-test").toFile()

    @Test
    fun init_preservesIncompleteTrail_andTripsSafeModeOnSecondDeath() {
        val dir = freshDir()
        try {
            // Launch 1: dies during the wallet load (no survival marker).
            StartupBreadcrumbs.init(dir)
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN", "size=123")
            // (process dies here)

            // Launch 2: sees one incomplete launch — still normal.
            StartupBreadcrumbs.init(dir)
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            val preserved = File(dir, "startup.breadcrumbs.prev")
            assertTrue("the dying launch's trail must be preserved", preserved.exists())
            assertTrue(preserved.readText().contains("WALLET_LOAD_BEGIN"))
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN", "size=123")
            // (process dies again)

            // Launch 3: two consecutive deaths — safe mode.
            StartupBreadcrumbs.init(dir)
            assertTrue(StartupBreadcrumbs.isSafeModeAdvised())

            // Launch 4: the previous launch was SAFE MODE (neutral — it never
            // shows the main UI by design) → retry a NORMAL start.
            StartupBreadcrumbs.init(dir)
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            // …and the preserved trail still holds the ORIGINAL crash evidence
            // (the safe-mode trail must not overwrite it).
            assertTrue(File(dir, "startup.breadcrumbs.prev").readText().contains("WALLET_LOAD_BEGIN"))

            // Launch 4 dies again mid-load → launch 5 is safe mode again:
            // the alternation, never a permanent lock-out.
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN", "size=123")
            StartupBreadcrumbs.init(dir)
            assertTrue(StartupBreadcrumbs.isSafeModeAdvised())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun init_survivedLaunch_resetsEverything() {
        val dir = freshDir()
        try {
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN")
            StartupBreadcrumbs.markLaunchSurvived()

            StartupBreadcrumbs.init(dir)
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            assertEquals("0", File(dir, "startup.failures").readText().trim())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun reportText_containsCurrentAndPreviousTrails() {
        val dir = freshDir()
        try {
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN", "size=3000000000")
            // dies; next launch:
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_CONFIG_LOADED, "CONFIG_LOADED")

            val report = StartupBreadcrumbs.reportText()
            assertTrue(report.contains("current launch"))
            assertTrue(report.contains("CONFIG_LOADED"))
            assertTrue(report.contains("previous INCOMPLETE launch"))
            assertTrue(report.contains("size=3000000000"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mark_beforeInit_neverThrows() {
        // Diagnostics must never take the app down, even if misused.
        StartupBreadcrumbs.mark(1, "ANYTHING")
        StartupBreadcrumbs.reportText()
    }
}
