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
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import de.schildbach.wallet.data.WalletData
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
 * - outcome surfacing: a durable system notification (with the
 *   per-outcome copy) whenever the user is not watching the transfer
 *   screen — backgrounded OR foregrounded elsewhere — and silence while
 *   the transfer screen is visible (it surfaces the result itself);
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
    private val walletData = mockk<WalletData> {
        every { freshReceiveAddressString() } returns "yTestAddressBase58"
    }
    private val notificationService = mockk<NotificationService>(relaxUnitFun = true)
    private val appContext = mockk<Context> {
        every { getString(any()) } answers { "str:${firstArg<Int>()}" }
    }

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

    // ── Max-spend fee adjustment (FromShielded only) ────────────────────

    /**
     * The live incident shape: the user's Max withdraw failed note
     * selection because required = amount + Rust-computed fee exceeded the
     * available shielded balance. Amounts in credits (1 duff = 1000):
     * deficit = 30062339200 − 29787148800 = 275190400 credits.
     */
    private val liveInsufficientMessage =
        "shielded withdraw failed: Insufficient shielded balance: " +
            "available 29787148800, required 30062339200"

    private fun insufficientShieldedResult() = SdkWriteResult.NotBroadcast(
        "pre-broadcast shielded note-selection failure",
        RuntimeException(liveInsufficientMessage)
    )

    /** What the user's Max tap requested: the shown balance, whole duffs. */
    private val maxRequested = Dash(29_787_148L)

    @Test
    fun maxWithdraw_insufficientForFee_retriesOnceWithTheFeeAdjustedAmount() = runTest(dispatcher) {
        coEvery { shieldedService.withdrawToCore(any(), any()) } returnsMany
            listOf(insufficientShieldedResult(), SdkWriteResult.Broadcast(Unit))
        val executor = executor()

        assertTrue(executor.submit(ShieldedTransferDirection.FromShielded, maxRequested, isMaxSpend = true))

        // one adjusted retry inside the same operation, then Success
        assertEquals(ShieldedSubmitState.Success, executor.submitState.value)
        // first the full balance, then requested 29_787_148_000 − deficit
        // 275_190_400 = 29_511_957_600 credits, floored DOWN to the whole
        // duff 29_511_957
        coVerifyOrder {
            shieldedService.withdrawToCore(any(), maxRequested)
            shieldedService.withdrawToCore(any(), Dash(29_511_957L))
        }
        coVerify(exactly = 2) { shieldedService.withdrawToCore(any(), any()) }
    }

    @Test
    fun nonMaxWithdraw_insufficientBalance_isNotSent_withoutAnyRetry() = runTest(dispatcher) {
        coEvery { shieldedService.withdrawToCore(any(), any()) } returns insufficientShieldedResult()
        val executor = executor()

        executor.submit(ShieldedTransferDirection.FromShielded, maxRequested)

        assertTrue(executor.submitState.value is ShieldedSubmitState.NotSent)
        coVerify(exactly = 1) { shieldedService.withdrawToCore(any(), any()) }
    }

    @Test
    fun maxWithdraw_retryAlsoInsufficient_isNotSent_neverAThirdAttempt() = runTest(dispatcher) {
        coEvery { shieldedService.withdrawToCore(any(), any()) } returns insufficientShieldedResult()
        val executor = executor()

        executor.submit(ShieldedTransferDirection.FromShielded, maxRequested, isMaxSpend = true)

        // one shot only: the adjustment never cascades
        assertTrue(executor.submitState.value is ShieldedSubmitState.NotSent)
        coVerify(exactly = 2) { shieldedService.withdrawToCore(any(), any()) }
    }

    @Test
    fun shieldedMaxFeeAdjustment_parsesReasonOrCauseChain_andGuardsNonsense() {
        val requested = Dash(29_787_148L)
        val expected = ShieldedMaxFeeAdjustment(275_190_400L, Dash(29_511_957L))

        // deficit in the cause chain (the classifier's NotBroadcast shape)
        assertEquals(
            expected,
            shieldedMaxFeeAdjustment(requested, insufficientShieldedResult())
        )
        // …or in the reason string itself
        assertEquals(
            expected,
            shieldedMaxFeeAdjustment(requested, SdkWriteResult.NotBroadcast(liveInsufficientMessage))
        )

        // no matching message → no adjustment
        assertNull(shieldedMaxFeeAdjustment(requested, SdkWriteResult.NotBroadcast("flag off")))
        // non-positive deficit → no adjustment
        assertNull(
            shieldedMaxFeeAdjustment(
                requested,
                SdkWriteResult.NotBroadcast("Insufficient shielded balance: available 200, required 100")
            )
        )
        // deficit swallows the whole request → no adjustment
        assertNull(
            shieldedMaxFeeAdjustment(
                Dash(1L),
                SdkWriteResult.NotBroadcast("Insufficient shielded balance: available 0, required 999999999")
            )
        )
    }

    // ── Max-spend fee reserve (ToShielded) ──────────────────────────────

    /**
     * The live incident shape: the user's Max shield failed asset-lock
     * coin selection because the requested amount left no room for the L1
     * fee. Amounts in duffs; note available == required — the message's
     * `required` figure does NOT include the fee, so (unlike FromShielded)
     * no exact deficit can be parsed and the retry must reserve an
     * ESTIMATE sized from the spendable UTXO count.
     */
    private val liveAssetLockShortMessage =
        "shielded fund-from-asset-lock failed: asset lock coin selection is short: " +
            "available 58999510 duffs, required 58999510 duffs"

    private fun assetLockShortResult() = SdkWriteResult.NotBroadcast(
        "pre-broadcast asset-lock coin-selection failure",
        RuntimeException(liveAssetLockShortMessage)
    )

    /** What the user's Max tap requested: the shown wallet balance, whole duffs. */
    private val maxShieldRequested = Dash(58_999_510L)

    @Test
    fun maxShield_selectionShort_retriesOnceWithTheFeeReservedAmount() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returnsMany
            listOf(assetLockShortResult(), SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED))
        every { walletData.spendableUtxoCount() } returns 7
        val executor = executor()

        assertTrue(executor.submit(ShieldedTransferDirection.ToShielded, maxShieldRequested, isMaxSpend = true))

        // one reserve-adjusted retry inside the same operation, then Success
        assertEquals(ShieldedSubmitState.Success, executor.submitState.value)
        // first the full balance, then requested minus the estimated
        // reserve for 7 UTXOs: (7 × 148 + 300) × 2 = 2672 duffs →
        // 58_999_510 − 2_672 = 58_996_838
        coVerifyOrder {
            shieldedService.shieldFromWallet(maxShieldRequested)
            shieldedService.shieldFromWallet(Dash(58_996_838L))
        }
        coVerify(exactly = 2) { shieldedService.shieldFromWallet(any()) }
    }

    @Test
    fun nonMaxShield_selectionShort_isNotSent_withoutAnyRetry() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns assetLockShortResult()
        val executor = executor()

        executor.submit(ShieldedTransferDirection.ToShielded, maxShieldRequested)

        assertTrue(executor.submitState.value is ShieldedSubmitState.NotSent)
        coVerify(exactly = 1) { shieldedService.shieldFromWallet(any()) }
    }

    @Test
    fun maxShield_retryAlsoShort_isNotSent_neverAThirdAttempt() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns assetLockShortResult()
        every { walletData.spendableUtxoCount() } returns 7
        val executor = executor()

        executor.submit(ShieldedTransferDirection.ToShielded, maxShieldRequested, isMaxSpend = true)

        // one shot only: the reserve adjustment never cascades
        assertTrue(executor.submitState.value is ShieldedSubmitState.NotSent)
        coVerify(exactly = 2) { shieldedService.shieldFromWallet(any()) }
    }

    @Test
    fun assetLockMaxFeeReserve_sizesFromTheUtxoCount_withTheMinimumClamp() {
        // 0 UTXOs (or a degenerate negative) → the 1000-duff minimum
        assertEquals(Dash(1_000L), assetLockMaxFeeReserve(0))
        assertEquals(Dash(1_000L), assetLockMaxFeeReserve(-3))
        // 1 UTXO: (148 + 300) × 2 = 896 — still under the clamp
        assertEquals(Dash(1_000L), assetLockMaxFeeReserve(1))
        // typical counts: (n × 148 + 300) × 2
        assertEquals(Dash(2_672L), assetLockMaxFeeReserve(7))
        assertEquals(Dash(30_200L), assetLockMaxFeeReserve(100))
    }

    @Test
    fun isAssetLockSelectionShort_matchesReasonOrCauseChain_only() {
        // in the cause chain (the classifier's NotBroadcast shape)
        assertTrue(isAssetLockSelectionShort(assetLockShortResult()))
        // …or in the reason string itself
        assertTrue(isAssetLockSelectionShort(SdkWriteResult.NotBroadcast(liveAssetLockShortMessage)))
        // anything else never triggers the reserve retry
        assertFalse(isAssetLockSelectionShort(SdkWriteResult.NotBroadcast("flag off")))
        assertFalse(
            isAssetLockSelectionShort(
                SdkWriteResult.NotBroadcast(
                    "pre-broadcast shielded note-selection failure",
                    RuntimeException("Insufficient shielded balance: available 1, required 2")
                )
            )
        )
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
        // the state stays for the screen's own success navigation
        assertEquals(ShieldedSubmitState.Success, executor.submitState.value)
    }

    @Test
    fun foregroundedElsewhere_postsNotification_andAutoAcknowledgesSuccess() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        val executor = executor(foreground = true, transferScreenVisible = false)

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))

        // "We will notify you when it's done" — a durable system
        // notification even while the app is foregrounded (a transient
        // toast was missed live during an activity-recreation gap).
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
        // the success story is told: a still-alive transfer screen must
        // not re-run its success navigation when the user wanders back
        assertEquals(ShieldedSubmitState.Idle, executor.submitState.value)
    }

    @Test
    fun foregroundedElsewhere_failureNotificationKeepsTheStateSticky() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Ambiguous(RuntimeException("timeout"))
        val executor = executor(foreground = true, transferScreenVisible = false)

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))

        verify {
            notificationService.showNotification(
                ShieldedTransferExecutor.NOTIFICATION_TAG,
                str(R.string.shielded_transfer_ambiguous_message),
                str(R.string.shielded_transfer_ambiguous_title),
                null,
                null,
                null
            )
        }
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
        // The visible screen surfaces it and acknowledges (its success
        // navigation) — that receipt is what keeps the executor quiet past
        // the render-confirmation window.
        executor.acknowledge()

        advanceTimeBy(ShieldedTransferExecutor.STALL_TIMEOUT_MS * 2)
        runCurrent()
        // Idle, not Stalled: the watchdog never fired
        assertEquals(ShieldedSubmitState.Idle, executor.submitState.value)
        verify(exactly = 0) {
            notificationService.showNotification(any(), any(), any(), any(), any(), any())
        }
    }

    // ── The activity-recreation gap ─────────────────────────────────────

    @Test
    fun visibleScreenThatNeverRendersTheOutcome_isNotifiedAnyway() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        // the flag says visible, but the composition behind it is gone
        // (activity recreation) so nothing ever acknowledges
        val executor = executor(foreground = true, transferScreenVisible = true)

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
        // nothing yet — the screen gets its confirmation window first
        verify(exactly = 0) {
            notificationService.showNotification(any(), any(), any(), any(), any(), any())
        }

        advanceTimeBy(ShieldedTransferExecutor.RENDER_CONFIRM_GRACE_MS)
        runCurrent()

        verify(exactly = 1) {
            notificationService.showNotification(
                ShieldedTransferExecutor.NOTIFICATION_TAG,
                str(R.string.shielded_notification_success_message),
                str(R.string.shielded_transfer_completed),
                null,
                null,
                null
            )
        }
    }

    @Test
    fun visibleScreenKeepsAStickyOverlay_staysQuiet() = runTest(dispatcher) {
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Ambiguous(RuntimeException("timeout"))
        val executor = executor(foreground = true, transferScreenVisible = true)

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
        advanceTimeBy(ShieldedTransferExecutor.RENDER_CONFIRM_GRACE_MS * 2)
        runCurrent()

        // the overlay is on a screen that is STILL visible — no duplicate
        verify(exactly = 0) {
            notificationService.showNotification(any(), any(), any(), any(), any(), any())
        }
        assertEquals(ShieldedSubmitState.MayHaveGoneThrough, executor.submitState.value)
    }

    @Test
    fun setTransferUiVisible_aStaleInstanceCannotClearANewerScreen() {
        val executor = executor(transferScreenVisible = false)
        val old = Any()
        val new = Any()

        executor.setTransferUiVisible(old, true)
        // recreation: the replacement resumes BEFORE the old instance's
        // onDestroy lands
        executor.setTransferUiVisible(new, true)
        executor.setTransferUiVisible(old, false)
        assertTrue(executor.transferUiVisible)

        executor.setTransferUiVisible(new, false)
        assertFalse(executor.transferUiVisible)
    }

    // ── Background pending-shield completions ───────────────────────────

    @Test
    fun backgroundShieldResume_isAnnouncedAndClearsThePendingState() = runTest(dispatcher) {
        val resumed = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        every { shieldedService.walletShieldResumed } returns resumed
        coEvery { shieldedService.shieldFromWallet(any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
        val executor = executor(foreground = false, transferScreenVisible = false)
        executor.startObservingBackgroundShields()

        executor.submit(ShieldedTransferDirection.ToShielded, Dash.parse("1"))
        assertEquals(ShieldedSubmitState.LockedPendingShield, executor.submitState.value)

        resumed.emit(1)
        runCurrent()

        // the "it will finish automatically" promise is finally answered
        verify(exactly = 1) {
            notificationService.showNotification(
                ShieldedTransferExecutor.NOTIFICATION_TAG,
                str(R.string.shielded_resumed_message),
                str(R.string.shielded_resumed_title),
                null,
                null,
                null
            )
        }
        // …and the stale "do not send it again" overlay is retired
        assertEquals(ShieldedSubmitState.Idle, executor.submitState.value)
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
