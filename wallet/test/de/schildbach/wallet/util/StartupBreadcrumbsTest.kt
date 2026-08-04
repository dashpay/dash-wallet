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
        assertNull(StartupBreadcrumbs.parseStage("# launch Mon Aug 03 (previous=NONE failures=1 safeMode=false)"))
        assertNull(StartupBreadcrumbs.parseStage(""))
        assertNull(StartupBreadcrumbs.parseStage("   "))
        assertNull(StartupBreadcrumbs.parseStage("not a stage line"))
    }

    // ── isLaunchComplete / lastStage ──────────────────────────────────

    private fun trail(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    @Test
    fun theMilestoneAlone_makesALaunchComplete() {
        // ONCREATE_COMPLETE is the milestone: everything that can crash-loop
        // the app is behind it. No survival timer required.
        val content = trail(
            "# launch header",
            "0 APP_ONCREATE +1ms",
            "4 WALLET_LOAD_BEGIN +100ms",
            "11 ONCREATE_COMPLETE +9000ms"
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
    fun engineLaneAlone_countsAsComplete() {
        // Corroboration: a trail that plainly shows a running app is never a
        // launch death, even if the milestone line itself were lost.
        val content = trail(
            "0 APP_ONCREATE +1ms",
            "20 SDK_BIND_KICKED +8100ms",
            "21 SDK_L1_ENGINE_STARTING +9000ms"
        )
        assertTrue(StartupBreadcrumbs.isLaunchComplete(content))
    }

    @Test
    fun completionStages_table() {
        assertFalse(StartupBreadcrumbs.isLaunchCompleteStage(StartupBreadcrumbs.STAGE_APP_ONCREATE))
        assertFalse(StartupBreadcrumbs.isLaunchCompleteStage(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN))
        assertFalse(StartupBreadcrumbs.isLaunchCompleteStage(StartupBreadcrumbs.STAGE_PLATFORM_INIT_KICKED))
        assertTrue(StartupBreadcrumbs.isLaunchCompleteStage(StartupBreadcrumbs.STAGE_LAUNCH_COMPLETE))
        assertTrue(StartupBreadcrumbs.isLaunchCompleteStage(StartupBreadcrumbs.STAGE_MAIN_UI_SHOWN))
        assertTrue(StartupBreadcrumbs.isLaunchCompleteStage(StartupBreadcrumbs.STAGE_LAUNCH_SURVIVED))
        assertTrue(StartupBreadcrumbs.isLaunchCompleteStage(StartupBreadcrumbs.STAGE_CUTOVER_SERVICES_STARTED))
        // degradation markers prove nothing about completion on their own
        assertFalse(StartupBreadcrumbs.isLaunchCompleteStage(StartupBreadcrumbs.STAGE_WALLET_LOAD_FAILED))
        assertFalse(StartupBreadcrumbs.isLaunchCompleteStage(StartupBreadcrumbs.STAGE_WALLET_LOAD_OVERBUDGET))
    }

    @Test
    fun emptyTrail_hasNoLastStage() {
        assertNull(StartupBreadcrumbs.lastStage("# header only\n"))
    }

    // ── classifyPrevious ──────────────────────────────────────────────

    @Test
    fun classifyPrevious_table() {
        assertEquals(StartupBreadcrumbs.PreviousLaunch.NONE, StartupBreadcrumbs.classifyPrevious(null))
        assertEquals(StartupBreadcrumbs.PreviousLaunch.NONE, StartupBreadcrumbs.classifyPrevious(""))
        assertEquals(
            StartupBreadcrumbs.PreviousLaunch.COMPLETE,
            StartupBreadcrumbs.classifyPrevious(
                trail("# launch h (previous=NONE failures=0 safeMode=false)", "11 ONCREATE_COMPLETE +9000ms")
            )
        )
        assertEquals(
            StartupBreadcrumbs.PreviousLaunch.SAFE_MODE,
            StartupBreadcrumbs.classifyPrevious(
                trail("# launch h (previous=INCOMPLETE_PRE_MILESTONE failures=1 safeMode=true)", "0 APP_ONCREATE +1ms")
            )
        )
        assertEquals(
            StartupBreadcrumbs.PreviousLaunch.INCOMPLETE_PRE_MILESTONE,
            StartupBreadcrumbs.classifyPrevious(
                trail("# launch h (previous=NONE failures=0 safeMode=false)", "4 WALLET_LOAD_BEGIN +100ms")
            )
        )
        // header only / clobbered file: NOT provably a death
        assertEquals(
            StartupBreadcrumbs.PreviousLaunch.INCOMPLETE_UNKNOWN,
            StartupBreadcrumbs.classifyPrevious(trail("# launch h (previous=NONE failures=0 safeMode=false)"))
        )
    }

    @Test
    fun safeModeLaunchIsNeutralEvenAfterShowingItsUi() {
        // The degraded screen writes MAIN_UI_SHOWN. That must not be read as
        // "the wallet load is fine" — it proves nothing, the load was skipped.
        assertEquals(
            StartupBreadcrumbs.PreviousLaunch.SAFE_MODE,
            StartupBreadcrumbs.classifyPrevious(
                trail(
                    "# launch h (previous=INCOMPLETE_PRE_MILESTONE failures=1 safeMode=true)",
                    "91 WALLET_LOAD_SKIPPED_SAFE_MODE +80ms",
                    "12 DEGRADED_UI_SHOWN +900ms"
                )
            )
        )
    }

    @Test
    fun safeModeLaunchWhoseRetrySucceeded_isComplete() {
        // The in-process escape hatch proved the load works — that IS evidence.
        assertEquals(
            StartupBreadcrumbs.PreviousLaunch.COMPLETE,
            StartupBreadcrumbs.classifyPrevious(
                trail(
                    "# launch h (previous=INCOMPLETE_PRE_MILESTONE failures=1 safeMode=true)",
                    "91 WALLET_LOAD_SKIPPED_SAFE_MODE +80ms",
                    "98 SAFE_MODE_RETRY +5000ms",
                    "99 SAFE_MODE_RETRY_OK +7000ms"
                )
            )
        )
    }

    // ── THE REGRESSION: a completed launch killed later is NOT a failure ──

    @Test
    fun completedLaunchKilledHoursLater_isCOMPLETE_neverAFailure() {
        // The QA-device shape: the app started fine and ran all the way through
        // CUTOVER_SERVICES_STARTED, then Android's lowmemorykiller reclaimed the
        // BACKGROUNDED process. There is no MAIN_UI_SHOWN / survival marker in
        // the trail, but the launch plainly succeeded.
        val content = trail(
            "# launch Sun Aug 03 (previous=COMPLETE failures=0 safeMode=false)",
            "0 APP_ONCREATE +1ms",
            "3 CONFIG_LOADED +40ms",
            "4 WALLET_LOAD_BEGIN +45ms size=2535811",
            "5 WALLET_PROTOBUF_PARSED +2100ms",
            "11 ONCREATE_COMPLETE +4200ms",
            "20 SDK_BIND_KICKED +4300ms",
            "22 SDK_L1_ENGINE_STARTED +9000ms",
            "23 CUTOVER_SERVICES_STARTED +9500ms"
        )
        assertTrue(StartupBreadcrumbs.isLaunchComplete(content))
        assertEquals(StartupBreadcrumbs.PreviousLaunch.COMPLETE, StartupBreadcrumbs.classifyPrevious(content))
        // …and therefore it clears, rather than accumulates, strikes.
        assertEquals(
            StartupBreadcrumbs.LaunchState(0, 0, false),
            StartupBreadcrumbs.nextLaunchState(StartupBreadcrumbs.PreviousLaunch.COMPLETE, 1, 1)
        )
    }

    // ── nextLaunchState: the crash-loop breaker table ─────────────────

    @Test
    fun completePreviousLaunch_resetsEverything_noSafeMode() {
        assertEquals(
            StartupBreadcrumbs.LaunchState(0, 0, false),
            StartupBreadcrumbs.nextLaunchState(StartupBreadcrumbs.PreviousLaunch.COMPLETE, 0, 0)
        )
        assertEquals(
            StartupBreadcrumbs.LaunchState(0, 0, false),
            StartupBreadcrumbs.nextLaunchState(StartupBreadcrumbs.PreviousLaunch.COMPLETE, 5, 2)
        )
        assertEquals(
            StartupBreadcrumbs.LaunchState(0, 0, false),
            StartupBreadcrumbs.nextLaunchState(StartupBreadcrumbs.PreviousLaunch.NONE, 3, 1)
        )
    }

    @Test
    fun firstPreMilestoneDeath_countsButDoesNotTripSafeMode() {
        assertEquals(
            StartupBreadcrumbs.LaunchState(1, 0, false),
            StartupBreadcrumbs.nextLaunchState(StartupBreadcrumbs.PreviousLaunch.INCOMPLETE_PRE_MILESTONE, 0, 0)
        )
    }

    @Test
    fun secondConsecutivePreMilestoneDeath_tripsSafeMode_andDecaysCounter() {
        // Reaching the threshold advises safe mode AND stores threshold-1, so
        // ONE more death after the safe-mode launch re-trips it immediately.
        assertEquals(
            StartupBreadcrumbs.LaunchState(1, 1, true),
            StartupBreadcrumbs.nextLaunchState(StartupBreadcrumbs.PreviousLaunch.INCOMPLETE_PRE_MILESTONE, 1, 0)
        )
    }

    @Test
    fun safeModePreviousLaunch_isNeutral_normalRetryWithCountersKept() {
        // The safe-mode launch skips the load by design — it must neither count
        // as a death nor clear the strikes, and the NEXT launch is always normal.
        assertEquals(
            StartupBreadcrumbs.LaunchState(1, 1, false),
            StartupBreadcrumbs.nextLaunchState(StartupBreadcrumbs.PreviousLaunch.SAFE_MODE, 1, 1)
        )
    }

    @Test
    fun unknownPreviousLaunch_isNeutral_neverPushesTowardSafeMode() {
        // A clobbered/unwritable trail is not evidence of a death.
        assertEquals(
            StartupBreadcrumbs.LaunchState(1, 0, false),
            StartupBreadcrumbs.nextLaunchState(StartupBreadcrumbs.PreviousLaunch.INCOMPLETE_UNKNOWN, 1, 0)
        )
    }

    @Test
    fun hardCap_forcesANormalLoadAttemptRegardlessOfStrikes() {
        // MAX_SAFE_MODE_RUNS safe-mode launches with no completed launch in
        // between → try a normal load anyway, and restart the run counter.
        assertEquals(
            StartupBreadcrumbs.LaunchState(1, 0, false),
            StartupBreadcrumbs.nextLaunchState(
                StartupBreadcrumbs.PreviousLaunch.INCOMPLETE_PRE_MILESTONE,
                1,
                StartupBreadcrumbs.MAX_SAFE_MODE_RUNS
            )
        )
    }

    @Test
    fun crashLoopAlternates_andHonoursTheConsecutiveSafeModeCap() {
        // Persistent launch-killer. The user must ALWAYS get a normal load
        // attempt between degraded screens, and the cap must force an extra one.
        var failures = 0
        var runs = 0
        val verdicts = mutableListOf<Boolean>()
        var previous = StartupBreadcrumbs.PreviousLaunch.NONE
        repeat(8) {
            val state = StartupBreadcrumbs.nextLaunchState(previous, failures, runs)
            verdicts += state.safeMode
            failures = state.failures
            runs = state.safeModeRuns
            // a safe-mode launch opens (by construction); a normal launch dies
            previous = if (state.safeMode) {
                StartupBreadcrumbs.PreviousLaunch.SAFE_MODE
            } else {
                StartupBreadcrumbs.PreviousLaunch.INCOMPLETE_PRE_MILESTONE
            }
        }
        assertEquals(listOf(false, false, true, false, true, false, false, true), verdicts)
        // never two safe-mode launches in a row
        assertFalse(verdicts.zipWithNext().any { (a, b) -> a && b })
    }

    @Test
    fun recoveredLaunch_afterSafeMode_clearsTheLoopState() {
        val state = StartupBreadcrumbs.nextLaunchState(StartupBreadcrumbs.PreviousLaunch.COMPLETE, 1, 1)
        assertEquals(0, state.failures)
        assertEquals(0, state.safeModeRuns)
        assertFalse(state.safeMode)
    }

    // ── init(): end-to-end file behaviour ─────────────────────────────

    private fun freshDir(): File = Files.createTempDirectory("breadcrumbs-test").toFile()

    @Test
    fun init_preservesIncompleteTrail_andTripsSafeModeOnSecondDeath() {
        val dir = freshDir()
        try {
            // Launch 1: dies during the wallet load (never reaches the milestone).
            StartupBreadcrumbs.init(dir)
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN", "size=123")
            // (process dies here)

            // Launch 2: sees one pre-milestone death — still normal.
            StartupBreadcrumbs.init(dir)
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            val preserved = File(dir, "startup.breadcrumbs.prev")
            assertTrue("the dying launch's trail must be preserved", preserved.exists())
            assertTrue(preserved.readText().contains("WALLET_LOAD_BEGIN"))
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN", "size=123")
            // (process dies again)

            // Launch 3: two consecutive pre-milestone deaths — safe mode.
            StartupBreadcrumbs.init(dir)
            assertTrue(StartupBreadcrumbs.isSafeModeAdvised())
            StartupBreadcrumbs.mark(
                StartupBreadcrumbs.STAGE_WALLET_LOAD_SKIPPED_SAFE_MODE, "WALLET_LOAD_SKIPPED_SAFE_MODE"
            )
            StartupBreadcrumbs.markLaunchComplete()
            StartupBreadcrumbs.markMainUiShown("DEGRADED_UI_SHOWN")

            // Launch 4: the previous launch was SAFE MODE (neutral) → NORMAL start.
            StartupBreadcrumbs.init(dir)
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            // …and the preserved trail still holds the ORIGINAL crash evidence
            // (the safe-mode trail must not overwrite it).
            assertTrue(File(dir, "startup.breadcrumbs.prev").readText().contains("WALLET_LOAD_BEGIN"))

            // Launch 4 dies again mid-load → launch 5 is safe mode again.
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN", "size=123")
            StartupBreadcrumbs.init(dir)
            assertTrue(StartupBreadcrumbs.isSafeModeAdvised())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun init_launchReachingTheMilestone_clearsTheLatchImmediately() {
        val dir = freshDir()
        try {
            // Two deaths → launch 3 is safe mode.
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN")
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN")
            StartupBreadcrumbs.init(dir)
            assertTrue(StartupBreadcrumbs.isSafeModeAdvised())

            // Launch 4 runs normally and REACHES the milestone → strikes cleared
            // on the spot, even though it is later killed with no UI markers.
            StartupBreadcrumbs.init(dir)
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            StartupBreadcrumbs.markLaunchComplete()
            assertEquals("0", File(dir, "startup.failures").readText().trim())
            assertEquals("0", File(dir, "startup.safemoderuns").readText().trim())
            // (killed by the lowmemorykiller hours later — no survival marker)

            // Launch 5 must be a plain normal start.
            StartupBreadcrumbs.init(dir)
            assertFalse("a completed launch killed later is not a launch failure",
                StartupBreadcrumbs.isSafeModeAdvised())
            assertTrue(File(dir, "startup.breadcrumbs").readText().contains("previous=COMPLETE"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun init_safeModeLaunchDoesNotClearStrikesAtItsOwnMilestone() {
        val dir = freshDir()
        try {
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN")
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN")
            StartupBreadcrumbs.init(dir)
            assertTrue(StartupBreadcrumbs.isSafeModeAdvised())
            // The safe-mode launch reaches the milestone too — but it skipped the
            // load, so it proves nothing and must not zero the strike counter.
            StartupBreadcrumbs.markLaunchComplete()
            assertEquals("1", File(dir, "startup.failures").readText().trim())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clearSafeModeLatch_escapesSafeModeInProcessAndOnDisk() {
        val dir = freshDir()
        try {
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN")
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN")
            StartupBreadcrumbs.init(dir)
            assertTrue(StartupBreadcrumbs.isSafeModeAdvised())

            // The user taps "Try Again" and the retried load succeeds.
            StartupBreadcrumbs.clearSafeModeLatch()
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            assertEquals("0", File(dir, "startup.failures").readText().trim())
            assertEquals("0", File(dir, "startup.safemoderuns").readText().trim())

            // The next launch is a plain normal start, classified COMPLETE.
            StartupBreadcrumbs.init(dir)
            assertFalse(StartupBreadcrumbs.isSafeModeAdvised())
            assertTrue(File(dir, "startup.breadcrumbs").readText().contains("previous=COMPLETE"))
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
    fun init_headerRecordsWhyThePreviousLaunchWasIncomplete() {
        val dir = freshDir()
        try {
            StartupBreadcrumbs.init(dir)
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN", "size=123")
            StartupBreadcrumbs.init(dir)

            val header = File(dir, "startup.breadcrumbs").readText().lineSequence().first()
            assertTrue(header, header.contains("previous=INCOMPLETE_PRE_MILESTONE"))
            assertTrue(header, header.contains("prevLastStage=" + StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN))
            assertTrue(header, header.contains("failures=1"))
            assertTrue(header, header.contains("safeModeRuns=0"))
            assertTrue(header, header.contains("safeMode=false"))
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
            assertTrue(report.contains("INCOMPLETE_PRE_MILESTONE"))
            assertTrue(report.contains("size=3000000000"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mark_beforeInit_neverThrows() {
        // Diagnostics must never take the app down, even if misused.
        StartupBreadcrumbs.mark(1, "ANYTHING")
        StartupBreadcrumbs.markLaunchComplete()
        StartupBreadcrumbs.markMainUiShown()
        StartupBreadcrumbs.clearSafeModeLatch()
        StartupBreadcrumbs.reportText()
    }
}
