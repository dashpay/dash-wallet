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

package de.schildbach.wallet.ui.shielded

import android.content.Context
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.ShieldFromWalletOutcome
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet_test.R
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.NotificationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the app-scoped spend owner [ShieldedTransferExecutor]:
 *
 * - single-attempt semantics — [ShieldedTransferExecutor.submit]
 *   atomically refuses while an operation is Proving or a terminal
 *   no-retry state is held, so re-attaching observers (a recreated
 *   ViewModel) can never cause a second broadcast;
 * - the SdkWriteResult → [ShieldedSubmitState] mapping (moved verbatim
 *   from the ViewModel);
 * - outcome surfacing: system notification when the app is backgrounded
 *   (with the per-outcome copy), global in-app toast when foregrounded
 *   away from the transfer screen, and silence while the transfer screen
 *   is visible (it surfaces the result itself);
 * - the fresh-visit / acknowledge state clearing rules;
 * - the stall watchdog: no terminal result within
 *   [ShieldedTransferExecutor.STALL_TIMEOUT_MS] (a wedged uncancellable
 *   native call) → the funds-honest Stalled state, announced once,
 *   non-resubmittable and sticky until a REAL terminal outcome
 *   supersedes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldedTransferExecutorTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val shieldedService = mockk<ShieldedBalanceService>()
    private val walletData = mockk<WalletDataProvider> {
        every { freshReceiveAddressString() } returns "yTestAddressBase58"
    }
    private val notificationService = mockk<NotificationService>(relaxUnitFun = true)
    private val appContext = mockk<Context> {
        every { getString(any()) } answers { "str:${firstArg<Int>()}" }
    }
    private val inAppToasts = mutableListOf<String>()

    private fun executor(
        foreground: Boolean = true,
        transferScreenVisible: Boolean = true
    ) = ShieldedTransferExecutor(
        shieldedService,
        walletData,
        notificationService,
        appContext,
        CoroutineScope(dispatcher)
    ).apply {
        ioDispatcher = dispatcher
        isAppInForeground = { foreground }
        transferUiVisible = transferScreenVisible
        moreScreenIntent = { null }
        showInAppToast = { inAppToasts += it }
    }

    private fun str(resId: Int) = "str:$resId"

    // ── Single-attempt semantics ────────────────────────────────────────

    @Test
    fun submit_refusedWhileAnOperationIsInFlight() = runTest(dispatcher) {
        val gate = CompletableDeferred<SdkWriteResult<ShieldFromWalletOutcome>>()
        coEvery { shieldedService.shieldFromWallet(any()) } coAnswers { gate.await() }
        val executor = executor()

        assertTrue(executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1")))
        assertEquals(ShieldedSubmitState.Proving, executor.submitState.value)

        // A second confirmation (or any re-attach mishap) must not spend again.
        assertFalse(executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1")))
        assertEquals(ShieldedSubmitState.Proving, executor.submitState.value)

        gate.complete(SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED))
        assertEquals(ShieldedSubmitState.Success, executor.submitState.value)
        coVerify(exactly = 1) { shieldedService.shieldFromWallet(any()) }
    }

    @Test
    fun submit_refusedFromTerminalNoRetryStates_allowedAfterNotSent() = runTest(dispatcher) {
        // Ambiguous is terminal: never re-submittable.
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Ambiguous(RuntimeException("timeout"))
        val executor = executor()
        assertTrue(executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1")))
        assertEquals(ShieldedSubmitState.MayHaveGoneThrough, executor.submitState.value)
        assertFalse(executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1")))
        coVerify(exactly = 1) { shieldedService.shieldFromWallet(any()) }

        // NotSent is provably pre-broadcast: a retry submit is allowed.
        executor.acknowledge()
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.NotBroadcast("preflight failed")
        assertTrue(executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1")))
        assertTrue(executor.submitState.value is ShieldedSubmitState.NotSent)
        assertTrue(executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1")))
    }

    // ── SdkWriteResult mapping (moved from the ViewModel) ───────────────

    @Test
    fun resultMapping_matchesTheWriteContract() = runTest(dispatcher) {
        val executor = executor()

        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
        assertEquals(ShieldedSubmitState.LockedPendingShield, executor.submitState.value)
        executor.acknowledge()

        coEvery { shieldedService.withdrawToCore(any(), any()) } returns
            SdkWriteResult.Broadcast(Unit)
        executor.submit(ShieldedTransferDirection.FromShielded, Dash.parse("1"))
        assertEquals(ShieldedSubmitState.Success, executor.submitState.value)
        // the withdraw draws a fresh Core receive address
        coVerify { shieldedService.withdrawToCore("yTestAddressBase58", Dash.parse("1")) }
    }

    @Test
    fun freshAddressFailure_isNotSent_notAmbiguous() = runTest(dispatcher) {
        every { walletData.freshReceiveAddressString() } throws IllegalStateException("wallet locked")
        val executor = executor()

        executor.submit(ShieldedTransferDirection.FromShielded, Dash.parse("1"))

        // pre-broadcast failure must surface as NotSent (retry-safe), never Ambiguous
        assertTrue(executor.submitState.value is ShieldedSubmitState.NotSent)
    }

    // ── Outcome surfacing: backgrounded → system notification ───────────

    @Test
    fun backgrounded_success_postsNotification_withMoreScreenToastRoute() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        val executor = executor(foreground = false, transferScreenVisible = false)
        var toastRouteRequested: Boolean? = null
        executor.moreScreenIntent = { withToast ->
            toastRouteRequested = withToast
            null
        }

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))

        verify {
            notificationService.showNotification(
                ShieldedTransferExecutor.NOTIFICATION_TAG,
                str(R.string.shielded_notification_success_message),
                str(R.string.shielded_transfer_completed),
                null,
                null,
                null
            )
        }
        // tapping success lands on the More screen WITH its completed toast
        assertEquals(true, toastRouteRequested)
        assertTrue(inAppToasts.isEmpty())
    }

    @Test
    fun backgrounded_eachTerminalOutcome_postsItsMappedCopy() = runTest(dispatcher) {
        val cases = listOf<Triple<SdkWriteResult<ShieldFromWalletOutcome>, Int, Int>>(
            Triple(
                SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY),
                R.string.shielded_locked_pending_title,
                R.string.shielded_notification_pending_message
            ),
            Triple(
                SdkWriteResult.NotBroadcast("preflight failed"),
                R.string.shielded_transfer_failed,
                R.string.shielded_transfer_failed_message
            ),
            Triple(
                SdkWriteResult.Ambiguous(RuntimeException("timeout")),
                R.string.shielded_transfer_ambiguous_title,
                R.string.shielded_transfer_ambiguous_message
            )
        )
        for ((result, titleRes, messageRes) in cases) {
            coEvery { shieldedService.shieldFromWallet(any()) } returns result
            val executor = executor(foreground = false, transferScreenVisible = false)

            executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))

            verify {
                notificationService.showNotification(
                    ShieldedTransferExecutor.NOTIFICATION_TAG,
                    str(messageRes),
                    str(titleRes),
                    null,
                    null,
                    null
                )
            }
        }
        assertTrue(inAppToasts.isEmpty())
    }

    // ── Outcome surfacing: foregrounded ─────────────────────────────────

    @Test
    fun transferScreenVisible_surfacesNothing_theScreenHandlesIt() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        val executor = executor(foreground = true, transferScreenVisible = true)

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))

        verify(exactly = 0) {
            notificationService.showNotification(any(), any(), any(), any(), any(), any())
        }
        assertTrue(inAppToasts.isEmpty())
        // the state stays for the screen's own success navigation
        assertEquals(ShieldedSubmitState.Success, executor.submitState.value)
    }

    @Test
    fun foregroundedElsewhere_showsInAppToast_andAutoAcknowledgesSuccess() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        val executor = executor(foreground = true, transferScreenVisible = false)

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))

        assertEquals(listOf(str(R.string.shielded_transfer_completed)), inAppToasts)
        verify(exactly = 0) {
            notificationService.showNotification(any(), any(), any(), any(), any(), any())
        }
        // the success story is told: a still-alive transfer screen must
        // not re-run its success navigation when the user wanders back
        assertEquals(ShieldedSubmitState.Idle, executor.submitState.value)
    }

    @Test
    fun foregroundedElsewhere_failureToastKeepsTheStateSticky() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Ambiguous(RuntimeException("timeout"))
        val executor = executor(foreground = true, transferScreenVisible = false)

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))

        assertEquals(listOf(str(R.string.shielded_transfer_ambiguous_title)), inAppToasts)
        // funds-critical: the overlay must still confront the user on the
        // next screen visit until acknowledged
        assertEquals(ShieldedSubmitState.MayHaveGoneThrough, executor.submitState.value)
    }

    // ── State clearing rules ────────────────────────────────────────────

    @Test
    fun clearForNewVisit_dropsSuccessAndNotSent_keepsInFlightAndFundsCriticalStates() =
        runTest(dispatcher) {
            val executor = executor()

            coEvery { shieldedService.shieldFromWallet(any()) } returns
                SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
            executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
            executor.clearForNewVisit()
            assertEquals(ShieldedSubmitState.Idle, executor.submitState.value)

            coEvery { shieldedService.shieldFromWallet(any()) } returns
                SdkWriteResult.NotBroadcast("preflight failed")
            executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
            executor.clearForNewVisit()
            assertEquals(ShieldedSubmitState.Idle, executor.submitState.value)

            coEvery { shieldedService.shieldFromWallet(any()) } returns
                SdkWriteResult.Ambiguous(RuntimeException("timeout"))
            executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
            executor.clearForNewVisit()
            assertEquals(ShieldedSubmitState.MayHaveGoneThrough, executor.submitState.value)
            executor.acknowledge()

            val gate = CompletableDeferred<SdkWriteResult<ShieldFromWalletOutcome>>()
            coEvery { shieldedService.shieldFromWallet(any()) } coAnswers { gate.await() }
            executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
            executor.clearForNewVisit()
            // an in-flight operation is NEVER dropped — the fresh screen re-attaches
            assertEquals(ShieldedSubmitState.Proving, executor.submitState.value)
            gate.complete(SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED))
        }

    @Test
    fun acknowledge_resetsTerminalStates_neverAnInFlightOp() = runTest(dispatcher) {
        val gate = CompletableDeferred<SdkWriteResult<ShieldFromWalletOutcome>>()
        coEvery { shieldedService.shieldFromWallet(any()) } coAnswers { gate.await() }
        val executor = executor()

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
        executor.acknowledge()
        assertEquals(ShieldedSubmitState.Proving, executor.submitState.value)

        gate.complete(SdkWriteResult.Ambiguous(RuntimeException("timeout")))
        assertEquals(ShieldedSubmitState.MayHaveGoneThrough, executor.submitState.value)
        executor.acknowledge()
        assertEquals(ShieldedSubmitState.Idle, executor.submitState.value)
    }

    @Test
    fun clearRetryableResult_onlyClearsNotSent() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.NotBroadcast("preflight failed")
        val executor = executor()
        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
        executor.clearRetryableResult()
        assertEquals(ShieldedSubmitState.Idle, executor.submitState.value)

        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Ambiguous(RuntimeException("timeout"))
        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
        executor.clearRetryableResult()
        assertEquals(ShieldedSubmitState.MayHaveGoneThrough, executor.submitState.value)
    }

    // ── Stall watchdog ──────────────────────────────────────────────────

    @Test
    fun watchdog_firesAtTheThreshold_notBefore_andKeepsSubmitLocked() = runTest(dispatcher) {
        val gate = CompletableDeferred<SdkWriteResult<ShieldFromWalletOutcome>>()
        coEvery { shieldedService.shieldFromWallet(any()) } coAnswers { gate.await() }
        val executor = executor()

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
        advanceTimeBy(ShieldedTransferExecutor.STALL_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(ShieldedSubmitState.Proving, executor.submitState.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(ShieldedSubmitState.Stalled(), executor.submitState.value)

        // Stalled is IN-FLIGHT for the no-resubmit rule: the wedged call
        // may still clear, so a retry could double-submit.
        assertFalse(executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1")))
        coVerify(exactly = 1) { shieldedService.shieldFromWallet(any()) }

        // the wedged call eventually returns: the real outcome supersedes
        // Stalled and unlocks per its own semantics
        gate.complete(SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED))
        assertEquals(ShieldedSubmitState.Success, executor.submitState.value)
    }

    @Test
    fun watchdog_doesNotFire_whenATerminalResultLandsFirst() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        // transfer screen visible → Success stays for the screen to surface
        val executor = executor()

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
        assertEquals(ShieldedSubmitState.Success, executor.submitState.value)

        advanceTimeBy(ShieldedTransferExecutor.STALL_TIMEOUT_MS * 2)
        runCurrent()
        assertEquals(ShieldedSubmitState.Success, executor.submitState.value)
        assertTrue(inAppToasts.isEmpty())
        verify(exactly = 0) {
            notificationService.showNotification(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun stalled_announcedExactlyOnce_aLateTerminalIsAnnouncedToo() = runTest(dispatcher) {
        val gate = CompletableDeferred<SdkWriteResult<ShieldFromWalletOutcome>>()
        coEvery { shieldedService.shieldFromWallet(any()) } coAnswers { gate.await() }
        val executor = executor(foreground = false, transferScreenVisible = false)

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
        advanceTimeBy(ShieldedTransferExecutor.STALL_TIMEOUT_MS)
        runCurrent()

        assertEquals(ShieldedSubmitState.Stalled(), executor.submitState.value)
        verify(exactly = 1) {
            notificationService.showNotification(
                ShieldedTransferExecutor.NOTIFICATION_TAG,
                str(R.string.shielded_transfer_stalled_message),
                str(R.string.shielded_transfer_stalled_title),
                null,
                null,
                null
            )
        }

        // more waiting never re-announces the stall
        advanceTimeBy(ShieldedTransferExecutor.STALL_TIMEOUT_MS * 3)
        runCurrent()
        verify(exactly = 1) {
            notificationService.showNotification(
                any(),
                str(R.string.shielded_transfer_stalled_message),
                any(),
                any(),
                any(),
                any()
            )
        }

        // the wedged call finally returns: the real terminal outcome
        // supersedes Stalled and is announced through the same plumbing
        gate.complete(SdkWriteResult.Ambiguous(RuntimeException("timeout")))
        assertEquals(ShieldedSubmitState.MayHaveGoneThrough, executor.submitState.value)
        verify(exactly = 1) {
            notificationService.showNotification(
                ShieldedTransferExecutor.NOTIFICATION_TAG,
                str(R.string.shielded_transfer_ambiguous_message),
                str(R.string.shielded_transfer_ambiguous_title),
                null,
                null,
                null
            )
        }
        // …and follows its own semantics from here
        executor.acknowledge()
        assertEquals(ShieldedSubmitState.Idle, executor.submitState.value)
    }

    @Test
    fun stalled_acknowledgeOnlyHidesTheOverlay_stickyAcrossVisits_untilTerminal() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<SdkWriteResult<ShieldFromWalletOutcome>>()
            coEvery { shieldedService.shieldFromWallet(any()) } coAnswers { gate.await() }
            val executor = executor()

            executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
            advanceTimeBy(ShieldedTransferExecutor.STALL_TIMEOUT_MS)
            runCurrent()
            assertEquals(ShieldedSubmitState.Stalled(), executor.submitState.value)

            // acknowledge dismisses the overlay only — never resets to Idle
            executor.acknowledge()
            assertEquals(
                ShieldedSubmitState.Stalled(acknowledged = true),
                executor.submitState.value
            )
            assertFalse(executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1")))

            // sticky like MayHaveGoneThrough: a fresh screen visit keeps it
            executor.clearForNewVisit()
            assertEquals(
                ShieldedSubmitState.Stalled(acknowledged = true),
                executor.submitState.value
            )

            // a late provably-pre-broadcast failure supersedes and, per its
            // own semantics, makes a retry submit possible again
            gate.complete(SdkWriteResult.NotBroadcast("preflight failed"))
            assertTrue(executor.submitState.value is ShieldedSubmitState.NotSent)
            assertTrue(executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1")))
            coVerify(exactly = 2) { shieldedService.shieldFromWallet(any()) }
        }

    // ── Pure outcome-copy mapping ───────────────────────────────────────

    @Test
    fun outcomeNotificationMapping() {
        assertEquals(
            ShieldedOutcomeNotification(
                R.string.shielded_transfer_completed,
                R.string.shielded_notification_success_message,
                showsTransferCompletedToast = true
            ),
            shieldedOutcomeNotification(ShieldedSubmitState.Success)
        )
        assertEquals(
            ShieldedOutcomeNotification(
                R.string.shielded_locked_pending_title,
                R.string.shielded_notification_pending_message,
                showsTransferCompletedToast = false
            ),
            shieldedOutcomeNotification(ShieldedSubmitState.LockedPendingShield)
        )
        assertEquals(
            ShieldedOutcomeNotification(
                R.string.shielded_transfer_failed,
                R.string.shielded_transfer_failed_message,
                showsTransferCompletedToast = false
            ),
            shieldedOutcomeNotification(ShieldedSubmitState.NotSent("reason"))
        )
        assertEquals(
            ShieldedOutcomeNotification(
                R.string.shielded_transfer_ambiguous_title,
                R.string.shielded_transfer_ambiguous_message,
                showsTransferCompletedToast = false
            ),
            shieldedOutcomeNotification(ShieldedSubmitState.MayHaveGoneThrough)
        )
        // Stalled is not terminal but IS announced (once, by the watchdog)
        // — funds-honest copy, no failure wording; acknowledging doesn't
        // change the mapping
        val stalledContent = ShieldedOutcomeNotification(
            R.string.shielded_transfer_stalled_title,
            R.string.shielded_transfer_stalled_message,
            showsTransferCompletedToast = false
        )
        assertEquals(stalledContent, shieldedOutcomeNotification(ShieldedSubmitState.Stalled()))
        assertEquals(
            stalledContent,
            shieldedOutcomeNotification(ShieldedSubmitState.Stalled(acknowledged = true))
        )
        // nothing to announce for the other non-terminal states
        assertNull(shieldedOutcomeNotification(ShieldedSubmitState.Idle))
        assertNull(shieldedOutcomeNotification(ShieldedSubmitState.Proving))
    }
}
