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
package de.schildbach.wallet.ui.migration

import androidx.lifecycle.ViewModelStore
import de.schildbach.wallet.service.platform.sdk.CoinJoinFundsMigrationService
import de.schildbach.wallet.service.platform.sdk.InFlightMixedFundsMigration
import de.schildbach.wallet.service.platform.sdk.MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction.COMBINE
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction.SHIELD
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome.NOT_ATTEMPTED
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome.STARTED
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome.UNCONFIRMED
import de.schildbach.wallet.util.viewModels.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The mixed-funds sheet's outcome under EVERY ordering of (persisted marker
 * emission, initiating/retry call completion). The persisted marker is the
 * authority: a definitive failure observed at any point ends in the failure
 * presentation (with the wallet-balance fallback after a shield), never in
 * processing or success; only a VERIFIED landing is success; an uncertain
 * result is "could not confirm", never a fresh-spend offer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MixedFundsMigrationOrderingMatrixTest {
    @get:Rule val coroutineRule = MainCoroutineRule()

    /** What the user sees — derived only from [MixedFundsMigrationUIState.presentation]. */
    enum class Shown { CHOICE, PROCESSING, FAILURE_WITH_FALLBACK, FAILURE_RESTART_ONLY, GUIDANCE, SUCCESS }

    private fun MixedFundsMigrationUIState.shown(): Shown {
        val p = presentation()
        return when {
            resultLanded -> Shown.SUCCESS
            p.processing -> Shown.PROCESSING
            p.failed && p.showRestart && p.showTransferUnmixed -> Shown.FAILURE_WITH_FALLBACK
            p.failed && p.showRestart -> Shown.FAILURE_RESTART_ONLY
            p.terminalGuidance -> Shown.GUIDANCE
            p.choiceMode -> Shown.CHOICE
            else -> error("unclassified presentation $p for $this")
        }
    }

    // ── Pure reducer table ────────────────────────────────────────────

    private data class ReducerRow(val name: String, val facts: MixedFundsMigrationFacts, val expected: Shown)

    private fun f(
        marker: MarkerPhase = MarkerPhase.ABSENT,
        action: MixedFundsMigrationAction? = SHIELD,
        running: Boolean = false,
        result: MixedFundsMigrationOutcome? = null,
        seen: Boolean = false,
        landed: Boolean = false
    ) = MixedFundsMigrationFacts(marker, action, running, result, seen, landed)

    @Test
    fun reducerTable() {
        val rows = listOf(
            // A definitive (marker) failure beats every call result once no call runs.
            ReducerRow("failed marker, retry returned STARTED", f(MarkerPhase.FAILED, result = STARTED, seen = true), Shown.FAILURE_WITH_FALLBACK),
            ReducerRow("failed marker, call returned UNCONFIRMED", f(MarkerPhase.FAILED, result = UNCONFIRMED, seen = true), Shown.FAILURE_WITH_FALLBACK),
            ReducerRow("failed marker, call returned NOT_ATTEMPTED", f(MarkerPhase.FAILED, result = NOT_ATTEMPTED, seen = true), Shown.FAILURE_WITH_FALLBACK),
            ReducerRow("failed marker, no call (opened fresh / restart)", f(MarkerPhase.FAILED), Shown.FAILURE_WITH_FALLBACK),
            ReducerRow("failed COMBINE marker: restart only", f(MarkerPhase.FAILED, action = COMBINE, result = STARTED), Shown.FAILURE_RESTART_ONLY),
            ReducerRow("failed marker while the call still runs: held", f(MarkerPhase.FAILED, running = true, seen = true), Shown.PROCESSING),
            // Direct (no marker) failures.
            ReducerRow("direct shield NOT_ATTEMPTED", f(result = NOT_ATTEMPTED), Shown.FAILURE_WITH_FALLBACK),
            ReducerRow("direct combine NOT_ATTEMPTED", f(action = COMBINE, result = NOT_ATTEMPTED), Shown.FAILURE_RESTART_ONLY),
            ReducerRow("stale pending marker cannot overrule… until it clears", f(MarkerPhase.PENDING, result = NOT_ATTEMPTED, seen = true), Shown.PROCESSING),
            // Landing.
            ReducerRow("verified landing after STARTED", f(result = STARTED, seen = true, landed = true), Shown.SUCCESS),
            ReducerRow("verified landing while running: not yet", f(running = true, seen = true, landed = true), Shown.PROCESSING),
            ReducerRow("cleared without verified landing is not success", f(result = STARTED, seen = true), Shown.CHOICE),
            ReducerRow("STARTED before its marker is observed", f(result = STARTED), Shown.PROCESSING),
            // Pending / uncertain / expired.
            ReducerRow("pending after STARTED", f(MarkerPhase.PENDING, result = STARTED, seen = true), Shown.PROCESSING),
            ReducerRow("pending with no call (opened mid-flight)", f(MarkerPhase.PENDING), Shown.PROCESSING),
            ReducerRow("pending after UNCONFIRMED", f(MarkerPhase.PENDING, result = UNCONFIRMED, seen = true), Shown.GUIDANCE),
            ReducerRow("expired after STARTED", f(MarkerPhase.EXPIRED, result = STARTED, seen = true), Shown.GUIDANCE),
            ReducerRow("expired, no call", f(MarkerPhase.EXPIRED), Shown.GUIDANCE),
            ReducerRow("UNCONFIRMED, no marker", f(result = UNCONFIRMED), Shown.GUIDANCE),
            ReducerRow("nothing at all", f(action = null), Shown.CHOICE)
        )
        for (row in rows) {
            val view = row.facts.reduce()
            val state = MixedFundsMigrationUIState(
                amount = Coin.COIN, amountChecked = true,
                inProgress = view.inProgress, outcome = view.outcome, attemptedAction = view.attemptedAction,
                awaitingResult = view.awaitingResult, resultTimedOut = view.resultTimedOut, resultLanded = view.resultLanded
            )
            assertEquals(row.name, row.expected, state.shown())
        }
    }

    // ── ViewModel ordering matrix ─────────────────────────────────────

    private sealed interface Step {
        /** Start the row's call (shield / combine / retry from the guidance). */
        object Call : Step

        /** The persisted marker changes ([landing]: the watcher verified a landing before clearing). */
        data class Marker(val marker: InFlightMixedFundsMigration?, val landing: Boolean = false) : Step

        /** The suspended call returns. */
        data class Complete(val outcome: MixedFundsMigrationOutcome) : Step

        /** The user closes the sheet. */
        object Close : Step
    }

    private enum class Kind { SHIELD, COMBINE, RETRY }

    private data class Row(
        val name: String,
        val kind: Kind,
        val steps: List<Step>,
        val expected: Shown,
        val initial: InFlightMixedFundsMigration? = null,
        val amount: Coin = Coin.COIN,
        val releaseSucceeds: Boolean = true
    )

    private fun pending(action: MixedFundsMigrationAction = SHIELD, at: Long = System.currentTimeMillis()) =
        InFlightMixedFundsMigration(action, at, 0L)
    private fun expired(action: MixedFundsMigrationAction = SHIELD) =
        InFlightMixedFundsMigration(action, System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 1_000L, 0L)
    private fun failed(action: MixedFundsMigrationAction = SHIELD) =
        InFlightMixedFundsMigration(action, System.currentTimeMillis(), 0L, failed = true)

    private val call = Step.Call
    private fun mark(m: InFlightMixedFundsMigration?, landing: Boolean = false) = Step.Marker(m, landing)
    private fun done(o: MixedFundsMigrationOutcome) = Step.Complete(o)

    private val matrix: List<Row> by lazy {
        val p = pending()
        val p2 = pending(at = p.startedAtMillis + 1)
        listOf(
            // ── Definitive failure: consolidation-stage shield NotBroadcast,
            //    consolidation tx NotBroadcast, nothing left to batch — all
            //    persist the failed marker; every ordering vs the call.
            Row("consolidating shield: pending, FAILED, then call STARTED", Kind.SHIELD,
                listOf(call, mark(p), mark(failed()), done(STARTED)), Shown.FAILURE_WITH_FALLBACK),
            Row("consolidating shield: pending, call STARTED, then FAILED", Kind.SHIELD,
                listOf(call, mark(p), done(STARTED), mark(failed())), Shown.FAILURE_WITH_FALLBACK),
            Row("consolidating shield: call STARTED, then pending, then FAILED", Kind.SHIELD,
                listOf(call, done(STARTED), mark(p), mark(failed())), Shown.FAILURE_WITH_FALLBACK),
            Row("consolidating shield: FAILED straight away, then call STARTED", Kind.SHIELD,
                listOf(call, mark(failed()), done(STARTED)), Shown.FAILURE_WITH_FALLBACK),
            Row("consolidating shield: FAILED while the call is still suspended", Kind.SHIELD,
                listOf(call, mark(p), mark(failed())), Shown.PROCESSING),
            Row("consolidating combine: pending, FAILED, then call STARTED (no fallback)", Kind.COMBINE,
                listOf(call, mark(pending(COMBINE)), mark(failed(COMBINE)), done(STARTED)), Shown.FAILURE_RESTART_ONLY),
            Row("consolidating combine: call STARTED, then FAILED", Kind.COMBINE,
                listOf(call, done(STARTED), mark(pending(COMBINE)), mark(failed(COMBINE))), Shown.FAILURE_RESTART_ONLY),
            // The reviewed race: retry from the guidance, watcher fails the
            // stage while resumePendingWalletShieldsOnUserRequest is suspended.
            Row("retry: refreshed, FAILED, then retry returns STARTED", Kind.RETRY,
                listOf(call, mark(p), mark(failed()), done(STARTED)), Shown.FAILURE_WITH_FALLBACK, initial = expired()),
            Row("retry: FAILED before the window refresh is seen, then STARTED", Kind.RETRY,
                listOf(call, mark(failed()), done(STARTED)), Shown.FAILURE_WITH_FALLBACK, initial = expired()),
            Row("retry: refreshed, retry returns STARTED, then FAILED", Kind.RETRY,
                listOf(call, mark(p), done(STARTED), mark(failed())), Shown.FAILURE_WITH_FALLBACK, initial = expired()),
            Row("retry: FAILED, then retry returns NOT_ATTEMPTED", Kind.RETRY,
                listOf(call, mark(failed()), done(NOT_ATTEMPTED)), Shown.FAILURE_WITH_FALLBACK, initial = expired()),
            Row("retry: FAILED, then retry throws (UNCONFIRMED)", Kind.RETRY,
                listOf(call, mark(failed()), done(UNCONFIRMED)), Shown.FAILURE_WITH_FALLBACK, initial = expired()),
            Row("combine retry: FAILED, then STARTED", Kind.RETRY,
                listOf(call, mark(pending(COMBINE)), mark(failed(COMBINE)), done(STARTED)), Shown.FAILURE_RESTART_ONLY,
                initial = expired(COMBINE)),
            // Direct shield NotBroadcast: the service clears its marker.
            Row("direct shield: pending, NOT_ATTEMPTED, then cleared", Kind.SHIELD,
                listOf(call, mark(p), done(NOT_ATTEMPTED), mark(null)), Shown.FAILURE_WITH_FALLBACK),
            Row("direct shield: pending, cleared, then NOT_ATTEMPTED", Kind.SHIELD,
                listOf(call, mark(p), mark(null), done(NOT_ATTEMPTED)), Shown.FAILURE_WITH_FALLBACK),
            Row("direct shield: NOT_ATTEMPTED before any marker", Kind.SHIELD,
                listOf(call, done(NOT_ATTEMPTED)), Shown.FAILURE_WITH_FALLBACK),
            Row("direct combine: NOT_ATTEMPTED", Kind.COMBINE,
                listOf(call, done(NOT_ATTEMPTED)), Shown.FAILURE_RESTART_ONLY),
            Row("amount does not cover the fee reserve", Kind.SHIELD,
                listOf(call), Shown.FAILURE_WITH_FALLBACK,
                amount = Coin.valueOf(CoinJoinFundsMigrationService.L1_FEE_RESERVE_DUFFS)),
            // Sheet opened fresh / app restarted on a failed marker; sheet open during failure.
            Row("opened fresh on a failed shield marker", Kind.SHIELD, listOf(), Shown.FAILURE_WITH_FALLBACK, initial = failed()),
            Row("restart on a failed combine marker", Kind.COMBINE, listOf(), Shown.FAILURE_RESTART_ONLY, initial = failed(COMBINE)),
            Row("open while processing, then FAILED", Kind.SHIELD, listOf(mark(failed())), Shown.FAILURE_WITH_FALLBACK, initial = p),
            Row("open on expired guidance, then FAILED", Kind.SHIELD, listOf(mark(failed())), Shown.FAILURE_WITH_FALLBACK, initial = expired()),
            // onSheetClosed after a definitive failure.
            Row("close a released failure: back to the choice", Kind.SHIELD, listOf(Step.Close), Shown.CHOICE, initial = failed()),
            Row("close a failure that cannot be released: still the failure", Kind.SHIELD, listOf(Step.Close),
                Shown.FAILURE_WITH_FALLBACK, initial = failed(), releaseSucceeds = false),
            Row("close a direct failure: back to the choice", Kind.SHIELD, listOf(call, done(NOT_ATTEMPTED), Step.Close), Shown.CHOICE),
            // Not definitive: the watcher waits for unlock (marker refreshed, never failed).
            Row("signer locked in the watcher: still processing", Kind.SHIELD,
                listOf(call, mark(p), done(STARTED), mark(p2)), Shown.PROCESSING),
            // ── Landed (verified only), every ordering.
            Row("landed before the call returns", Kind.SHIELD,
                listOf(call, mark(p), mark(null, landing = true), done(STARTED)), Shown.SUCCESS),
            Row("landed after the call returns", Kind.SHIELD,
                listOf(call, mark(p), done(STARTED), mark(null, landing = true)), Shown.SUCCESS),
            Row("call returns before its marker, then lands", Kind.COMBINE,
                listOf(call, done(STARTED), mark(pending(COMBINE)), mark(null, landing = true)), Shown.SUCCESS),
            Row("retry lands before it returns", Kind.RETRY,
                listOf(call, mark(p), mark(null, landing = true), done(STARTED)), Shown.SUCCESS, initial = expired()),
            Row("retry lands after it returns", Kind.RETRY,
                listOf(call, mark(p), done(STARTED), mark(null, landing = true)), Shown.SUCCESS, initial = expired()),
            Row("cleared WITHOUT a verified landing is never success", Kind.SHIELD,
                listOf(call, mark(p), done(STARTED), mark(null)), Shown.CHOICE),
            Row("a later FAILED after a landing-free clear is the failure", Kind.SHIELD,
                listOf(call, mark(p), mark(null), mark(failed()), done(STARTED)), Shown.FAILURE_WITH_FALLBACK),
            // ── Unconfirmed / timed out, every ordering.
            Row("UNCONFIRMED with its marker set first", Kind.SHIELD,
                listOf(call, mark(p), done(UNCONFIRMED)), Shown.GUIDANCE),
            Row("UNCONFIRMED before its marker", Kind.SHIELD,
                listOf(call, done(UNCONFIRMED), mark(p)), Shown.GUIDANCE),
            Row("call throws (UNCONFIRMED), no marker", Kind.COMBINE,
                listOf(call, done(UNCONFIRMED)), Shown.GUIDANCE),
            Row("STARTED, then the window lapses", Kind.SHIELD,
                listOf(call, mark(p), done(STARTED), mark(expired())), Shown.GUIDANCE),
            Row("window lapses while the call is still suspended, then STARTED", Kind.SHIELD,
                listOf(call, mark(p), mark(expired()), done(STARTED)), Shown.GUIDANCE),
            Row("retry returns UNCONFIRMED on the expired marker", Kind.RETRY,
                listOf(call, done(UNCONFIRMED)), Shown.GUIDANCE, initial = expired()),
            Row("retry returns STARTED, then lapses again", Kind.RETRY,
                listOf(call, mark(p), done(STARTED), mark(expired())), Shown.GUIDANCE, initial = expired()),
            Row("retry returns STARTED with its window open: processing", Kind.RETRY,
                listOf(call, mark(p), done(STARTED)), Shown.PROCESSING, initial = expired())
        )
    }

    @Test
    fun orderingMatrix() {
        for (row in matrix) {
            assertEquals(row.name, row.expected, runRow(row))
        }
    }

    private fun runRow(row: Row): Shown {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        val markers = MutableStateFlow(row.initial)
        var landings = 0
        var completion = CompletableDeferred<MixedFundsMigrationOutcome>()
        every { service.verifiedLandings } answers { landings }
        coEvery { service.restorePendingShieldMigration() } answers { markers.value != null }
        coEvery { service.mixedFundsToMigrate() } returns row.amount
        coEvery { service.observeInFlightMigration() } returns markers
        coEvery { service.inFlightMigration() } answers { markers.value }
        coEvery { service.shieldMixedFunds(any()) } coAnswers { completion.await() }
        coEvery { service.combineIntoUnmixedBalance() } coAnswers { completion.await() }
        coEvery { service.retryPendingMigration() } coAnswers { completion.await() }
        coEvery { service.releaseFailedMigration() } answers {
            if (row.releaseSucceeds && markers.value?.failed == true) markers.value = null
            row.releaseSucceeds
        }
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            for (step in row.steps) {
                when (step) {
                    Step.Call -> when (row.kind) {
                        Kind.SHIELD -> model.shieldMixedFunds()
                        Kind.COMBINE -> model.combineIntoUnmixedBalance()
                        Kind.RETRY -> {
                            assertEquals("${row.name}: retry is offered", Shown.GUIDANCE, model.uiState.value.shown())
                            model.retryMigration()
                        }
                    }
                    is Step.Marker -> {
                        if (step.landing) landings++
                        markers.value = step.marker
                    }
                    is Step.Complete -> completion.complete(step.outcome)
                    Step.Close -> model.onSheetClosed()
                }
            }
            // Never a fresh spend from a resume, and never two calls.
            if (row.kind == Kind.RETRY) {
                coVerify(exactly = 0) { service.shieldMixedFunds(any()) }
                coVerify(exactly = 0) { service.combineIntoUnmixedBalance() }
            }
            coVerify(exactly = 0) { service.clearInFlightMigration() }
            return model.uiState.value.shown()
        } finally {
            store.clear()
        }
    }

    // ── Closing the "could not confirm" guidance ──────────────────────

    @Test
    fun closingTimedOutGuidanceAcknowledgesWithoutClearingTheRecord() {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        coEvery { service.restorePendingShieldMigration() } returns true
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.observeInFlightMigration() } returns MutableStateFlow(expired())
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            assertEquals(Shown.GUIDANCE, model.uiState.value.shown())
            model.onSheetClosed()
            coVerify(exactly = 1) { service.acknowledgeUnconfirmedMigration() }
            coVerify(exactly = 0) { service.clearInFlightMigration() }
            coVerify(exactly = 0) { service.releaseFailedMigration() }
            // Still recoverable when reopened from Tools: guidance with a resume-only Try again.
            assertEquals(Shown.GUIDANCE, model.uiState.value.shown())
        } finally {
            store.clear()
        }
    }

    @Test
    fun closingProcessingOrFailureDoesNotAcknowledge() {
        for (initial in listOf(pending(), failed())) {
            val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
            coEvery { service.restorePendingShieldMigration() } returns true
            coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
            coEvery { service.observeInFlightMigration() } returns MutableStateFlow(initial)
            val store = ViewModelStore()
            val model = MixedFundsMigrationViewModel(service)
            store.put("migration", model)
            try {
                model.onSheetClosed()
                coVerify(exactly = 0) { service.acknowledgeUnconfirmedMigration() }
            } finally {
                store.clear()
            }
        }
    }

    /**
     * Expired guidance is closable while the native call is still suspended.
     * Closing it then must be acknowledged (not swallowed by the running-call
     * guard), and the unlock path must not bring it back — while a genuinely
     * in-progress window (and a failure) still re-presents.
     */
    @Test
    fun closingGuidanceDuringASuspendedCallAcknowledgesAndUnlockDoesNotRepresentIt() = runBlocking {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        val markers = MutableStateFlow<InFlightMixedFundsMigration?>(null)
        var acknowledged = false
        coEvery { service.restorePendingShieldMigration() } answers { markers.value != null }
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.observeInFlightMigration() } returns markers
        coEvery { service.inFlightMigration() } answers { markers.value }
        // The native call never returns within this test.
        coEvery { service.shieldMixedFunds(any()) } coAnswers { CompletableDeferred<MixedFundsMigrationOutcome>().await() }
        coEvery { service.acknowledgeUnconfirmedMigration() } answers { acknowledged = true }
        coEvery { service.isUnconfirmedMigrationAcknowledged() } answers { acknowledged }
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            model.shieldMixedFunds()
            markers.value = pending()
            assertTrue(model.uiState.value.inProgress)
            assertEquals(Shown.PROCESSING, model.uiState.value.shown())
            assertTrue("in-progress processing re-presents on unlock", model.shouldRepresentAfterUnlock())

            // The honesty window passes while the call is still suspended.
            markers.value = expired()
            assertTrue("the call is still running", model.uiState.value.inProgress)
            assertEquals(Shown.GUIDANCE, model.uiState.value.shown())
            assertTrue("not yet closed: the guidance is re-presented", model.shouldRepresentAfterUnlock())

            model.onSheetClosed()
            coVerify(exactly = 1) { service.acknowledgeUnconfirmedMigration() }
            coVerify(exactly = 0) { service.clearInFlightMigration() }
            coVerify(exactly = 0) { service.releaseFailedMigration() }
            assertFalse("closed guidance must not return on unlock", model.shouldRepresentAfterUnlock())
            // The record is kept: still guidance, still blocking, still resumable.
            assertEquals(Shown.GUIDANCE, model.uiState.value.shown())

            // A new window (Try again / signer-locked retry) is in progress again: presented.
            markers.value = pending()
            assertEquals(Shown.PROCESSING, model.uiState.value.shown())
            assertTrue(model.shouldRepresentAfterUnlock())

            // A failure is never acknowledged away.
            markers.value = failed()
            model.onSheetClosed()
            coVerify(exactly = 1) { service.acknowledgeUnconfirmedMigration() }
            assertTrue(model.shouldRepresentAfterUnlock())
        } finally {
            store.clear()
        }
    }
}
