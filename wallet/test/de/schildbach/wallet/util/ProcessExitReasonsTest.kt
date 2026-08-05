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
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitReasonsTest {

    // ── reasonName ────────────────────────────────────────────────────

    @Test
    fun reasonName_mapsEveryDocumentedReason() {
        assertEquals("UNKNOWN", ProcessExitReasons.reasonName(0))
        assertEquals("EXIT_SELF", ProcessExitReasons.reasonName(1))
        assertEquals("SIGNALED", ProcessExitReasons.reasonName(2))
        assertEquals("LOW_MEMORY_LMK", ProcessExitReasons.reasonName(3))
        assertEquals("CRASH", ProcessExitReasons.reasonName(4))
        assertEquals("CRASH_NATIVE", ProcessExitReasons.reasonName(5))
        assertEquals("ANR", ProcessExitReasons.reasonName(6))
        assertEquals("INITIALIZATION_FAILURE", ProcessExitReasons.reasonName(7))
        assertEquals("PERMISSION_CHANGE", ProcessExitReasons.reasonName(8))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", ProcessExitReasons.reasonName(9))
        assertEquals("USER_REQUESTED", ProcessExitReasons.reasonName(10))
        assertEquals("USER_STOPPED", ProcessExitReasons.reasonName(11))
        assertEquals("DEPENDENCY_DIED", ProcessExitReasons.reasonName(12))
        assertEquals("OTHER", ProcessExitReasons.reasonName(13))
        assertEquals("FREEZER", ProcessExitReasons.reasonName(14))
        assertEquals("PACKAGE_STATE_CHANGE", ProcessExitReasons.reasonName(15))
        assertEquals("PACKAGE_UPDATED", ProcessExitReasons.reasonName(16))
    }

    @Test
    fun reasonName_keepsUnknownFutureValuesVisible() {
        // A newer Android's reason must never be silently lost.
        assertEquals("REASON_17", ProcessExitReasons.reasonName(17))
        assertEquals("REASON_99", ProcessExitReasons.reasonName(99))
        assertEquals("REASON_-1", ProcessExitReasons.reasonName(-1))
    }

    // ── importanceName ────────────────────────────────────────────────

    @Test
    fun importanceName_mapsKnownImportances() {
        assertEquals("FOREGROUND", ProcessExitReasons.importanceName(100))
        assertEquals("FOREGROUND_SERVICE", ProcessExitReasons.importanceName(125))
        assertEquals("VISIBLE", ProcessExitReasons.importanceName(200))
        assertEquals("PERCEPTIBLE", ProcessExitReasons.importanceName(230))
        assertEquals("SERVICE", ProcessExitReasons.importanceName(300))
        assertEquals("TOP_SLEEPING", ProcessExitReasons.importanceName(325))
        assertEquals("CANT_SAVE_STATE", ProcessExitReasons.importanceName(350))
        assertEquals("CACHED", ProcessExitReasons.importanceName(400))
        assertEquals("GONE", ProcessExitReasons.importanceName(1000))
    }

    @Test
    fun importanceName_keepsUnknownValuesVisible() {
        assertEquals("IMPORTANCE_247", ProcessExitReasons.importanceName(247))
        assertEquals("IMPORTANCE_0", ProcessExitReasons.importanceName(0))
    }

    // ── formatKb ──────────────────────────────────────────────────────

    @Test
    fun formatKb_rendersKilobytesAndMegabytes() {
        // getPss()/getRss() are in KB — both units are printed.
        assertEquals("1024KB/1.0MB", ProcessExitReasons.formatKb(1024))
        assertEquals("0KB/0.0MB", ProcessExitReasons.formatKb(0))
        assertEquals("524288KB/512.0MB", ProcessExitReasons.formatKb(524288))
    }

    // ── formatAge ─────────────────────────────────────────────────────

    @Test
    fun formatAge_rendersSecondsMinutesHoursDays() {
        assertEquals("0s ago", ProcessExitReasons.formatAge(0))
        assertEquals("45s ago", ProcessExitReasons.formatAge(45_000))
        // the ~19-minute kill pattern we are chasing
        assertEquals("19m ago", ProcessExitReasons.formatAge(19 * 60_000L))
        assertEquals("3h 12m ago", ProcessExitReasons.formatAge((3 * 60 + 12) * 60_000L))
        assertEquals("2d 3h ago", ProcessExitReasons.formatAge(((2 * 24 + 3) * 60L) * 60_000L))
    }

    @Test
    fun formatAge_survivesClockGoingBackwards() {
        assertEquals("in the future (clock changed)", ProcessExitReasons.formatAge(-5_000))
    }

    // ── formatRecord ──────────────────────────────────────────────────

    private fun lmkRecord(): String = ProcessExitReasons.formatRecord(
        index = 0,
        reason = 3, // REASON_LOW_MEMORY
        description = "isolated not needed",
        timestampMs = 1_000_000_000_000L,
        nowMs = 1_000_000_000_000L + 42 * 60_000L,
        pssKb = 262144,
        rssKb = 393216,
        importance = 400, // IMPORTANCE_CACHED
        processName = "hashengineering.darkcoin.wallet",
        pid = 12345,
        status = 0,
        definingUid = 10123
    )

    @Test
    fun formatRecord_containsEveryFieldWithUnitsAndNames() {
        val line = lmkRecord()
        assertTrue(line, line.startsWith("#0 "))
        assertTrue(line, line.contains("reason=LOW_MEMORY_LMK(3)"))
        assertTrue(line, line.contains("(42m ago)"))
        assertTrue(line, line.contains("importance=CACHED(400)"))
        assertTrue(line, line.contains("pss=262144KB/256.0MB"))
        assertTrue(line, line.contains("rss=393216KB/384.0MB"))
        assertTrue(line, line.contains("process=hashengineering.darkcoin.wallet"))
        assertTrue(line, line.contains("pid=12345"))
        assertTrue(line, line.contains("status=0"))
        assertTrue(line, line.contains("definingUid=10123"))
        assertTrue(line, line.contains("description=\"isolated not needed\""))
        // ISO-ish local timestamp, e.g. at=2001-09-09T03:46:40
        assertTrue(line, Regex("at=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}").containsMatchIn(line))
    }

    @Test
    fun formatRecord_toleratesMissingProcessNameAndDescription() {
        val line = ProcessExitReasons.formatRecord(
            index = 3,
            reason = 6, // REASON_ANR
            description = null,
            timestampMs = 1_000_000_000_000L,
            nowMs = 1_000_000_000_000L + 90_000L,
            pssKb = 0,
            rssKb = 0,
            importance = 100,
            processName = null,
            pid = 0,
            status = 0,
            definingUid = 0
        )
        assertTrue(line, line.startsWith("#3 "))
        assertTrue(line, line.contains("reason=ANR(6)"))
        assertTrue(line, line.contains("importance=FOREGROUND(100)"))
        assertTrue(line, line.contains("process=?"))
        assertTrue(line, line.contains("description=none"))
    }

    @Test
    fun formatRecord_printsUnknownReasonAndImportanceRaw() {
        val line = ProcessExitReasons.formatRecord(
            index = 1,
            reason = 42,
            description = "from the future",
            timestampMs = 1_000_000_000_000L,
            nowMs = 1_000_000_000_000L,
            pssKb = 2048,
            rssKb = 4096,
            importance = 275,
            processName = "p",
            pid = 7,
            status = 9,
            definingUid = 11
        )
        assertTrue(line, line.contains("reason=REASON_42(42)"))
        assertTrue(line, line.contains("importance=IMPORTANCE_275(275)"))
    }

    // ── summaryLine ───────────────────────────────────────────────────

    @Test
    fun summaryLine_isGreppableAndCarriesTheVerdict() {
        val summary = ProcessExitReasons.summaryLine(
            reason = 3,
            description = "isolated not needed",
            timestampMs = 1_000_000_000_000L,
            nowMs = 1_000_000_000_000L + 19 * 60_000L,
            pssKb = 262144,
            rssKb = 393216,
            importance = 400
        )
        assertTrue(summary, summary.startsWith("LOW_MEMORY_LMK(3)"))
        assertTrue(summary, summary.contains("19m ago"))
        assertTrue(summary, summary.contains("importance=CACHED(400)"))
        assertTrue(summary, summary.contains("pss=262144KB/256.0MB"))
        assertTrue(summary, summary.contains("rss=393216KB/384.0MB"))
        assertTrue(summary, summary.contains("description=\"isolated not needed\""))
        // one line only — it is grepped out of a multi-megabyte log
        assertEquals(1, summary.lines().size)
    }

    @Test
    fun summaryPrefix_isDistinct() {
        assertEquals("PROCESS EXIT REASON:", ProcessExitReasons.SUMMARY_PREFIX)
    }
}
