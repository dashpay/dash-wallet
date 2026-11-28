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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounding, de-duplication and redaction rules that decide what native
 * (Rust/SDK) logcat output is copied into `wallet.log` — i.e. what a remote
 * tester's support upload actually carries. The `logcat` invocation itself is
 * not exercised here; everything that decides CONTENT and VOLUME is.
 */
class NativeLogBridgeTest {

    private fun line(
        level: Char = 'I',
        tag: String = NativeLogBridge.NATIVE_TAG,
        message: String,
        ms: String = "000"
    ) = "08-05 12:34:56.$ms  4242  4300 $level $tag: $message"

    private val batchLine = line(
        message = "wallet-event batch: folded=12 wallets=1 synced_height_persisted=Some(1234) " +
            "faulted=0 missed=0"
    )
    private val frozenLine = line(
        level = 'E',
        message = "SYNC WATERMARK FROZEN: persister rejected a changeset for wallet abcd"
    )

    // ------------------------------------------------------------ dedupe

    @Test
    fun firstDrain_forwardsEverything_withNoGap() {
        val lines = listOf(batchLine, frozenLine)
        val (fresh, gap) = NativeLogBridge.newLinesAfter(lines, null)
        assertEquals(lines, fresh)
        assertFalse(gap)
    }

    @Test
    fun secondDrain_forwardsOnlyLinesAfterTheMarker() {
        val a = line(message = "a", ms = "001")
        val b = line(message = "b", ms = "002")
        val c = line(message = "c", ms = "003")
        val (fresh, gap) = NativeLogBridge.newLinesAfter(listOf(a, b, c), b)
        assertEquals(listOf(c), fresh)
        assertFalse(gap)
    }

    @Test
    fun unchangedBuffer_forwardsNothing() {
        val a = line(message = "a", ms = "001")
        val b = line(message = "b", ms = "002")
        val (fresh, _) = NativeLogBridge.newLinesAfter(listOf(a, b), b)
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun rolledBuffer_forwardsEverythingAndFlagsTheGap() {
        val (fresh, gap) = NativeLogBridge.newLinesAfter(
            listOf(line(message = "later", ms = "900")),
            line(message = "evicted", ms = "001")
        )
        assertEquals(1, fresh.size)
        assertTrue(gap)
    }

    // ------------------------------------------------------------- level

    @Test
    fun levelIsParsedFromThreadtime() {
        assertEquals('I', NativeLogBridge.levelOf(batchLine))
        assertEquals('E', NativeLogBridge.levelOf(frozenLine))
        assertNull(NativeLogBridge.levelOf("not a logcat line at all"))
    }

    @Test
    fun warnErrorAndUnparsedCountAsHighPriority_infoDoesNot() {
        assertTrue(NativeLogBridge.isHighPriority(frozenLine))
        assertTrue(NativeLogBridge.isHighPriority(line(level = 'W', message = "careful")))
        // an unrecognised format is kept rather than silently dropped
        assertTrue(NativeLogBridge.isHighPriority("some other format"))
        assertFalse(NativeLogBridge.isHighPriority(batchLine))
    }

    // --------------------------------------------------------- redaction

    @Test
    fun sensitiveLinesAreWithheld() {
        listOf(
            "mnemonic = abandon abandon about",
            "restored from SEED PHRASE ok",
            "private key loaded",
            "xprv9s21ZrQH143K3",
            "password accepted"
        ).forEach {
            assertTrue(it, NativeLogBridge.isSensitive(line(message = it)))
        }
    }

    @Test
    fun ordinaryDiagnosticLinesAreNotWithheld() {
        assertFalse(NativeLogBridge.isSensitive(batchLine))
        assertFalse(NativeLogBridge.isSensitive(frozenLine))
        // public identifiers must survive — they are the point of the report
        assertFalse(NativeLogBridge.isSensitive(line(message = "txid=9f2c… address=yjSvwyLB5X")))
    }

    @Test
    fun budgetDropsASensitiveLineEntirely() {
        val budget = NativeLogBridge.Budget()
        assertNull(budget.admit(line(message = "wallet mnemonic = abandon abandon")))
        assertNotNull(budget.admit(batchLine))
    }

    // ---------------------------------------------------------- bounding

    @Test
    fun longLinesAreTruncated() {
        val long = line(message = "x".repeat(5_000))
        val truncated = NativeLogBridge.truncate(long)
        assertTrue(truncated.length < long.length)
        assertTrue(truncated.startsWith(long.take(NativeLogBridge.MAX_LINE_CHARS)))
        assertTrue(truncated.endsWith("[truncated]"))
        // short lines are untouched
        assertEquals(batchLine, NativeLogBridge.truncate(batchLine))
    }

    @Test
    fun budgetStopsInfoLinesOnceTheLineCapIsHit() {
        val budget = NativeLogBridge.Budget()
        var admitted = 0
        repeat(NativeLogBridge.MAX_FORWARDED_LINES + 50) { i ->
            if (budget.admit(line(message = "batch $i")) != null) admitted++
        }
        assertEquals(NativeLogBridge.MAX_FORWARDED_LINES, admitted)
    }

    @Test
    fun budgetStopsOnTheCharacterCapEvenBelowTheLineCap() {
        val budget = NativeLogBridge.Budget()
        var admitted = 0
        // ~500 chars per line: the 1 MB cap bites long before 10k lines
        val fat = "y".repeat(400)
        repeat(NativeLogBridge.MAX_FORWARDED_LINES) { i ->
            if (budget.admit(line(message = "$i $fat")) != null) admitted++
        }
        assertTrue(admitted in 1 until NativeLogBridge.MAX_FORWARDED_LINES)
    }

    @Test
    fun errorsStillPassAfterTheBudgetIsSpent_upToTheReserve() {
        val budget = NativeLogBridge.Budget()
        repeat(NativeLogBridge.MAX_FORWARDED_LINES) { budget.admit(line(message = "filler $it")) }
        // info is now dropped...
        assertNull(budget.admit(batchLine))
        // ...but the one-shot freeze error — the line worth reporting — is not
        assertNotNull(budget.admit(frozenLine))

        var reserved = 1
        repeat(NativeLogBridge.ERROR_RESERVE_LINES + 10) {
            if (budget.admit(line(level = 'E', message = "err $it")) != null) reserved++
        }
        assertEquals(NativeLogBridge.ERROR_RESERVE_LINES, reserved)
    }

    @Test
    fun exhaustionIsAnnouncedExactlyOnce() {
        val budget = NativeLogBridge.Budget()
        assertFalse(budget.consumeExhaustionNotice())
        repeat(NativeLogBridge.MAX_FORWARDED_LINES) { budget.admit(line(message = "filler $it")) }
        assertFalse(budget.consumeExhaustionNotice())
        budget.admit(batchLine) // first rejection flips the notice
        assertTrue(budget.consumeExhaustionNotice())
        assertFalse(budget.consumeExhaustionNotice())
    }

    // ------------------------------------------------------- no feedback

    @Test
    fun theBridgeNeverReadsBackItsOwnReEmittedOutput() {
        val budget = NativeLogBridge.Budget()
        val ownOutput = line(
            tag = NativeLogBridge.EMITTER_LOGGER_NAME,
            message = "08-05 12:34:56.000  4242  4300 I DashSDK: wallet-event batch"
        )
        assertNull(budget.admit(ownOutput))
    }

    @Test
    fun emitterTagDiffersFromTheCapturedTag() {
        // The whole no-feedback guarantee rests on these two being different.
        assertFalse(NativeLogBridge.EMITTER_LOGGER_NAME == NativeLogBridge.NATIVE_TAG)
    }

    @Test
    fun blankLinesAreDropped() {
        val budget = NativeLogBridge.Budget()
        assertNull(budget.admit(""))
        assertNull(budget.admit("   "))
    }
}
