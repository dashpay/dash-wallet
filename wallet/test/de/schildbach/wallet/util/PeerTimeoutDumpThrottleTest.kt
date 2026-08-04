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

import ch.qos.logback.core.spi.FilterReply
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rate limit on dashj's per-peer-timeout thread dumps (96 dumps = 118k of
 * 190k log lines in the mainnet tester's session). The contract: dump lines
 * on the PeerSocketHandler logger are limited to one full dump per interval;
 * every other line — on that logger and every other — is untouched.
 */
class PeerTimeoutDumpThrottleTest {

    private var nowMs = 0L
    private fun filter(intervalMs: Long = PeerTimeoutDumpThrottle.DEFAULT_INTERVAL_MS) =
        PeerTimeoutDumpThrottle(intervalMs) { nowMs }

    private val header = PeerTimeoutDumpThrottle.DUMP_HEADER_FORMAT
    private val frame = PeerTimeoutDumpThrottle.DUMP_FRAME_FORMAT
    private val dumpLogger = PeerTimeoutDumpThrottle.PEER_SOCKET_HANDLER_LOGGER

    @Test
    fun firstDump_passesInFull() {
        val f = filter()
        assertEquals(FilterReply.NEUTRAL, f.decide(dumpLogger, header))
        repeat(40) { assertEquals(FilterReply.NEUTRAL, f.decide(dumpLogger, frame)) }
        // multiple threads in the same dump: more headers within the window pass
        assertEquals(FilterReply.NEUTRAL, f.decide(dumpLogger, frame))
    }

    @Test
    fun theFloodShape_96DumpsIn27Minutes_collapsesToTheIntervalRate() {
        val f = filter(intervalMs = 5 * 60_000L)
        var allowedDumps = 0
        // one dump every ~17s for 27 minutes — the tester's session shape
        for (t in 0 until 27 * 60_000L step 17_000L) {
            nowMs = t
            if (f.decide(dumpLogger, header) == FilterReply.NEUTRAL) {
                allowedDumps++
                // its frames pass too
                assertEquals(FilterReply.NEUTRAL, f.decide(dumpLogger, frame))
            } else {
                // a denied dump's frames are denied with it (outside the window)
                nowMs = t + PeerTimeoutDumpThrottle.DUMP_WINDOW_MS + 1
                assertEquals(FilterReply.DENY, f.decide(dumpLogger, frame))
            }
        }
        assertEquals("27 min at one dump per 5 min", 6, allowedDumps)
    }

    @Test
    fun deniedDumpFrames_areDenied() {
        val f = filter(intervalMs = 60_000L)
        assertEquals(FilterReply.NEUTRAL, f.decide(dumpLogger, header)) // dump 1 allowed
        nowMs = PeerTimeoutDumpThrottle.DUMP_WINDOW_MS + 1
        assertEquals(FilterReply.DENY, f.decide(dumpLogger, header)) // dump 2 denied
        assertEquals(FilterReply.DENY, f.decide(dumpLogger, frame))
    }

    @Test
    fun afterTheInterval_theNextDumpPassesAgain() {
        val f = filter(intervalMs = 60_000L)
        assertEquals(FilterReply.NEUTRAL, f.decide(dumpLogger, header))
        nowMs = 59_999
        assertEquals(FilterReply.DENY, f.decide(dumpLogger, header))
        nowMs = 60_000
        assertEquals(FilterReply.NEUTRAL, f.decide(dumpLogger, header))
    }

    @Test
    fun everythingElse_isNeverTouched() {
        val f = filter(intervalMs = 60_000L)
        // exhaust the allowance so any over-filtering would show as DENY
        f.decide(dumpLogger, header)
        nowMs = PeerTimeoutDumpThrottle.DUMP_WINDOW_MS + 1

        // the lines that must be kept, verbatim from dashj 22.0.4
        assertEquals(FilterReply.NEUTRAL, f.decide(dumpLogger, "{}: Timed out"))
        assertEquals(
            FilterReply.NEUTRAL,
            f.decide(dumpLogger, "TIMEOUT CAUSE: General peer timeout - no response received within timeout period")
        )
        assertEquals(
            FilterReply.NEUTRAL,
            f.decide(dumpLogger, "CRITICAL: Detected SPVBlockStore timeout - native I/O freeze detected in thread: {}")
        )
        // other loggers are never filtered, even with the dump format strings
        assertEquals(FilterReply.NEUTRAL, f.decide("org.bitcoinj.core.PeerGroup", header))
        assertEquals(FilterReply.NEUTRAL, f.decide("de.schildbach.wallet.util.AnrException", frame))
        // null-safety
        assertEquals(FilterReply.NEUTRAL, f.decide(null, header))
        assertEquals(FilterReply.NEUTRAL, f.decide(dumpLogger, null))
    }
}
