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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *    [Intent.ACTION_USER_PRESENT] receiver (RECEIVER_EXPORTED; see
 *    `registerUserPresentReceiver` for why the flag matters) fires an
 *    immediate retry on the next unlock. Armed on the FIRST failure, not on
 *    a later poll.
 *
 *    It has two known limits, both observed. The receiver lives in the
 *    process, so a background process with no foreground service cannot run
 *    it: the 2026-09-16 upgrade test logged `ActivityManager: freezing <pid>`
 *    30 s after the package-replaced broadcast, then "Sending oneway calls to
 *    frozen process" while `USER_PRESENT` went out. Starting the blockchain
 *    service on that path (see `WalletApplication
 *    .startBlockchainServiceAfterUpgrade`) keeps the process unfrozen and
 *    closes that hole. And some OEMs simply do not deliver the broadcast:
 *    walletB's HONOR PTP-N49 delivered zero in ten hours.
 *
 * Because of those limits the ongoing notification is the guaranteed path:
 * the user opens the app, [noteAppForeground] runs, and the bind completes —
 * 1.6 s after the app was opened on that same test.
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
    /** [SdkWalletBinder.bindEstablished]: true once a pass in THIS process bound the wallet. */
    private val bindEstablished: Flow<Boolean> = emptyFlow(),
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
        bindEstablished = binder.bindEstablished,
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

    /**
     * Serializes [onBindFailureChanged] against [onBindEstablished].
     *
     * The two run on SEPARATE collectors, and both suspend part-way through
     * (persistBlocker) while holding a decision made before the suspension.
     * Re-reading `_blocker` after the suspension is not enough on its own: the
     * failure handler can pass that check and then be overtaken, so its
     * showPendingNotice lands AFTER onBindEstablished's clearPendingNotice and
     * the "unlock your device" notification survives on a bound wallet, with
     * nothing left to dismiss it until a next failure a healed bind never
     * produces. Holding one lock across each handler's whole body makes the
     * post-or-clear decision and the act of posting indivisible.
     */
    private val outcomeMutex = Mutex()

    /**
     * The most recent value the failure feed delivered, recorded by the
     * collector BEFORE it waits on [outcomeMutex]. The identity check in
     * [classifyAndAnnounce] compares against this under the lock.
     *
     * Why the mutex alone is not enough (CodeRabbit on #1568): the feed is a
     * StateFlow, so the collector only ever sees the latest failure — but a
     * failure A can be parked waiting for the lock while success B and then
     * failure C are delivered. When A finally runs it is stale twice over,
     * yet nothing about A itself says so; the timestamp dedup below only
     * catches a RE-emission of the same failure. Recording the newest
     * emission here, sequentially, gives the lock holder a current value to
     * compare against: A is not it, so A is dropped instead of advancing the
     * streaks and publishing an outdated blocker ahead of C.
     */
    @Volatile
    private var latestBindFailure: SdkBindFailure? = null

    init {
        scope.launch {
            try {
                bindFailures.collect { onBindFailureChanged(it) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("SDK bind failure feed died; blocker classification stops", t)
            }
        }
        scope.launch {
            try {
                bindEstablished.collect { established -> if (established) onBindEstablished() }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("SDK bind success feed died; the durable blocker may go stale", t)
            }
        }
    }

    /**
     * A pass in THIS process left the wallet bound: drop the in-memory blocker
     * AND overwrite the durable record with NONE.
     *
     * Kept separate from the `failure == null` arm of [onBindFailureChanged] on
     * purpose. [SdkWalletBinder.lastBindFailure] is null both when the wallet is
     * bound and before the first pass of a fresh process, so that arm cannot
     * safely write NONE — doing so would erase a real blocker before this
     * process has attempted anything. It therefore keys off the in-memory
     * [_blocker], which a new process starts at null, so a blocker persisted by
     * an EARLIER process was never cleared on success.
     *
     * Caught on emulator-5554 (2026-09-16): the app was force-stopped, the bind
     * then succeeded in the new process and the notification cleared, yet
     * `sdk_bind_blocker` still read OTHER in the support report.
     */
    private suspend fun onBindEstablished() = outcomeMutex.withLock {
        // Stale-success guard (review, 2026-09-22). Success and failure ride
        // separate StateFlows collected by separate coroutines, so a success
        // already parked on this mutex can run AFTER a newer failure has
        // classified its blocker — and would null it, clear the notice and
        // persist NONE while the binder still says a retry is pending, with
        // no further emission to put the UI back. The binder's live
        // `bindRetryPending` tells the two apart: `noteBindOutcome` lowers it
        // before it raises `bindEstablished` and raises it before it publishes
        // a failure, so a true here means a newer failure owns the state and
        // this success is history.
        if (bindRetryPending()) {
            log.info(
                "SDK bind established signal superseded by a newer failure — leaving its blocker ({}) in place",
                _blocker.value
            )
            return@withLock
        }
        val previous = _blocker.value
        unlockedDenialStreak = 0
        otherFailureStreak = 0
        _blocker.value = null
        log.info(
            "SDK bind established — clearing the pending state ({})",
            previous ?: "no blocker recorded in this process"
        )
        clearPendingNotice()
        runCatching { persistBlocker(null) }
            .onFailure { if (it is CancellationException) throw it; log.warn("failed to persist the cleared bind blocker", it) }
    }

    private suspend fun onBindFailureChanged(failure: SdkBindFailure?) {
        // Success is NOT handled here. `lastBindFailure` goes null both when the
        // wallet is bound and before the first pass of a fresh process, so this
        // feed cannot tell them apart. [onBindEstablished] owns the whole
        // success path, keyed off a signal that only a bound pass raises.
        // Recorded BEFORE the lock, null included: this collector is
        // sequential, so by the time a queued call acquires the mutex this
        // already holds whatever the feed delivered after it — a newer
        // failure, or the null a success leaves behind.
        latestBindFailure = failure
        if (failure == null) return
        outcomeMutex.withLock { classifyAndAnnounce(failure) }
    }

    /** The body of [onBindFailureChanged], under [outcomeMutex]. */
    private suspend fun classifyAndAnnounce(failure: SdkBindFailure) {
        // IDENTITY FIRST, under the lock: is this still the feed's latest
        // value? A failure that waited on the mutex while a success (null) or
        // a newer failure was delivered is stale, and classifying it would
        // advance the streaks and publish an outdated blocker ahead of the
        // current one. See [latestBindFailure].
        if (failure !== latestBindFailure) {
            log.info(
                "SDK bind failure from {} superseded while waiting for classification; dropping it",
                failure.atMs
            )
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
        // Re-read _blocker AFTER persistBlocker's suspension. onBindEstablished
        // can run in the gap: it sets _blocker to null and calls
        // clearPendingNotice(), and this would then resume and repost "unlock
        // your device" for a wallet that is already bound — a notification with
        // nothing left to dismiss it until the next failure, which on a healed
        // bind never comes. The blocker this call classified has to still be the
        // one in force.
        if (_blocker.value == blocker && appInBackground()) showPendingNotice(blocker)
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
 * The real ACTION_USER_PRESENT registration.
 *
 * RECEIVER_EXPORTED, and that is load-bearing. This was RECEIVER_NOT_EXPORTED,
 * reasoning that a protected system broadcast needs no app-facing surface. The
 * reasoning inverted the consequence: `NOT_EXPORTED` matches only broadcasts
 * whose sender shares our uid (or is the platform), and `ACTION_USER_PRESENT`
 * is broadcast by **SystemUI**, a normal app uid — so the filter was never
 * matched and the receiver never ran.
 *
 * Measured on the 2026-09-16 locked-upgrade test (emulator-5554, Android 16).
 * The wallet's filter was registered and visible in `dumpsys activity
 * broadcasts`:
 *
 *     ReceiverList{… hashengineering.darkcoin.wallet_test/10169/u0}
 *       Filter #0: BroadcastFilter{59c4fef}
 *         Action: "android.intent.action.USER_PRESENT"
 *
 * …and the broadcast that followed a real device unlock reached four
 * receivers, none of them ours:
 *
 *     caller=com.android.systemui 1547:com.android.systemui/u0a124 uid=10124
 *     DELIVERED #0 system/1000/u0   DELIVERED #1 system/1000/u-1
 *     DELIVERED #2 com.android.launcher3/10116/u0   SKIPPED #3 (manifest)
 *
 * The launcher receives it because it registers exported. The same dump shows
 * the wallet receiving TIME_TICK, which the system server sends from uid 1000,
 * the one sender `NOT_EXPORTED` does admit — which is why the registration
 * looked healthy while being inert for the broadcast it exists for.
 *
 * Exporting is safe precisely because the action is protected: it is declared
 * `<protected-broadcast>` by the platform, so a third-party app that tries to
 * send it gets a SecurityException. Exported here means "accept it from the
 * privileged component that legitimately sends it", not "accept it from
 * anyone". The receiver re-checks the action anyway.
 *
 * The receiver stays registered for the process lifetime; once the wallet is
 * bound its retries are cheap no-ops. Top-level (not a companion member) so
 * the @Inject constructor's delegation expression may reference it.
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
            ContextCompat.RECEIVER_EXPORTED
        )
        LoggerFactory.getLogger(SdkBindRetryService::class.java)
            .info("unlock-heal receiver registered (ACTION_USER_PRESENT, exported — SystemUI is the sender)")
        true
    } catch (t: Throwable) {
        LoggerFactory.getLogger(SdkBindRetryService::class.java)
            .warn("could not register the ACTION_USER_PRESENT receiver", t)
        false
    }
