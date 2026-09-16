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
import de.schildbach.wallet.AppForegroundMonitor
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
 * Two cooperating mechanisms:
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
 *
 * There is deliberately NO engine fallback. A third mechanism used to roll
 * the committed cutover back to dashj after five consecutive failures with
 * the device unlocked. That fallback is what put two SPV engines in one
 * process on the reference install (docs/upgrade-memory-and-sync-plan.md
 * §12), and under the no-fallback policy the dashj peergroup starts only
 * for the Tools › dashj sync diagnostic. A bind that keeps failing keeps
 * retrying — on the ladder, on unlock, on foreground.
 *
 * 3. **"SDK setup pending" state** (Phase 1a item 3) — instead of a
 *    fallback, every failed pass ([SdkWalletBinder.lastBindFailure]) is
 *    CLASSIFIED ([classifyBindFailure]) into an [SdkBindBlocker] and
 *    published on [blocker]. The unlock receiver is armed the moment the
 *    first failure lands (not on a later poll), the classification is
 *    persisted for the support report ([DashPayConfig.SDK_BIND_BLOCKER]),
 *    a notification asks the user to unlock the phone when the app is in
 *    the background, and the home screen shows a sheet while the app is
 *    open. The reference install (2026-09-14) sat in this state for 22
 *    hours with nothing telling the user; walletB's HONOR never healed at
 *    all because its keystore denied while unlocked.
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
    /**
     * Register the unlock receiver; the callback fires on every
     * ACTION_USER_PRESENT. Returns whether registration succeeded (a
     * failure re-arms on the next consultation).
     */
    private val registerUnlockReceiver: (onUserPresent: () -> Unit) -> Boolean,
    /**
     * Whether the device is PROVABLY locked right now ([KeyguardManager
     * .isDeviceLocked]). Used to classify a failed pass: a denial while
     * locked is expected and heals on unlock; a denial while unlocked is the
     * false-locked keystore class.
     */
    private val deviceProvablyLocked: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: (Int) -> Long = ::bindRetryDelayMs,
    /** [SdkWalletBinder.lastBindFailure]: null once bound, else the latest failed pass. */
    private val bindFailures: Flow<SdkBindFailure?> = emptyFlow(),
    /** Durable record of the current blocker for the support report. */
    private val persistBlocker: suspend (SdkBindBlocker?) -> Unit = {},
    /** Post / clear the "unlock your phone" notification (background only). */
    private val showPendingNotice: (SdkBindBlocker) -> Unit = {},
    private val clearPendingNotice: () -> Unit = {},
    private val appInBackground: () -> Boolean = { false }
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        binder: SdkWalletBinder,
        nonInteractiveWalletUnlock: NonInteractiveWalletUnlock,
        cutoverCoordinator: CutoverCoordinator,
        dashPayConfig: DashPayConfig,
        scope: CoroutineScope
    ) : this(
        scope = scope,
        bindRetryPending = { binder.bindRetryPending.value },
        consecutiveBindFailures = { binder.consecutiveBindFailures },
        // The same non-interactive unlock recipe every background binding
        // trigger uses (PlatformSyncService.kickSdkEngines) — never a prompt.
        runBindPass = { binder.bindIfEnabled(nonInteractiveWalletUnlock::unlockOrNull) },
        registerUnlockReceiver = { onUserPresent ->
            registerUserPresentReceiver(context, onUserPresent)
        }, // (top-level helper — a companion reference is not legal in constructor delegation)
        deviceProvablyLocked = {
            try {
                context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
            } catch (t: Throwable) {
                false // unknowable reads as unlocked
            }
        },
        bindFailures = binder.lastBindFailure,
        persistBlocker = { blocker ->
            dashPayConfig.set(DashPayConfig.SDK_BIND_BLOCKER, blocker?.name ?: "NONE")
        },
        showPendingNotice = { blocker -> SdkBindPendingNotification.show(context, blocker) },
        clearPendingNotice = { SdkBindPendingNotification.clear(context) },
        appInBackground = { AppForegroundMonitor.isInBackground }
    )

    /**
     * Why the bind is pending right now; null while the wallet is bound (or
     * before the first pass). The home screen renders a sheet from this and
     * the support report records it.
     */
    private val _blocker = MutableStateFlow<SdkBindBlocker?>(null)
    val blocker: StateFlow<SdkBindBlocker?> = _blocker.asStateFlow()

    /** Consecutive keystore denials seen with the device reporting UNLOCKED. */
    @Volatile
    private var unlockedDenialStreak = 0

    /** Consecutive non-keystore failures. */
    @Volatile
    private var otherFailureStreak = 0

    @Volatile
    private var lastClassifiedFailureAtMs = Long.MIN_VALUE

    init {
        scope.launch {
            try {
                bindFailures.collect { onBindFailureChanged(it) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("SDK bind failure feed died; blocker classification stops", t)
            }
        }
    }

    private suspend fun onBindFailureChanged(failure: SdkBindFailure?) {
        if (failure == null) {
            val previous = _blocker.value ?: return
            unlockedDenialStreak = 0
            otherFailureStreak = 0
            _blocker.value = null
            log.info("SDK bind established — clearing the pending state ({})", previous)
            clearPendingNotice()
            runCatching { persistBlocker(null) }
                .onFailure { if (it is CancellationException) throw it; log.warn("failed to persist the cleared bind blocker", it) }
            return
        }
        // StateFlow conflates; a re-emission of the same failure is not a new one.
        if (failure.atMs == lastClassifiedFailureAtMs) return
        lastClassifiedFailureAtMs = failure.atMs

        val locked = deviceProvablyLocked()
        if (isKeystoreDenial(failure.cause)) {
            otherFailureStreak = 0
            val lockedByEvidence = locked || keystoreDenialReportsDeviceLocked(failure.cause) == true
            if (lockedByEvidence) unlockedDenialStreak = 0 else unlockedDenialStreak++
        } else {
            unlockedDenialStreak = 0
            otherFailureStreak++
        }
        val blocker = classifyBindFailure(
            failure.cause,
            deviceProvablyLocked = locked,
            unlockedDenialStreak = unlockedDenialStreak,
            otherFailureStreak = otherFailureStreak
        )
        val changed = _blocker.value != blocker
        _blocker.value = blocker
        log.warn(
            "SDK bind pending: {} ({} consecutive failure(s); device provably locked={}; " +
                "unlocked keystore denials in a row={}; other failures in a row={}; cause={})",
            blocker, failure.consecutiveFailures, locked, unlockedDenialStreak, otherFailureStreak,
            failure.cause.toString().take(200)
        )
        // Arm the unlock heal NOW — not on some later poll that may never come.
        armUnlockReceiver()
        if (changed) {
            runCatching { persistBlocker(blocker) }
                .onFailure { if (it is CancellationException) throw it; log.warn("failed to persist the bind blocker", it) }
        }
        if (appInBackground()) showPendingNotice(blocker)
    }

    /**
     * The app left the foreground with the bind still pending: tell the user
     * what it is waiting for, since nothing on screen can.
     */
    fun noteAppBackground() {
        val blocker = _blocker.value ?: return
        showPendingNotice(blocker)
    }

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
     * App came to the foreground with a bind retry pending: DRIVE a retry, and
     * arm the unlock receiver while we are here.
     *
     * MO-995 — this used to be state-only ("reset the ladder so the next
     * wait-loop poll retries"), which relied on a trigger that does not exist
     * in the state that needs it most. The only caller of [maybeRetry] is
     * `CutoverUiDataService.awaitBoundWallet()`, and that loop runs only while
     * the cutover holds dashj with no bound wallet. Once the coordinator
     * correctly REFUSES to commit onto an unbindable SDK, that state never
     * occurs — so the loop never runs, [maybeRetry] is never called, the
     * unlock receiver is never armed, and nothing ever retries. Reproduced on
     * the emulator (S3): after a Keystore denial, unlocking the device and
     * returning to the app produced ZERO binder activity; only a full app
     * restart healed it.
     *
     * App-foreground is the right trigger precisely because it depends on
     * neither the cutover state nor a broadcast: the user is looking at the
     * app, so the device is provably unlocked — which is the heal condition
     * for the device-locked keystore denial — and no receiver has to survive
     * an OEM's background restrictions. walletB's HONOR PTP-N49 delivered
     * `ACTION_USER_PRESENT` zero times in ten hours; MagicOS suppresses
     * exactly that kind of broadcast.
     *
     * [retryNowInBackground] resets the ladder and runs one pass.
     */
    fun noteAppForeground() {
        // The user is looking at the app: the notification is redundant and
        // the sheet takes over.
        if (_blocker.value != null) clearPendingNotice()
        if (!bindRetryPending()) return
        log.info("app foregrounded with an SDK bind retry pending — retrying the bind now")
        armUnlockReceiver()
        retryNowInBackground("app foreground")
    }

    private fun resetBackoff() {
        retriesAttempted = 0
        nextRetryAtMs = 0L
    }

    /** One retry attempt. */
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
            log.warn(
                "SDK bind retry {} ({}) failed — {} consecutive failure(s); device provably " +
                    "locked={}. No dashj fallback: retrying on the ladder, on unlock and on " +
                    "app foreground",
                retriesAttempted, trigger, consecutiveBindFailures(), deviceProvablyLocked()
            )
        } finally {
            retryInFlight.set(false)
        }
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
