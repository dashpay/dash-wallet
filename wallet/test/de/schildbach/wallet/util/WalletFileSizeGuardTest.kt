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

import de.schildbach.wallet.util.WalletFileSizeGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class WalletFileSizeGuardTest {

    private val mb = 1024L * 1024L

    // ── softLimitBytes: min(heap/10, 100MB) ───────────────────────────

    @Test
    fun softLimit_isTenthOfHeap_onSmallHeapDevices() {
        // 256MB largeHeap -> 25.6MB soft limit (8x parse multiplier + 25% headroom = /10)
        assertEquals(256 * mb / 10, WalletFileSizeGuard.softLimitBytes(256))
        // 512MB largeHeap -> 51.2MB. The measured repro: a 250MB file OOMs a
        // 512MB heap — this limit flags it RISKY with 5x margin.
        assertEquals(512 * mb / 10, WalletFileSizeGuard.softLimitBytes(512))
    }

    @Test
    fun softLimit_isCappedAt100MB_onBigHeapDevices() {
        // 1GB and 2GB heaps both cap at 100MB — near-limit parses GC-livelock
        // (measured at 1GB) even when they might technically fit.
        assertEquals(100 * mb, WalletFileSizeGuard.softLimitBytes(1024))
        assertEquals(100 * mb, WalletFileSizeGuard.softLimitBytes(2048))
        // The crossover point: heap/10 == 100MB at exactly 1000MB.
        assertEquals(100 * mb, WalletFileSizeGuard.softLimitBytes(1000))
        assertEquals(999 * mb / 10, WalletFileSizeGuard.softLimitBytes(999))
    }

    @Test
    fun softLimit_survivesDegenerateHeapValues() {
        // A zero/negative largeMemoryClass (broken ROM) must not divide to 0
        // in a way that makes every file RISKY-at-size-0... it does make the
        // limit tiny, which is the CONSERVATIVE direction (RISKY still parses).
        assertTrue(WalletFileSizeGuard.softLimitBytes(0) > 0)
        assertTrue(WalletFileSizeGuard.softLimitBytes(-5) > 0)
    }

    // ── verdict table ─────────────────────────────────────────────────

    @Test
    fun verdict_normal_belowSoftLimit() {
        assertEquals(Verdict.NORMAL, WalletFileSizeGuard.verdict(0, 512))
        assertEquals(Verdict.NORMAL, WalletFileSizeGuard.verdict(10 * mb, 512))
        // one byte under the soft limit
        assertEquals(Verdict.NORMAL, WalletFileSizeGuard.verdict(512 * mb / 10 - 1, 512))
    }

    @Test
    fun verdict_risky_atAndAboveSoftLimit_belowHardLimit() {
        assertEquals(Verdict.RISKY, WalletFileSizeGuard.verdict(512 * mb / 10, 512))
        // The measured OOM case: 250MB file, 512MB heap -> RISKY (wrapped parse).
        assertEquals(Verdict.RISKY, WalletFileSizeGuard.verdict(250 * mb, 512))
        // Big-heap device, above the 100MB cap -> RISKY.
        assertEquals(Verdict.RISKY, WalletFileSizeGuard.verdict(150 * mb, 2048))
        // one byte under the hard limit is still (only) RISKY
        assertEquals(Verdict.RISKY, WalletFileSizeGuard.verdict(WalletFileSizeGuard.HARD_LIMIT_BYTES - 1, 512))
    }

    @Test
    fun verdict_unparseable_atAndAboveHardLimit_regardlessOfHeap() {
        // >=2GB fails "Protocol message was too large" at ANY heap (verified
        // to 26GB) — heap size must not matter.
        assertEquals(Verdict.UNPARSEABLE, WalletFileSizeGuard.verdict(WalletFileSizeGuard.HARD_LIMIT_BYTES, 512))
        assertEquals(Verdict.UNPARSEABLE, WalletFileSizeGuard.verdict(WalletFileSizeGuard.HARD_LIMIT_BYTES, Int.MAX_VALUE))
        // Joel's file: ~3.00GB.
        assertEquals(Verdict.UNPARSEABLE, WalletFileSizeGuard.verdict(3_000_000_000L, 512))
    }

    // ── preserveAside: forensics + safety, never delete ───────────────

    private fun freshDir(): File = Files.createTempDirectory("size-guard-test").toFile()

    @Test
    fun preserveAside_renames_keepsContent_originalGone() {
        val dir = freshDir()
        try {
            val file = File(dir, "wallet-protobuf")
            file.writeText("precious transaction history")

            val preserved = WalletFileSizeGuard.preserveAside(file, "oversize", nowMs = 1700000000000L)

            assertNotNull(preserved)
            assertEquals("wallet-protobuf.oversize.1700000000000", preserved!!.name)
            assertTrue(preserved.exists())
            assertEquals("precious transaction history", preserved.readText())
            assertFalse("the original path must be free for the recovered wallet", file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun preserveAside_neverOverwrites_onNameCollision() {
        val dir = freshDir()
        try {
            val existing = File(dir, "wallet-protobuf.oversize.42")
            existing.writeText("earlier preserved copy")
            val file = File(dir, "wallet-protobuf")
            file.writeText("new copy")

            val preserved = WalletFileSizeGuard.preserveAside(file, "oversize", nowMs = 42L)

            assertNotNull(preserved)
            assertEquals("wallet-protobuf.oversize.42-1", preserved!!.name)
            assertEquals("earlier preserved copy", existing.readText())
            assertEquals("new copy", preserved.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun preserveAside_missingFile_isNoOp() {
        val dir = freshDir()
        try {
            assertNull(WalletFileSizeGuard.preserveAside(File(dir, "nope"), "oversize"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun preserveAside_worksOnSparseOversizeFile() {
        // A crafted oversize file via seek/truncate — no need to write GBs:
        // RandomAccessFile.setLength creates a sparse 2.5GB file instantly.
        val dir = freshDir()
        try {
            val file = File(dir, "wallet-protobuf")
            RandomAccessFile(file, "rw").use { it.setLength(2_500_000_000L) }
            assertEquals(Verdict.UNPARSEABLE, WalletFileSizeGuard.verdict(file.length(), 512))

            val preserved = WalletFileSizeGuard.preserveAside(file, "oversize")
            assertNotNull(preserved)
            assertEquals(2_500_000_000L, preserved!!.length())
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
