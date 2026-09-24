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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the MO-995 bind retry machinery: the capped backoff
 * ladder, the re-arming semantics ([SdkBindRetryService.maybeRetry] /
 * [SdkBindRetryService.retryNowInBackground]), the device-unlock heal
 * receiver arming — and the no-fallback invariant over a REAL binder +
 * REAL coordinator: persistent bind failures keep retrying and never hand
 * L1 back to dashj (docs/upgrade-memory-and-sync-plan.md §12).
 */
class SdkBindRetryServiceTest {

    // ── The ladder ────────────────────────────────────────────────────

    @Test
    fun ladder_is5_15_30_60ThenHourly() {
        assertEquals(5_000L, bindRetryDelayMs(0))
        assertEquals(15_000L, bindRetryDelayMs(1))
        assertEquals(30_000L, bindRetryDelayMs(2))
        assertEquals(60_000L, bindRetryDelayMs(3))
        assertEquals(3_600_000L, bindRetryDelayMs(4))
        assertEquals(3_600_000L, bindRetryDelayMs(99))
    }

    // ── Harness ───────────────────────────────────────────────────────

    /**
     * A scriptable failure signal standing in for the binder: [pending] /
     * [failures] mirror [SdkWalletBinder.bindRetryPending] and
     * [SdkWalletBinder.consecutiveBindFailures]; each [bindPass] either
     * "fails" (increments both) or "succeeds" (clears both), exactly the
     * binder's noteBindOutcome contract.
     */
    private class FakeBinderSignal(var passSucceeds: Boolean = false) {
        var pending = false
        var failures = 0
        var passes = 0

        // A pre-existing failure (the initial PlatformSyncService-triggered
        // pass) is what arms the retry machinery in the first place.
        fun primeFailed(initialFailures: Int = 1) {
            pending = true
            failures = initialFailures
        }

        suspend fun bindPass() {
            passes++
            if (passSucceeds) {
                pending = false
                failures = 0
            } else {
                failures++
                pending = true
            }
        }
    }

    private class Harness(
        val signal: FakeBinderSignal = FakeBinderSignal(),
        var deviceLocked: Boolean = false,
        var registerSucceeds: Boolean = true
    ) {
        var nowMs = 0L
        var registrations = 0
        var unlockCallback: (() -> Unit)? = null

        // The "SDK setup pending" surface.
        val failures = kotlinx.coroutines.flow.MutableStateFlow<SdkBindFailure?>(null)
        val established = kotlinx.coroutines.flow.MutableStateFlow(false)
        var appInBackground = false
        val notices = mutableListOf<SdkBindBlocker>()
        var noticeClears = 0
        val persisted = mutableListOf<SdkBindBlocker?>()

        fun service(scope: kotlinx.coroutines.CoroutineScope) = SdkBindRetryService(
            scope = scope,
            bindRetryPending = { signal.pending },
            consecutiveBindFailures = { signal.failures },
            runBindPass = { signal.bindPass() },
            registerUnlockReceiver = { onUserPresent ->
                if (registerSucceeds) {
                    registrations++
                    unlockCallback = onUserPresent
                }
                registerSucceeds
            },
            deviceProvablyLocked = { deviceLocked },
            now = { nowMs },
            bindFailures = failures,
            bindEstablished = established,
            persistBlocker = { persisted += it },
            showPendingNotice = { notices += it },
            clearPendingNotice = { noticeClears++ },
            appInBackground = { appInBackground }
        )

        /** Publish one failed pass the way the binder does. */
        fun fail(cause: Throwable) {
            signal.primeFailed(signal.failures + 1)
            established.value = false
            failures.value = SdkBindFailure(cause, signal.failures, atMs = ++nowMs)
        }

        /** Both signals, exactly as SdkWalletBinder.noteBindOutcome raises them. */
        fun succeed() {
            signal.pending = false
            signal.failures = 0
            failures.value = null
            established.value = true
        }
    }

    private fun lockedDenial() = org.dashfoundation.dashsdk.security.KeystoreDeviceLockedException(
        "org.dashfoundation.wallet.master", "createWallet",
        org.dashfoundation.dashsdk.security.DeviceLockState(true, true), RuntimeException("denied")
    )

    private fun unlockedDenial() = org.dashfoundation.dashsdk.security.KeystoreDeviceLockedException(
        "org.dashfoundation.wallet.master", "createWallet",
        org.dashfoundation.dashsdk.security.DeviceLockState(false, false), RuntimeException("denied")
    )

    // ── maybeRetry: gating + ladder ───────────────────────────────────

    @Test
    fun maybeRetry_isANoOpWhileNoFailureIsPending() = runTest {
        val h = Harness()
        val service = h.service(backgroundScope)
        service.maybeRetry("poll")
        assertEquals(0, h.signal.passes)
        assertEquals(0, h.registrations) // receiver only arms once a failure exists
    }

    @Test
    fun maybeRetry_retriesImmediatelyOnTheFirstConsult_thenHonorsTheLadder() = runTest {
        val h = Harness()
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        // First consult: window 0 → retry runs (and fails again).
        service.maybeRetry("poll")
        assertEquals(1, h.signal.passes)

        // Same 5 s poll cadence, but inside the 5 s window → no attempt.
        h.nowMs += 4_999
        service.maybeRetry("poll")
        assertEquals(1, h.signal.passes)

        // Window elapsed → second retry.
        h.nowMs += 2
        service.maybeRetry("poll")
        assertEquals(2, h.signal.passes)

        // The second retry armed the 15 s rung.
        h.nowMs += 5_001
        service.maybeRetry("poll")
        assertEquals(2, h.signal.passes)
        h.nowMs += 10_000
        service.maybeRetry("poll")
        assertEquals(3, h.signal.passes)
    }

    /**
     * Review, 2026-09-22: success and failure ride separate StateFlows on
     * separate collectors, so a success that was already inside
     * onBindEstablished and parked on the mutex can run AFTER a newer failure
     * classified its blocker. The binder's live `bindRetryPending` — true
     * before a failure is published, false before a success is — tells the
     * stale success from a current one. A real success that follows still
     * clears everything.
     *
     * Unconfined scope so each emission runs its collector synchronously; a
     * locked-device denial classifies to a blocker on the first failure.
     */
    @Test
    fun staleBindSuccess_doesNotClearANewerFailuresBlocker() = runBlocking {
        val collectors = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
        )
        try {
            val h = Harness(deviceLocked = true)
            h.signal.primeFailed()
            val service = h.service(collectors)

            h.fail(lockedDenial())
            val blocker = service.blocker.value
            assertNotNull("the failure classified a blocker", blocker)
            val clearsBefore = h.noticeClears

            // The stale success: `bindEstablished` reads true while the binder
            // still reports a retry pending for the newer failure.
            h.established.value = true
            assertEquals("a superseded success leaves the newer failure's blocker in place", blocker, service.blocker.value)
            assertEquals("…and does not clear the notice", clearsBefore, h.noticeClears)
            assertFalse("…and persists no NONE", h.persisted.contains(null))

            // The binder lowers `bindEstablished` for the failure it published;
            // the genuine success that follows clears the state as before.
            h.established.value = false
            h.succeed()
            assertNull(service.blocker.value)
            assertEquals(clearsBefore + 1, h.noticeClears)
            assertTrue(h.persisted.contains(null))
        } finally {
            collectors.cancel()
        }
    }

    /**
     * Review, 2026-09-24: the mirror image of the stale success. A failure
     * that reached its handler and then waited on the mutex while the OTHER
     * collector delivered a success is history by the time it classifies —
     * the identity check cannot see that, because it compares against a
     * value the failure collector itself wrote. The binder's live
     * `bindRetryPending` reads false once the bind succeeded, and the
     * classifier now drops the failure on that.
     */
    @Test
    fun staleBindFailure_arrivingAfterTheBindSucceeded_publishesNothing() = runBlocking {
        val collectors = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
        )
        try {
            val h = Harness(deviceLocked = true)
            h.signal.primeFailed()
            val service = h.service(collectors)
            h.fail(lockedDenial())
            assertNotNull(service.blocker.value)

            h.succeed() // pending=false, blocker cleared
            assertNull(service.blocker.value)
            val noticesBefore = h.notices.size
            val persistedBefore = h.persisted.size

            // The stale failure: the binder still says no retry is pending.
            h.failures.value = SdkBindFailure(lockedDenial(), 1, atMs = ++h.nowMs)
            assertNull("a failure that lost the race to a success republishes nothing", service.blocker.value)
            assertEquals(noticesBefore, h.notices.size)
            assertEquals(persistedBefore, h.persisted.size)
        } finally {
            collectors.cancel()
        }
    }

    @Test
    fun maybeRetry_successResetsTheLadder() = runTest {
        val h = Harness()
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        service.maybeRetry("poll") // fails, arms the 5s rung
        h.signal.passSucceeds = true
        h.nowMs += 5_001
        service.maybeRetry("poll") // succeeds — pending clears
        assertEquals(2, h.signal.passes)
        assertFalse(h.signal.pending)

        // Bound: later consults are no-ops.
        h.nowMs += 100_000
        service.maybeRetry("poll")
        assertEquals(2, h.signal.passes)
    }

    @Test
    fun noteAppForeground_drivesARetryImmediately() = runTest {
        val h = Harness()
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        // Climb to the hourly tail: 5 retries.
        repeat(5) {
            h.nowMs += 3_600_000
            service.maybeRetry("poll")
        }
        assertEquals(5, h.signal.passes)

        // Deep inside the hourly window nothing fires…
        h.nowMs += 60_000
        service.maybeRetry("poll")
        assertEquals(5, h.signal.passes)

        // …until the app foregrounds, which now DRIVES a pass itself rather than
        // only resetting the ladder for some other trigger to notice.
        //
        // MO-995: the old state-only behaviour depended on `maybeRetry` being
        // called by CutoverUiDataService's bound-wallet wait loop — a loop that
        // only runs while the cutover holds dashj with NO bound wallet. Once the
        // coordinator refuses to commit onto an unbindable SDK that state never
        // happens, so nothing ever polled and nothing ever retried (emulator S3:
        // unlock + foreground produced zero binder activity; only an app restart
        // healed it).
        service.noteAppForeground()
        runCurrent()
        assertEquals("foregrounding must itself run a bind pass", 6, h.signal.passes)
    }

    // ── The unlock heal receiver ──────────────────────────────────────

    @Test
    fun unlockReceiver_armsOnceAndHealsWithAnImmediateRetry() = runTest {
        val h = Harness()
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        service.maybeRetry("poll")
        h.nowMs += 5_001
        service.maybeRetry("poll")
        assertEquals(1, h.registrations) // armed exactly once
        assertEquals(2, h.signal.passes)

        // The device unlock is the heal condition: the keystore stops
        // denying, and the receiver-triggered retry bypasses the backoff.
        h.signal.passSucceeds = true
        checkNotNull(h.unlockCallback).invoke()
        runCurrent()
        assertEquals(3, h.signal.passes)
        assertFalse(h.signal.pending)
    }

    @Test
    fun unlockReceiver_reArmsOnALaterConsult_whenRegistrationFailed() = runTest {
        val h = Harness(registerSucceeds = false)
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        service.maybeRetry("poll")
        assertEquals(0, h.registrations)

        h.registerSucceeds = true
        h.nowMs += 5_001
        service.maybeRetry("poll")
        assertEquals(1, h.registrations)
    }

    // ── No engine fallback ────────────────────────────────────────────

    @Test
    fun persistentFailures_keepRetryingOnTheHourlyTail_withTheDeviceUnlocked() = runTest {
        // Five consecutive failures used to roll the cutover back to dashj.
        // Now they are just five failures: the ladder keeps going.
        val h = Harness()
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        repeat(8) {
            h.nowMs += 3_600_000
            service.maybeRetry("poll")
        }
        assertEquals(8, h.signal.passes)
        assertTrue(h.signal.pending)
    }

    @Test
    fun persistentFailures_whileProvablyLocked_healOnTheUnlockReceiver() = runTest {
        // A genuinely locked device EXPECTS keystore denials; the unlock is
        // the heal.
        val h = Harness(deviceLocked = true)
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        repeat(8) {
            h.nowMs += 3_600_000
            service.maybeRetry("poll")
        }
        assertTrue(h.signal.pending)

        h.deviceLocked = false
        h.signal.passSucceeds = true
        checkNotNull(h.unlockCallback).invoke()
        runCurrent()
        assertFalse(h.signal.pending)
    }

    // ── End-to-end invariant: a failing bind never hands L1 back to dashj ──

    /**
     * The MO-995 outage replayed over a REAL [SdkWalletBinder] and a REAL
     * [CutoverCoordinator], under the no-fallback policy: fresh-wallet commit
     * holds dashj, the SDK bind (createWallet in the keystore) fails on every
     * pass, the retry service drives the ladder past the old five-failure
     * threshold — and the cutover stays CUT_OVER with `dashjEngineMayStart()`
     * false throughout. The old end state (rolled back to DUAL_RUNNING, dashj
     * syncing) is exactly what produced two SPV engines on the reference
     * install once the bind later succeeded.
     */
    @Test
    fun endToEnd_persistentBindFailure_staysCutOver_andNeverStartsDashj() = runTest {
        var storedState: String? = null
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } answers { storedState }
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns true
        coEvery { config.get(DashPayConfig.SDK_BIND_EVER_SUCCEEDED) } returns false
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns false
        coEvery { config.set(DashPayConfig.CUTOVER_STATE, any<String>()) } answers {
            storedState = secondArg()
            Unit
        }
        val collector = mockk<CutoverEvidenceCollector>()
        val coordinator = CutoverCoordinator(config, collector)

        // Real binder whose SDK bind dies in the keystore, forever.
        val sdk = mockk<DashSdkService>(relaxed = true)
        coEvery { sdk.bindAppWallet(any(), any()) } throws
            IllegalStateException("Keystore createWallet: UserNotAuthenticatedException")
        val identityConfig = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityConfig> {
            coEvery { loadBase() } returns de.schildbach.wallet.database.entity.BlockchainIdentityBaseData(
                creationState = de.schildbach.wallet.database.entity.IdentityCreationState.NONE,
                creationStateErrorMessage = null,
                username = null,
                usernameSecondary = null,
                userId = null,
                restoring = false
            )
        }
        val walletData = mockk<de.schildbach.wallet.data.WalletData> {
            io.mockk.every { wallet } returns null
        }
        val serviceConfig = mockk<org.dash.wallet.common.data.BlockchainServiceConfig> {
            coEvery { getWalletCreationDate() } returns null
        }
        val binder = SdkWalletBinder(
            sdkService = sdk,
            mnemonicProvider = object : PlatformMnemonicProvider {
                override suspend fun getMnemonicWords(unlock: WalletUnlock) =
                    listOf("abandon", "abandon", "about")
            },
            identityConfig = identityConfig,
            dashPayConfig = config,
            walletData = walletData,
            blockchainServiceConfig = serviceConfig,
            scope = backgroundScope,
            supportsPlatform = { true },
            backfillGate = DashPayBackfillGate.ALWAYS_RUN
        )

        assertEquals(CutoverState.CUT_OVER, coordinator.commitForFreshWalletSetup().state)
        assertFalse(coordinator.dashjEngineMayStart())

        binder.bindIfEnabled { WalletUnlock.Unencrypted }
        assertTrue(binder.bindRetryPending.value)

        var nowMs = 0L
        val retryService = SdkBindRetryService(
            scope = backgroundScope,
            bindRetryPending = { binder.bindRetryPending.value },
            consecutiveBindFailures = { binder.consecutiveBindFailures },
            runBindPass = { binder.bindIfEnabled { WalletUnlock.Unencrypted } },
            registerUnlockReceiver = { true },
            deviceProvablyLocked = { false },
            now = { nowMs }
        )
        repeat(10) {
            nowMs += 3_600_000
            retryService.maybeRetry("poll")
        }

        assertTrue("still failing", binder.consecutiveBindFailures >= 10)
        assertTrue("still pending — retries continue", binder.bindRetryPending.value)
        assertEquals("the cutover never rolled back", CutoverState.CUT_OVER.name, storedState)
        assertFalse("dashj never starts on its own", coordinator.dashjEngineMayStart())
    }

    /** The bind HEALS on the ladder — the cutover was committed all along. */
    @Test
    fun endToEnd_bindHealsOnTheLadder() = runTest {
        val h = Harness()
        h.signal.primeFailed()
        var nowMs = 0L
        val service = SdkBindRetryService(
            scope = backgroundScope,
            bindRetryPending = { h.signal.pending },
            consecutiveBindFailures = { h.signal.failures },
            runBindPass = { h.signal.bindPass() },
            registerUnlockReceiver = { true },
            deviceProvablyLocked = { false },
            now = { nowMs }
        )
        service.maybeRetry("poll") // failure 2
        nowMs += 5_001
        service.maybeRetry("poll") // failure 3
        h.signal.passSucceeds = true
        nowMs += 15_001
        service.maybeRetry("poll") // heals on the third retry
        assertFalse(h.signal.pending)
    }

    // ── "SDK setup pending": classification, arming, notification ─────

    @Test
    fun lockedDenial_publishesDeviceLocked_armsTheReceiverImmediately_andNotifiesInBackground() = runTest {
        // The reference install, 2026-09-14 14:49: background start, phone
        // locked, createWallet denied. Nothing polled maybeRetry for 22 hours
        // and the receiver was never armed. Now the first failure does both.
        val h = Harness(deviceLocked = true)
        h.appInBackground = true
        val service = h.service(backgroundScope)
        runCurrent()
        assertEquals(0, h.registrations)

        h.fail(lockedDenial())
        runCurrent()

        assertEquals(SdkBindBlocker.DEVICE_LOCKED, service.blocker.value)
        assertEquals("armed on the failure itself, no poll needed", 1, h.registrations)
        assertEquals(listOf(SdkBindBlocker.DEVICE_LOCKED), h.notices)
        assertEquals(listOf<SdkBindBlocker?>(SdkBindBlocker.DEVICE_LOCKED), h.persisted)
    }

    @Test
    fun lockedDenial_inTheForeground_doesNotNotify() = runTest {
        val h = Harness(deviceLocked = true)
        h.appInBackground = false
        val service = h.service(backgroundScope)
        h.fail(lockedDenial())
        runCurrent()
        assertEquals(SdkBindBlocker.DEVICE_LOCKED, service.blocker.value)
        assertTrue(h.notices.isEmpty())

        // Leaving the app with the bind still pending posts it then.
        service.noteAppBackground()
        assertEquals(listOf(SdkBindBlocker.DEVICE_LOCKED), h.notices)
    }

    @Test
    fun threeUnlockedDenials_escalateToKeystoreProblem() = runTest {
        // walletB: the keyguard says unlocked, Keystore2 still denies.
        val h = Harness(deviceLocked = false)
        val service = h.service(backgroundScope)

        h.fail(unlockedDenial()); runCurrent()
        assertEquals(SdkBindBlocker.KEYSTORE_DENIED_UNLOCKED, service.blocker.value)
        h.fail(unlockedDenial()); runCurrent()
        assertEquals(SdkBindBlocker.KEYSTORE_DENIED_UNLOCKED, service.blocker.value)
        h.fail(unlockedDenial()); runCurrent()
        assertEquals(SdkBindBlocker.KEYSTORE_PROBLEM, service.blocker.value)
        assertEquals(
            "persisted on every change, not every failure",
            listOf<SdkBindBlocker?>(SdkBindBlocker.KEYSTORE_DENIED_UNLOCKED, SdkBindBlocker.KEYSTORE_PROBLEM),
            h.persisted
        )
    }

    @Test
    fun aLockedDenial_resetsTheUnlockedStreak() = runTest {
        val h = Harness(deviceLocked = false)
        val service = h.service(backgroundScope)
        h.fail(unlockedDenial()); runCurrent()
        h.fail(unlockedDenial()); runCurrent()
        h.deviceLocked = true
        h.fail(lockedDenial()); runCurrent()
        assertEquals(SdkBindBlocker.DEVICE_LOCKED, service.blocker.value)
        h.deviceLocked = false
        h.fail(unlockedDenial()); runCurrent()
        assertEquals("streak restarted after the locked denial", SdkBindBlocker.KEYSTORE_DENIED_UNLOCKED, service.blocker.value)
    }

    @Test
    fun otherFailures_becomeSetupFailed_afterFive() = runTest {
        val h = Harness()
        val service = h.service(backgroundScope)
        repeat(4) { h.fail(IllegalStateException("Room: database is locked")); runCurrent() }
        assertEquals(SdkBindBlocker.OTHER, service.blocker.value)
        h.fail(IllegalStateException("Room: database is locked")); runCurrent()
        assertEquals(SdkBindBlocker.SETUP_FAILED, service.blocker.value)
    }

    @Test
    fun bindSuccess_clearsTheBlocker_theNotification_andThePersistedRecord() = runTest {
        val h = Harness(deviceLocked = true)
        h.appInBackground = true
        val service = h.service(backgroundScope)
        h.fail(lockedDenial()); runCurrent()
        assertEquals(SdkBindBlocker.DEVICE_LOCKED, service.blocker.value)

        h.succeed(); runCurrent()

        assertEquals(null, service.blocker.value)
        assertEquals(1, h.noticeClears)
        assertEquals(listOf<SdkBindBlocker?>(SdkBindBlocker.DEVICE_LOCKED, null), h.persisted)
    }

    /**
     * Regression, emulator-5554 2026-09-16: the app was force-stopped mid-repair,
     * the bind then succeeded in the fresh process and the notification cleared,
     * yet `sdk_bind_blocker` still read OTHER in the support report. The clear
     * path keyed off the in-memory blocker, which a new process starts at null,
     * so it early-returned and left the previous process's record standing.
     */
    @Test
    fun bindSuccess_inAProcessThatNeverSawAFailure_stillClearsThePersistedRecord() = runTest {
        val h = Harness()
        val service = h.service(backgroundScope)
        runCurrent()
        // Fresh process: nothing failed here, so there is no in-memory blocker.
        assertEquals(null, service.blocker.value)

        h.succeed(); runCurrent()

        assertEquals(
            "the durable record must be overwritten with NONE even when this " +
                "process never classified a blocker of its own",
            listOf<SdkBindBlocker?>(null),
            h.persisted
        )
    }

    @Test
    fun foregroundWithAPendingBind_clearsTheNotification() = runTest {
        val h = Harness(deviceLocked = true)
        h.appInBackground = true
        val service = h.service(backgroundScope)
        h.fail(lockedDenial()); runCurrent()
        assertEquals(1, h.notices.size)

        service.noteAppForeground()
        runCurrent()
        assertEquals(1, h.noticeClears)
    }

    // ── The binder's own outcome bookkeeping (real binder) ────────────

    @Test
    fun binder_flagsThePendingRetry_andClearsItOnSuccess() = runBlocking {
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns true
        coEvery { config.get(DashPayConfig.SDK_GAP_WIDENED_VERSION) } returns
            SdkWalletBinder.GAP_WIDEN_HEAL_VERSION
        var bindFails = true
        val sdk = mockk<DashSdkService>(relaxed = true)
        coEvery { sdk.bindAppWallet(any(), any()) } answers {
            if (bindFails) throw IllegalStateException("keystore denied") else "ab".repeat(32)
        }
        coEvery { sdk.loadedWalletIds() } returns emptySet()
        val identityConfig = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityConfig> {
            coEvery { loadBase() } returns de.schildbach.wallet.database.entity.BlockchainIdentityBaseData(
                creationState = de.schildbach.wallet.database.entity.IdentityCreationState.NONE,
                creationStateErrorMessage = null,
                username = null,
                usernameSecondary = null,
                userId = null,
                restoring = false
            )
        }
        val binder = SdkWalletBinder(
            sdkService = sdk,
            mnemonicProvider = object : PlatformMnemonicProvider {
                override suspend fun getMnemonicWords(unlock: WalletUnlock) =
                    listOf("abandon", "abandon", "about")
            },
            identityConfig = identityConfig,
            dashPayConfig = config,
            walletData = mockk { io.mockk.every { wallet } returns null },
            blockchainServiceConfig = mockk { coEvery { getWalletCreationDate() } returns null },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            supportsPlatform = { true },
            backfillGate = DashPayBackfillGate.ALWAYS_RUN
        )

        // Two failed passes: pending + a climbing consecutive count.
        binder.bindIfEnabled { WalletUnlock.Unencrypted }
        assertTrue(binder.bindRetryPending.value)
        assertEquals(1, binder.consecutiveBindFailures)
        binder.bindIfEnabled { WalletUnlock.Unencrypted }
        assertEquals(2, binder.consecutiveBindFailures)

        // The keystore heals (the post-unlock retry): everything clears.
        bindFails = false
        binder.bindIfEnabled { WalletUnlock.Unencrypted }
        assertFalse(binder.bindRetryPending.value)
        assertEquals(0, binder.consecutiveBindFailures)
    }

    @Test
    fun binder_skippedPass_neverArmsTheRetry() = runBlocking {
        // All flags off → the eligibility gate skips before touching the
        // SDK; a skip carries no keystore evidence and must not arm.
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns false
        val binder = SdkWalletBinder(
            sdkService = mockk(relaxed = true),
            mnemonicProvider = object : PlatformMnemonicProvider {
                override suspend fun getMnemonicWords(unlock: WalletUnlock) = error("must not run")
            },
            identityConfig = mockk(),
            dashPayConfig = config,
            walletData = mockk { io.mockk.every { wallet } returns null },
            blockchainServiceConfig = mockk(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            supportsPlatform = { true },
            backfillGate = DashPayBackfillGate.ALWAYS_RUN
        )
        binder.bindIfEnabled { WalletUnlock.Unencrypted }
        assertFalse(binder.bindRetryPending.value)
        assertEquals(0, binder.consecutiveBindFailures)
    }

    /**
     * MO-995: the durable bind-success marker must be written on EVERY
     * successful pass, including the "app wallet already bound" path — not only
     * when a new SDK wallet is created.
     *
     * REGRESSION: the marker first lived inside `bindAppWallet(...).also { }`,
     * which only runs on a fresh bind. On any launch that found the SDK wallet
     * already bound, the marker was never written, so
     * `CutoverCoordinator.commitForUpgradedWalletAsync` declined forever and
     * the cutover never committed. Caught on the emulator: launch 2 of a clean
     * run still logged "bind has never succeeded" while the L1 engine was
     * demonstrably running.
     */
    @Test
    fun binder_persistsTheBindSuccessMarker_evenWhenTheWalletWasAlreadyBound() = runBlocking {
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns false
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns true
        coEvery { config.get(DashPayConfig.SDK_GAP_WIDENED_VERSION) } returns
            SdkWalletBinder.GAP_WIDEN_HEAL_VERSION
        var markerWritten = false
        coEvery { config.set(DashPayConfig.SDK_BIND_EVER_SUCCEEDED, any<Boolean>()) } answers {
            markerWritten = secondArg()
            Unit
        }
        val walletId = "cd".repeat(32)
        val sdk = mockk<DashSdkService>(relaxed = true)
        coEvery { sdk.bindAppWallet(any(), any()) } returns walletId
        // The ALREADY-BOUND path: the SDK reports the wallet is already loaded,
        // so a fresh bindAppWallet() is not what establishes it.
        coEvery { sdk.loadedWalletIds() } returns setOf(walletId)
        val identityConfig = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityConfig> {
            coEvery { loadBase() } returns de.schildbach.wallet.database.entity.BlockchainIdentityBaseData(
                creationState = de.schildbach.wallet.database.entity.IdentityCreationState.NONE,
                creationStateErrorMessage = null,
                username = null,
                usernameSecondary = null,
                userId = null,
                restoring = false
            )
        }
        val binder = SdkWalletBinder(
            sdkService = sdk,
            mnemonicProvider = object : PlatformMnemonicProvider {
                override suspend fun getMnemonicWords(unlock: WalletUnlock) =
                    listOf("abandon", "abandon", "about")
            },
            identityConfig = identityConfig,
            dashPayConfig = config,
            walletData = mockk { io.mockk.every { wallet } returns null },
            blockchainServiceConfig = mockk { coEvery { getWalletCreationDate() } returns null },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            supportsPlatform = { true },
            backfillGate = DashPayBackfillGate.ALWAYS_RUN
        )

        binder.bindIfEnabled { WalletUnlock.Unencrypted }

        assertTrue(
            "a successful pass must persist SDK_BIND_EVER_SUCCEEDED regardless of which " +
                "path established the bind — otherwise the upgrade seam declines forever",
            markerWritten
        )
    }

    /**
     * The emulator S3 scenario, reduced: a pending retry, and NOTHING polling.
     *
     * This is the state the wallet is actually in after the coordinator
     * declines to commit onto an unbindable SDK — CutoverUiDataService's
     * bound-wallet wait loop (the sole caller of [SdkBindRetryService.maybeRetry])
     * never runs, so no poll ever arrives. Foregrounding the app must be
     * sufficient on its own, with no `maybeRetry` call anywhere in the test.
     */
    @Test
    fun noteAppForeground_recoversWithNoPollingTriggerAtAll() = runTest {
        val h = Harness()
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        assertEquals("no poll has happened", 0, h.signal.passes)

        service.noteAppForeground()
        runCurrent()

        assertEquals("a foreground visit alone must retry the bind", 1, h.signal.passes)
    }

    /**
     * Foregrounding also ARMS the unlock receiver. Arming used to happen only
     * inside [SdkBindRetryService.maybeRetry], so in the no-polling state above
     * the receiver was never registered either — walletB logged
     * "unlock-heal receiver registered" zero times.
     */
    @Test
    fun noteAppForeground_armsTheUnlockReceiver() = runTest {
        val h = Harness()
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        service.noteAppForeground()
        runCurrent()

        assertEquals("the unlock-heal receiver must be armed by a foreground visit", 1, h.registrations)
    }
}
