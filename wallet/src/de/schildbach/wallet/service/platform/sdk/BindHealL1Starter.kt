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
package de.schildbach.wallet.service.platform.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Starts the SDK L1 engine when the wallet bind heals INSIDE a service
 * lifetime.
 *
 * `PlatformSynchronizationService.resume()` starts the engine once, after the
 * startup bind pass. When that pass fails — the device locked at upgrade time
 * is the field case (MO-995) — `startIfEnabled()` declines because no wallet
 * is bound, and the unlock receiver and the retry ladder then run only the
 * bind. A successful retry clears the blocker and resets the backoff but
 * nothing starts L1: the blockchain service calls `resume()` on creation,
 * not on every `onStartCommand`, so opening the app did not repair the
 * running instance either. The upgrade replay marker stayed set (IDLE
 * snapshots preserve it) and the cleared blocker re-enabled the replay
 * idle-stop guard for an engine that was not running (review, 2026-09-23).
 *
 * [arm] waits for the binder's `bindEstablished` to go true and then starts
 * L1 once through the same idempotent entry point the startup path uses.
 * [cancel] is called from the service's shutdown, so a heal that lands after
 * teardown starts nothing; [serviceTearingDown] covers the window in which
 * the shutdown has begun but not yet reached the cancel.
 *
 * Kept out of `PlatformSynchronizationService` so the ordering can be pinned
 * by a host test with plain flows.
 */
internal class BindHealL1Starter(
    private val scope: CoroutineScope,
    private val bindEstablished: Flow<Boolean>,
    private val serviceTearingDown: () -> Boolean,
    private val startL1: suspend () -> Boolean
) {
    private val log = LoggerFactory.getLogger(BindHealL1Starter::class.java)

    private var job: Job? = null

    /** Whether a heal is currently being waited for. */
    val isArmed: Boolean get() = job?.isActive == true

    /** Arm for this service lifetime; a later arm replaces an earlier one. */
    fun arm() {
        job?.cancel()
        job = scope.launch {
            bindEstablished.first { it }
            if (serviceTearingDown()) {
                log.info(
                    "SDK bind healed while the blockchain service is tearing down — not starting L1; " +
                        "the next service start kicks it"
                )
                return@launch
            }
            val started = try {
                startL1()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("SDK L1 engine start after the in-session bind heal failed", t)
                false
            }
            log.info(
                "SDK bind healed in-session — L1 engine start {}",
                if (started) "succeeded" else "declined"
            )
        }
    }

    /** Stop waiting. Called from the service's shutdown and engine stop. */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
