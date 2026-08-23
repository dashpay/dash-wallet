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

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delay BEFORE bind retry attempt number `retriesAttempted + 1` — the
 * capped ladder 5s / 15s / 30s / 60s, then hourly. The first steps are
 * tight because the field failure class (a keystore that FALSELY reports
 * the device locked) often clears within seconds; the hourly tail keeps a
 * genuinely broken keystore from burning battery forever while the
 * device-unlock receiver still heals it instantly. Pure — host-testable.
 */
internal fun bindRetryDelayMs(retriesAttempted: Int): Long = when (retriesAttempted) {
    0 -> 5_000L
    1 -> 15_000L
    2 -> 30_000L
    3 -> 60_000L
    else -> 60 * 60_000L
}

/**
 * MO-995: re-arms the single-shot [SdkWalletBinder] after a failed bind
 * pass — the app-side fix for the fresh-wallet sync outage where the SDK's
 * `createWallet` died in the Android keystore
 * (`UserNotAuthenticatedException` from a `setUnlockedDeviceRequired` key:
 * Keystore2 thought the device was locked, sometimes falsely), the
 * fresh-wallet cutover commit held dashj, and NOTHING ever retried the
 * bind — leaving the wallet with no sync engine at all and the Network
 * Monitor showing a dead "Not started".
 *
 * Three cooperating mechanisms:
 *
 * 1. **Backoff-capped re-invocation** ([maybeRetry]) — driven by
 *    [CutoverUiDataService]'s existing 5 s bound-wallet wait loop, which
 *    runs exactly while the cutover is committed but no SDK wallet is
 *    bound (the stranded state). The loop calls this every poll; the
 *    ladder ([bindRetryDelayMs]) decides which polls actually re-run the
 *    bind pass. [noteAppForeground] resets the ladder so a user returning
 *    to the app is never stuck behind the hourly tail.
 * 2. **Device-unlock heal** — a runtime-registered
 *    [Intent.ACTION_USER_PRESENT] receiver (RECEIVER_NOT_EXPORTED) fires
 *    an immediate retry on the next unlock: the exact heal condition for
 *    the keystore false-locked class. Armed once, on the first retry
 *    consultation after a failure; retries once the wallet is bound are
 *    cheap no-ops.
 * 3. **Engine fallback** — after [rollbackAfterFailures] consecutive
 *    failed passes ([SdkWalletBinder.consecutiveBindFailures]) the
 *    committed cutover is rolled back
 *    ([CutoverCoordinator.rollbackForFailedBind]) so
 *    `dashjEngineMayStart` is true again and the user syncs on the dashj
 *    fallback engine. Skipped while the device is PROVABLY locked
 *    ([KeyguardManager.isDeviceLocked]) — a genuinely-locked keystore
 *    denial is expected, heals on unlock, and must not flip engines.
 *
 * The invariant all three protect: the gate always ends with dashj
 * allowed OR the SDK wallet bound — never both held.
 *
 * Never throws into a caller; every entry point contains its own failures.
 * The SDK-side hardening (a typed keystore error + internal retry in
 * `createWallet`) is a deliberately separate follow-up.
 */
@Singleton
class SdkBindRetryService internal constructor(
    private val scope: CoroutineScope,
    /** [SdkWalletBinder.bindRetryPending]'s current value. */
    private val bindRetryPending: () -> Boolean,
    /** [SdkWalletBinder.consecutiveBindFailures]. */
    private val consecutiveBindFailures: () -> Int,
    /** One full bind pass — [SdkWalletBinder.bindIfEnabled], which never throws. */
    private val runBindPass: suspend () -> Unit,
    /** [CutoverCoordinator.rollbackForFailedBind]. */
    private val rollbackCutover: suspend (Int) -> Unit,
    /**
     * Register the unlock receiver; the callback fires on every
     * ACTION_USER_PRESENT. Returns whether registration succeeded (a
     * failure re-arms on the next consultation).
     */
    private val registerUnlockReceiver: (onUserPresent: () -> Unit) -> Boolean,
    /**
     * Whether the device is PROVABLY locked right now. True suppresses the
     * engine rollback (see class KDoc); the false-locked keystore class
     * reads false here, which is exactly when the rollback must fire.
     */
    private val deviceProvablyLocked: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: (Int) -> Long = ::bindRetryDelayMs,
    private val rollbackAfterFailures: Int = ROLLBACK_AFTER_CONSECUTIVE_FAILURES
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        binder: SdkWalletBinder,
        nonInteractiveWalletUnlock: NonInteractiveWalletUnlock,
        cutoverCoordinator: CutoverCoordinator,
        scope: CoroutineScope
    ) : this(
        scope = scope,
        bindRetryPending = { binder.bindRetryPending.value },
        consecutiveBindFailures = { binder.consecutiveBindFailures },
        // The same non-interactive unlock recipe every background binding
        // trigger uses (PlatformSyncService.kickSdkEngines) — never a prompt.
        runBindPass = { binder.bindIfEnabled(nonInteractiveWalletUnlock::unlockOrNull) },
        rollbackCutover = { failures -> cutoverCoordinator.rollbackForFailedBind(failures) },
        registerUnlockReceiver = { onUserPresent ->
            registerUserPresentReceiver(context, onUserPresent)
        }, // (top-level helper — a companion reference is not legal in constructor delegation)
        deviceProvablyLocked = {
            try {
                context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
            } catch (t: Throwable) {
                false // unknowable reads as unlocked — the rollback stays available
            }
        }
    )

    /** Retries THIS service has attempted since the last success/foreground reset — the ladder index. */
    @Volatile
    private var retriesAttempted = 0

    /** Wall-clock ms before which [maybeRetry] stays a no-op. */
    @Volatile
    private var nextRetryAtMs = 0L

    /** One receiver registration per process. */
    private val unlockReceiverArmed = AtomicBoolean(false)

    /** Single-flight: the binder's own mutex serializes passes, but don't queue on it. */
    private val retryInFlight = AtomicBoolean(false)

    /**
     * The ladder-driven consultation — call freely (the bound-wallet wait
     * loop calls it every 5 s poll); it no-ops unless a failed bind is
     * pending AND the backoff window has elapsed. Never throws.
     */
    suspend fun maybeRetry(trigger: String) {
        try {
            if (!bindRetryPending()) return
            armUnlockReceiver()
            if (now() < nextRetryAtMs) return
            retryOnce(trigger)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK bind retry consultation failed; will retry on the next trigger", t)
        }
    }

    /**
     * Immediate retry, bypassing the backoff window — the device-unlock
     * heal path. Fire-and-forget on the injected scope; never throws.
     */
    fun retryNowInBackground(trigger: String) {
        scope.launch {
            try {
                if (!bindRetryPending()) return@launch
                // The unlock is the heal condition for the false-locked
                // keystore class — restart the ladder so follow-up retries
                // (if this one still fails) come quickly again.
                resetBackoff()
                retryOnce(trigger)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("immediate SDK bind retry ({}) failed; ladder retries continue", trigger, t)
            }
        }
    }

    /**
     * App came to the foreground: reset the ladder so the next wait-loop
     * poll retries within seconds instead of the hourly tail. State-only —
     * the actual retry rides the existing triggers.
     */
    fun noteAppForeground() {
        if (!bindRetryPending()) return
        log.info("app foregrounded with an SDK bind retry pending — resetting the retry backoff")
        resetBackoff()
    }

    private fun resetBackoff() {
        retriesAttempted = 0
        nextRetryAtMs = 0L
    }

    /** One retry attempt + the post-attempt rollback consultation. */
    private suspend fun retryOnce(trigger: String) {
        if (!retryInFlight.compareAndSet(false, true)) return
        try {
            // Schedule the next window BEFORE the attempt so a slow/hung
            // pass cannot be stacked by the next poll.
            nextRetryAtMs = now() + retryDelayMs(retriesAttempted)
            retriesAttempted++
            log.info(
                "SDK bind retry {} ({}): re-running the wallet bind pass " +
                    "({} consecutive failure(s) so far)",
                retriesAttempted, trigger, consecutiveBindFailures()
            )
            runBindPass()
            if (!bindRetryPending()) {
                log.info("SDK bind retry {} ({}) succeeded — the wallet is bound", retriesAttempted, trigger)
                resetBackoff()
                return
            }
            maybeRollBackCutover()
        } finally {
            retryInFlight.set(false)
        }
    }

    /**
     * After [rollbackAfterFailures] consecutive failed passes, roll the
     * committed cutover back so dashj may run — unless the device is
     * provably locked (the denial is then EXPECTED and heals on unlock;
     * flipping engines for it would punish every locked-screen background
     * start). The coordinator no-ops from any non-CUT_OVER state, so
     * repeated consultations are harmless.
     */
    private suspend fun maybeRollBackCutover() {
        val failures = consecutiveBindFailures()
        if (failures < rollbackAfterFailures) return
        if (deviceProvablyLocked()) {
            log.info(
                "SDK bind has failed {} consecutive passes but the device is provably locked — " +
                    "holding the cutover rollback; the unlock receiver retries the bind first",
                failures
            )
            return
        }
        log.warn(
            "SDK bind failed {} consecutive passes with the device unlocked — rolling the " +
                "cutover back so the dashj fallback engine can sync",
            failures
        )
        rollbackCutover(failures)
    }

    /** Arm the ACTION_USER_PRESENT heal receiver (once per process). */
    private fun armUnlockReceiver() {
        if (!unlockReceiverArmed.compareAndSet(false, true)) return
        val registered = try {
            registerUnlockReceiver {
                log.info("device unlocked (ACTION_USER_PRESENT) — running an immediate SDK bind retry")
                retryNowInBackground("device unlock")
            }
        } catch (t: Throwable) {
            log.warn("failed to register the unlock-heal receiver", t)
            false
        }
        if (!registered) unlockReceiverArmed.set(false)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkBindRetryService::class.java)

        /**
         * Consecutive failed bind passes before the cutover rolls back to
         * dashj. With the 5/15/30/60 s ladder this is roughly two minutes
         * of retrying — long enough for a transient keystore hiccup to
         * clear, short enough that the user is never staring at a dead
         * "Not started" for a whole session.
         */
        internal const val ROLLBACK_AFTER_CONSECUTIVE_FAILURES = 5

    }
}

/**
 * The real ACTION_USER_PRESENT registration. NOT_EXPORTED: the unlock
 * broadcast is a protected system broadcast — no app-facing surface is
 * exposed. The receiver stays registered for the process lifetime; once
 * the wallet is bound its retries are cheap no-ops. Top-level (not a
 * companion member) so the @Inject constructor's delegation expression may
 * reference it.
 */
private fun registerUserPresentReceiver(context: Context, onUserPresent: () -> Unit): Boolean =
    try {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) onUserPresent()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        LoggerFactory.getLogger(SdkBindRetryService::class.java)
            .info("unlock-heal receiver registered (ACTION_USER_PRESENT, not exported)")
        true
    } catch (t: Throwable) {
        LoggerFactory.getLogger(SdkBindRetryService::class.java)
            .warn("could not register the ACTION_USER_PRESENT receiver", t)
        false
    }
