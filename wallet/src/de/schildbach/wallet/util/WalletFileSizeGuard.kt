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

import org.slf4j.LoggerFactory
import java.io.File

/**
 * PRE-PARSE size guard for the dashj wallet protobuf — the fix for the
 * mainnet "un-openable crash loop" where a very large CoinJoin wallet file
 * OOM-killed `WalletProtobufSerializer.readWallet` inside
 * `Application.onCreate` on every launch.
 *
 * ## The measured limits (local reproduction with the app's exact load call)
 *
 * - **Heap multiplier ≈ 8×**: parsing a wallet-protobuf peaks at roughly
 *   EIGHT times the file size in Java heap (the transient `Protos.Wallet`
 *   message tree PLUS the bitcoinj object graph both live simultaneously
 *   mid-parse). A 250MB file OOMs a 512MB heap; near-limit files that don't
 *   outright OOM drive the heap into GC livelock instead (observed at 1GB).
 * - **2GB is a protobuf-level hard wall**: `CodedInputStream` cannot read a
 *   top-level message past `Integer.MAX_VALUE` bytes — a ≥2GB file fails
 *   "Protocol message was too large" at ANY heap size (verified up to a
 *   26GB desktop heap). The WRITE side streams field-by-field and sails
 *   past 2GB without error, so a growing wallet crosses the point of no
 *   return SILENTLY. Such a file is unparseable BY CONSTRUCTION; the only
 *   recovery is the transaction-stripped key backup
 *   (`key-backup-protobuf`).
 *
 * ## The thresholds
 *
 * - **HARD ([HARD_LIMIT_BYTES], 2,000,000,000)**: at/above this the parse
 *   is not attempted at all (it cannot succeed and the attempt itself OOMs
 *   first on any real device). Kept fractionally below the true
 *   `Integer.MAX_VALUE` wall for margin.
 * - **SOFT ([softLimitBytes])**: `min(largeHeap_bytes / 10, 100MB)`.
 *   Derivation: the parse needs ≈8× the file size, and the guard demands
 *   ~20% heap headroom for the rest of the app (8 × 1.25 = 10, hence
 *   heap/10). The absolute 100MB cap reflects the GC-livelock observation —
 *   even when a big-heap device could theoretically fit a larger parse, a
 *   file this size means ~100k+ transactions and multi-second launch
 *   parses; above the cap the parse is ATTEMPTED but wrapped so an
 *   OOM/Error routes to the deliberate key-backup recovery instead of
 *   crash-looping.
 */
object WalletFileSizeGuard {
    /** Files at/above this cannot parse at any heap size (protobuf 2GiB wall, with margin). */
    const val HARD_LIMIT_BYTES = 2_000_000_000L

    /** Measured peak heap-to-file multiplier of the wallet protobuf parse. */
    const val MEASURED_PARSE_HEAP_MULTIPLIER = 8

    /** Absolute soft-limit ceiling — see the class KDoc (GC livelock + parse-time rationale). */
    const val SOFT_LIMIT_CAP_BYTES = 100L * 1024 * 1024

    private val log = LoggerFactory.getLogger(WalletFileSizeGuard::class.java)

    enum class Verdict {
        /** Parse normally. */
        NORMAL,

        /**
         * Attempt the parse, but wrap it: an OOM/Error is EXPECTED to be the
         * file's fault at this size and routes to the deliberate key-backup
         * recovery (preserve file aside, restore) instead of crashing.
         */
        RISKY,

        /**
         * Do not even attempt the parse — it fails by construction. Preserve
         * the file aside and go straight to the key-backup recovery.
         */
        UNPARSEABLE
    }

    /**
     * The RISKY threshold for a device whose `ActivityManager.getLargeMemoryClass()`
     * is [largeMemoryClassMb] (the app manifest sets `largeHeap="true"`, so
     * that IS the heap limit): `min(heap/10, 100MB)` — see the class KDoc
     * for the 8×-multiplier + headroom derivation.
     */
    @JvmStatic
    fun softLimitBytes(largeMemoryClassMb: Int): Long {
        val heapBytes = largeMemoryClassMb.coerceAtLeast(1).toLong() * 1024L * 1024L
        return minOf(heapBytes / 10L, SOFT_LIMIT_CAP_BYTES)
    }

    @JvmStatic
    fun verdict(fileSizeBytes: Long, largeMemoryClassMb: Int): Verdict = when {
        fileSizeBytes >= HARD_LIMIT_BYTES -> Verdict.UNPARSEABLE
        fileSizeBytes >= softLimitBytes(largeMemoryClassMb) -> Verdict.RISKY
        else -> Verdict.NORMAL
    }

    /**
     * Preserve a wallet file ASIDE — forensics and safety, NEVER delete: the
     * user's transaction history is in there even when it cannot be parsed,
     * and support/offline tooling may still be able to salvage it. Renames
     * `<name>` to `<name>.<reason>.<timestamp>` (with a `-N` suffix on
     * collision), so the recovered wallet's autosave writes a FRESH small
     * file at the original path instead of ballooning on top of the old one,
     * and the next launch cannot re-trip the same failure.
     *
     * @return the preserved file, or null when the rename failed (the
     *         original is left untouched in that case — never deleted).
     */
    @JvmStatic
    @JvmOverloads
    fun preserveAside(file: File, reason: String, nowMs: Long = System.currentTimeMillis()): File? {
        if (!file.exists()) return null
        var target = File(file.parentFile, "${file.name}.$reason.$nowMs")
        var suffix = 1
        while (target.exists()) {
            target = File(file.parentFile, "${file.name}.$reason.$nowMs-${suffix++}")
        }
        return try {
            if (file.renameTo(target)) {
                log.warn("wallet file preserved aside: '{}' -> '{}' ({} bytes)", file.name, target.name, target.length())
                target
            } else {
                log.error("failed to preserve wallet file aside: '{}' (leaving it in place)", file.name)
                null
            }
        } catch (t: Throwable) {
            log.error("failed to preserve wallet file aside: '{}'", file.name, t)
            null
        }
    }
}
