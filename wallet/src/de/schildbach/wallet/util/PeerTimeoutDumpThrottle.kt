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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/**
 * Rate-limits dashj's PEER-TIMEOUT THREAD DUMPS in the app's logging pipeline.
 *
 * dashj 22.0.4's `PeerSocketHandler.timeoutOccurred()` calls
 * `checkForBlockStoreTimeout()`, which — on EVERY peer timeout — WARN-logs a
 * full stack dump of every "PeerGroup Thread" / "NioClientManager" thread
 * (a `Stack trace for thread '{}' (State: {}):` header plus one `  at {}`
 * line per frame). On the mainnet tester's flapping session that produced 96
 * full dumps: 118k of 190k log lines, starving file I/O exactly while
 * autosaves and the UI were already struggling.
 *
 * The dump frequency is not configurable in dashj and the dump shares its
 * logger with lines that must be kept, so a plain level change is too blunt.
 * This [TurboFilter] (evaluated before any appender work) surgically denies
 * ONLY the dump format strings on ONLY that logger, and still lets one full
 * dump through per [intervalMs] so occasional forensic stacks survive.
 *
 * Everything else is untouched (NEUTRAL): the per-timeout "Timed out" INFO,
 * both "TIMEOUT CAUSE" lines, the "CRITICAL: Detected SPVBlockStore timeout"
 * ERROR, and — importantly — the programmatic detection itself:
 * `checkForBlockStoreTimeout` scans the stacks in code and feeds
 * `TimeoutErrorListener` regardless of what gets logged, so app-level
 * recovery (BLOCKSTORE_MEMORY_ACCESS restart) is unaffected.
 */
class PeerTimeoutDumpThrottle @JvmOverloads constructor(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) : TurboFilter() {

    companion object {
        /** The dashj logger the dumps ride on — nothing else is filtered. */
        const val PEER_SOCKET_HANDLER_LOGGER = "org.bitcoinj.core.PeerSocketHandler"

        /** The dump's per-thread header format string, verbatim from dashj 22.0.4. */
        const val DUMP_HEADER_FORMAT = "Stack trace for thread '{}' (State: {}):"

        /** The dump's per-frame format string, verbatim from dashj 22.0.4. */
        const val DUMP_FRAME_FORMAT = "  at {}"

        /** At most one full dump per 5 minutes. */
        const val DEFAULT_INTERVAL_MS = 5 * 60_000L

        /** How long after an allowed header its frame lines keep flowing. */
        const val DUMP_WINDOW_MS = 2_000L
    }

    @Volatile
    private var nextAllowedAtMs = 0L // first dump always passes

    @Volatile
    private var windowEndsAtMs = 0L

    override fun decide(
        markers: List<Marker>?,
        logger: Logger?,
        level: Level?,
        format: String?,
        params: Array<out Any>?,
        t: Throwable?
    ): FilterReply = decide(logger?.name, format)

    /** The whole decision, on plain values — unit-tested directly. */
    internal fun decide(loggerName: String?, format: String?): FilterReply {
        if (loggerName != PEER_SOCKET_HANDLER_LOGGER || format == null) return FilterReply.NEUTRAL
        return when (format) {
            DUMP_HEADER_FORMAT -> {
                val now = clock()
                if (now >= nextAllowedAtMs) {
                    nextAllowedAtMs = now + intervalMs
                    windowEndsAtMs = now + DUMP_WINDOW_MS
                    FilterReply.NEUTRAL
                } else {
                    FilterReply.DENY
                }
            }
            DUMP_FRAME_FORMAT ->
                if (clock() <= windowEndsAtMs) FilterReply.NEUTRAL else FilterReply.DENY
            else -> FilterReply.NEUTRAL
        }
    }
}
