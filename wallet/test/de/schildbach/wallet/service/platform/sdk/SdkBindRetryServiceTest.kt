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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the MO-995 bind retry machinery: the capped backoff
 * ladder, the re-arming semantics ([SdkBindRetryService.maybeRetry] /
 * [SdkBindRetryService.retryNowInBackground]), the device-unlock heal
 * receiver arming, and the engine-fallback rollback — including the
 * end-to-end invariant over a REAL binder + REAL coordinator: after
 * persistent bind failures the gate ends with dashj allowed OR the SDK
 * wallet bound, never both held.
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
        var rollbacks = 0
        var lastRollbackFailures = -1
        var registrations = 0
        var unlockCallback: (() -> Unit)? = null

        fun service(scope: kotlinx.coroutines.CoroutineScope) = SdkBindRetryService(
            scope = scope,
            bindRetryPending = { signal.pending },
            consecutiveBindFailures = { signal.failures },
            runBindPass = { signal.bindPass() },
            rollbackCutover = { failures ->
                rollbacks++
                lastRollbackFailures = failures
            },
            registerUnlockReceiver = { onUserPresent ->
                if (registerSucceeds) {
                    registrations++
                    unlockCallback = onUserPresent
                }
                registerSucceeds
            },
            deviceProvablyLocked = { deviceLocked },
            now = { nowMs }
        )
    }

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
        assertEquals(0, h.rollbacks)
    }

    @Test
    fun noteAppForeground_collapsesTheBackoffWindow() = runTest {
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

        // …until the app foregrounds, which resets the ladder.
        service.noteAppForeground()
        service.maybeRetry("poll")
        assertEquals(6, h.signal.passes)
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
        assertEquals(0, h.rollbacks)
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

    // ── The engine-fallback rollback ──────────────────────────────────

    @Test
    fun rollback_firesAfterTheFailureThreshold_withTheDeviceUnlocked() = runTest {
        val h = Harness()
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        // Failures 2..4 (initial + three retries): below the threshold.
        repeat(3) {
            h.nowMs += 3_600_000
            service.maybeRetry("poll")
        }
        assertEquals(0, h.rollbacks)

        // The 5th consecutive failure crosses it.
        h.nowMs += 3_600_000
        service.maybeRetry("poll")
        assertEquals(1, h.rollbacks)
        assertEquals(5, h.lastRollbackFailures)
    }

    @Test
    fun rollback_isHeldWhileTheDeviceIsProvablyLocked() = runTest {
        // A genuinely locked device EXPECTS keystore denials; flipping
        // engines for it would punish every locked-screen background start.
        val h = Harness(deviceLocked = true)
        h.signal.primeFailed()
        val service = h.service(backgroundScope)

        repeat(8) {
            h.nowMs += 3_600_000
            service.maybeRetry("poll")
        }
        assertTrue(h.signal.failures >= SdkBindRetryService.ROLLBACK_AFTER_CONSECUTIVE_FAILURES)
        assertEquals(0, h.rollbacks)

        // Unlock: the receiver retry heals instead — no rollback needed.
        h.deviceLocked = false
        h.signal.passSucceeds = true
        checkNotNull(h.unlockCallback).invoke()
        runCurrent()
        assertFalse(h.signal.pending)
        assertEquals(0, h.rollbacks)
    }

    // ── End-to-end invariant: dashj allowed OR sdk bound, never both held ──

    /**
     * The full MO-995 outage replayed over a REAL [SdkWalletBinder] and a
     * REAL [CutoverCoordinator]: fresh-wallet commit holds dashj, the SDK
     * bind (createWallet in the keystore) fails on every pass, the retry
     * service drives the ladder — and the gate MUST end with
     * `dashjEngineMayStart() == true`. Before this fix the end state was
     * dashj held forever with nothing bound: no sync engine at all.
     */
    @Test
    fun endToEnd_persistentBindFailure_endsWithDashjAllowed_neverBothHeld() = runTest {
        // Real coordinator over a stateful in-memory CUTOVER_STATE.
        var storedState: String? = null
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } answers { storedState }
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns true
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

        // The Andrei launch: fresh-wallet setup commits the cutover…
        assertEquals(CutoverState.CUT_OVER, coordinator.commitForFreshWalletSetup().state)
        assertFalse(coordinator.dashjEngineMayStart()) // dashj held

        // …then the first bind pass fails (the keystore denial).
        binder.bindIfEnabled { WalletUnlock.Unencrypted }
        assertTrue(binder.bindRetryPending.value)

        // The retry service drives the ladder to the rollback threshold.
        var nowMs = 0L
        val retryService = SdkBindRetryService(
            scope = backgroundScope,
            bindRetryPending = { binder.bindRetryPending.value },
            consecutiveBindFailures = { binder.consecutiveBindFailures },
            runBindPass = { binder.bindIfEnabled { WalletUnlock.Unencrypted } },
            rollbackCutover = { failures -> coordinator.rollbackForFailedBind(failures) },
            registerUnlockReceiver = { true },
            deviceProvablyLocked = { false },
            now = { nowMs }
        )
        repeat(SdkBindRetryService.ROLLBACK_AFTER_CONSECUTIVE_FAILURES - 1) {
            nowMs += 3_600_000
            retryService.maybeRetry("poll")
        }

        // THE INVARIANT: the gate rolled back — dashj may sync again.
        assertEquals(CutoverState.DUAL_RUNNING.name, storedState)
        assertTrue(coordinator.dashjEngineMayStart())
    }

    /** The complementary end state: the bind HEALS — the cutover stays committed (SDK owns L1). */
    @Test
    fun endToEnd_bindHealsBeforeTheThreshold_cutoverStaysCommitted() = runTest {
        val h = Harness()
        h.signal.primeFailed()
        var rolledBack = false
        var nowMs = 0L
        val service = SdkBindRetryService(
            scope = backgroundScope,
            bindRetryPending = { h.signal.pending },
            consecutiveBindFailures = { h.signal.failures },
            runBindPass = { h.signal.bindPass() },
            rollbackCutover = { rolledBack = true },
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
        assertFalse(rolledBack)
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
}
