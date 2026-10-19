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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [SdkWriteResult.NotBroadcast] reason a producer reports when the fence
 * refused it before anything was spent.
 */
const val REASON_WALLET_WIPE_IN_PROGRESS = "wallet wipe in progress"

/** A wallet-secret write (or a funding call that leads to one) refused because Reset Wallet is running. */
class WalletWipeInProgressException(what: String) :
    IllegalStateException("wallet wipe in progress — $what refused")

/**
 * Keeps the OLD wallet's secrets out of the SDK's `WalletStorage` once
 * Reset Wallet has started destroying it.
 *
 * The wipe erases (or verifies) the SDK secrets store near its end. That
 * only proves the store's contents AT THAT MOMENT: a username creation, an
 * invite claim or a key heal still running for the old wallet can persist
 * private-key ciphertext (and owner-index entries) right after the erase —
 * and the SDK's own tombstone is not guaranteed to exist when its removal
 * cascade failed. This fence closes that window:
 *
 * - **Permits.** Every app-side secret write — and every funding call that
 *   makes the SDK persist keys as part of it — runs under
 *   [withSecretWritePermit] / [tryAcquire]. Once [raise]d, new permits are
 *   refused with [WalletWipeInProgressException] (or `false`).
 * - **Producers.** The long-running app-scoped jobs that can reach such a
 *   write register with [track]; [raiseAndQuiesce] cancels and joins them.
 * - **Quiesce.** [raiseAndQuiesce] = raise, cancel+join every tracked
 *   producer, then wait for the permits already held to drain — bounded.
 *   Only a `true` answer lets the wipe trust its erase.
 *
 * Lifecycle: raised at the start of the wipe's destroy phase (and before a
 * wipe resumed at launch starts), lowered only when a wipe COMPLETED with its
 * marker confirmed cleared. A failed wipe leaves it raised for the rest of
 * the process (which stays degraded and creates no replacement wallet); a
 * fresh process starts lowered — a pending marker re-raises it from
 * `Application.onCreate` before anything could write.
 */
@Singleton
class WalletSecretWriteFence @Inject constructor() {
    private val lock = Any()

    @Volatile
    private var raised = false
    private var permits = 0
    private val permitCount = MutableStateFlow(0)
    private val producers = LinkedHashSet<Job>()

    /**
     * Set by the wipe once it has brought the SDK runtime to rest
     * ([DashSdkService.quiesceForWalletWipe]): from then on no SDK bootstrap
     * may run ([DashSdkService.ensureStarted] refuses), so nothing can
     * re-open a wallet manager whose persistence deriver writes keys into
     * `WalletStorage` outside the permits. Cleared only by [lower].
     */
    @Volatile
    private var sdkRuntimeHalted = false

    /** Whether Reset Wallet holds the fence. */
    val isRaised: Boolean get() = raised

    /** Whether SDK runtime (re)starts are refused — see [haltSdkRuntime]. */
    val isSdkRuntimeHalted: Boolean get() = sdkRuntimeHalted

    /**
     * Refuse every later SDK runtime bootstrap until [lower]. Idempotent.
     * Raises the fence too: a halted runtime without fenced permits is not a
     * state the wipe ever wants.
     */
    fun haltSdkRuntime() {
        synchronized(lock) {
            raised = true
            sdkRuntimeHalted = true
        }
        log.warn("SDK runtime starts halted: Reset Wallet is erasing the SDK secrets")
    }

    /** Refuse every later permit. Idempotent. */
    fun raise() {
        synchronized(lock) { raised = true }
        log.warn("wallet secret writes fenced: Reset Wallet is destroying the wallet")
    }

    /** Re-open for the next wallet. Only for a wipe that completed. */
    fun lower() {
        synchronized(lock) {
            raised = false
            sdkRuntimeHalted = false
        }
        log.info("wallet secret writes (and SDK runtime starts) re-opened: Reset Wallet completed")
    }

    /** Throws [WalletWipeInProgressException] while raised. A point check — prefer a permit around the write itself. */
    fun checkOpen(what: String) {
        if (raised) throw WalletWipeInProgressException(what)
    }

    /**
     * Take a permit for [what] unless the fence is raised. Every `true` must
     * be paired with exactly one [release].
     */
    fun tryAcquire(what: String): Boolean {
        synchronized(lock) {
            if (raised) {
                log.warn("wallet wipe in progress — {} refused", what)
                return false
            }
            permits++
            permitCount.value = permits
        }
        return true
    }

    /** Give back a permit taken by [tryAcquire]. */
    fun release() {
        synchronized(lock) {
            check(permits > 0) { "WalletSecretWriteFence permit released without being acquired" }
            permits--
            permitCount.value = permits
        }
    }

    /**
     * Run [block] under a permit. Throws [WalletWipeInProgressException]
     * without running it when the fence is raised. A wipe that raises the
     * fence while [block] runs waits for it (see [raiseAndQuiesce]).
     */
    inline fun <T> withSecretWritePermit(what: String, block: () -> T): T {
        if (!tryAcquire(what)) throw WalletWipeInProgressException(what)
        try {
            return block()
        } finally {
            release()
        }
    }

    /**
     * Run [block] under a permit, or return [refused]'s value without running
     * it when the fence is raised — for producers that report a refusal as a
     * result rather than an exception (both lambdas are inlined, so they may
     * `return` from the caller).
     */
    inline fun <T> ifOpen(what: String, refused: () -> T, block: () -> T): T {
        if (!tryAcquire(what)) return refused()
        try {
            return block()
        } finally {
            release()
        }
    }

    /**
     * Register an app-scoped producer [job] (one that can reach a secret
     * write) so the wipe can cancel and join it. Removed again on
     * completion. A job registered while the fence is raised is cancelled at
     * once — start it LAZY and [track] it before `start()` so it can never
     * run unfenced.
     */
    fun track(job: Job) {
        val cancelNow = synchronized(lock) {
            if (raised) {
                true
            } else {
                producers += job
                false
            }
        }
        if (cancelNow) {
            log.warn("wallet wipe in progress — cancelling a wallet-secret producer started under the fence")
            job.cancel(CancellationException("wallet wipe in progress"))
            return
        }
        job.invokeOnCompletion { synchronized(lock) { producers -= job } }
    }

    /**
     * [raise], cancel and join every tracked producer, then wait until no
     * permit is held — within [timeoutMs] in total. True when the fence
     * provably holds: nothing running can write an old-wallet secret any
     * more. False on timeout (a producer ignoring cancellation, or a native
     * call holding a permit past the budget) — the caller must then treat
     * the SDK secrets as NOT erased.
     */
    suspend fun raiseAndQuiesce(timeoutMs: Long): Boolean {
        raise()
        val jobs = synchronized(lock) { producers.toList() }
        if (jobs.isNotEmpty()) {
            log.warn("wallet wipe: cancelling {} wallet-secret producer(s) and waiting for them", jobs.size)
        }
        jobs.forEach { it.cancel(CancellationException("wallet wipe in progress")) }
        val quiesced = withTimeoutOrNull(timeoutMs) {
            jobs.forEach { it.join() }
            permitCount.first { it == 0 }
            true
        } ?: false
        if (quiesced) {
            log.info("wallet secret producers quiesced: no old-wallet secret write can follow the erase")
        } else {
            val stragglers = synchronized(lock) { producers.count { it.isActive } to permits }
            log.error(
                "wallet secret producers did NOT quiesce within {} ms ({} producer(s) still active, {} " +
                    "write permit(s) held) — the SDK secrets cannot be confirmed erased",
                timeoutMs,
                stragglers.first,
                stragglers.second
            )
        }
        return quiesced
    }

    companion object {
        private val log = LoggerFactory.getLogger(WalletSecretWriteFence::class.java)

        /**
         * The wipe's budget for [raiseAndQuiesce]. Generous on purpose: a
         * shielded proof in flight runs ~30 s, and the cost of overrunning is
         * a wipe that stays pending and re-runs at the next launch.
         */
        const val WIPE_QUIESCE_TIMEOUT_MS = 60_000L
    }
}

/**
 * The SDK-secrets part of Reset Wallet's destroy phase, in its required
 * order:
 *
 * 1. fence the old wallet's APP-side secret producers ([WalletSecretWriteFence.raiseAndQuiesce]);
 * 2. the [teardown] (wipe listeners, SDK wallet-removal cascade, database
 *    clears — every step of which must still run);
 * 3. [quiesceSdk] — bring the SDK's OWN work to rest: the persistence
 *    deriver of a live wallet manager writes identity keys straight into
 *    `WalletStorage` during changeset commits / sync discovery, outside the
 *    app's permits — also for a wallet a half-failed removal already dropped
 *    from the loaded map. A running runtime is therefore always stopped
 *    (wallets tombstoned, manager closed and drained) and cannot restart
 *    until the wipe completes ([DashSdkService.quiesceForWalletWipe]);
 * 4. only then the [erase] (list / verify / delete the secrets store).
 *
 * When either quiesce step could not be established the erase still runs —
 * it removes what is there now — but its outcome is reported as
 * [SdkSecretsWipeOutcome.FAILED], so the wipe keeps its marker and the next
 * launch (with nothing in flight) erases again.
 */
internal suspend fun fencedSdkSecretsWipe(
    fence: WalletSecretWriteFence,
    quiesceTimeoutMs: Long,
    teardown: suspend () -> Unit,
    quiesceSdk: suspend () -> Boolean,
    erase: suspend () -> SdkSecretsWipeOutcome
): SdkSecretsWipeOutcome {
    val quiesced = try {
        fence.raiseAndQuiesce(quiesceTimeoutMs)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        fencedWipeLog.error("could not fence wallet-secret producers", t)
        false
    }
    teardown()
    val sdkQuiesced = try {
        quiesceSdk()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        fencedWipeLog.error("could not bring the SDK runtime to rest", t)
        false
    }
    val outcome = erase()
    if (outcome.erased && (!quiesced || !sdkQuiesced)) {
        fencedWipeLog.error(
            "SDK secrets erase reported {}, but {} — treating the secrets as NOT erased; the wipe stays pending",
            outcome,
            if (!quiesced) {
                "old-wallet secret producers were not fenced"
            } else {
                "the SDK runtime could not be proven quiescent (its persistence deriver may still write)"
            }
        )
        return SdkSecretsWipeOutcome.FAILED
    }
    return outcome
}

private val fencedWipeLog = LoggerFactory.getLogger("de.schildbach.wallet.service.platform.sdk.FencedSdkSecretsWipe")
