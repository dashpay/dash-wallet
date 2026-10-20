package de.schildbach.wallet.ui.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import de.schildbach.wallet.service.platform.sdk.InFlightMixedFundsMigration
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction
import de.schildbach.wallet.service.platform.sdk.CoinJoinFundsMigrationService
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome
import de.schildbach.wallet.service.platform.sdk.MixedFundsRoutes
import de.schildbach.wallet.util.viewModels.MainCoroutineRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MixedFundsMigrationViewModelTest {
    @get:Rule val coroutineRule = MainCoroutineRule()

    /** A relaxed service offering [Coin.COIN] in the choice, nothing pending. */
    private fun choiceService(
        markers: MutableStateFlow<InFlightMixedFundsMigration?> = MutableStateFlow(null),
        routes: MixedFundsRoutes = MixedFundsRoutes(shieldOperable = true, combineOperable = true)
    ) = mockk<CoinJoinFundsMigrationService>(relaxed = true).also { service ->
        coEvery { service.routeAvailability() } returns routes
        coEvery { service.restorePendingShieldMigration() } returns false
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.observeInFlightMigration() } returns markers
    }

    @Test
    fun reopeningSheetDuringShieldKeepsProgressAndDoesNotSubmitAgain() = runBlocking {
        val service = mockk<CoinJoinFundsMigrationService>()
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        val completion = CompletableDeferred<MixedFundsMigrationOutcome>()
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.restorePendingShieldMigration() } returns false
        coEvery { service.observeInFlightMigration() } returns MutableStateFlow(null)
        coEvery { service.shieldMixedFunds(any()) } coAnswers { completion.await() }
        val activityStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MixedFundsMigrationViewModel(service) as T
            }
        }
        try {
            val model = ViewModelProvider(activityStore, factory)[MixedFundsMigrationViewModel::class.java]
            val dialogObserver = launch { model.uiState.collect {} }
            model.shieldMixedFunds()
            assertTrue(model.uiState.value.inProgress)

            // Auto-lock destroys the sheet's observer, not the activity store.
            dialogObserver.cancelAndJoin()
            val reopened = ViewModelProvider(activityStore, factory)[MixedFundsMigrationViewModel::class.java]
            assertSame(model, reopened)
            assertTrue(reopened.uiState.value.inProgress)
            reopened.shieldMixedFunds()
            coVerify(exactly = 1) { service.shieldMixedFunds(any()) }

            completion.complete(MixedFundsMigrationOutcome.STARTED)
            assertFalse(reopened.uiState.value.inProgress)
        } finally {
            activityStore.clear()
        }
    }

    @Test
    fun restoredPendingShieldDisablesBothMigrationActions() {
        val service = mockk<CoinJoinFundsMigrationService>()
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        every { service.verifiedLandings } returns 0
        coEvery { service.restorePendingShieldMigration() } returns true
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.observeInFlightMigration() } returns MutableStateFlow(
            de.schildbach.wallet.service.platform.sdk.InFlightMixedFundsMigration(
                de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction.SHIELD,
                System.currentTimeMillis()
            )
        )
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            assertTrue(model.uiState.value.awaitingResult)
            model.shieldMixedFunds()
            model.combineIntoUnmixedBalance()
            coVerify(exactly = 0) { service.shieldMixedFunds(any()) }
            coVerify(exactly = 0) { service.combineIntoUnmixedBalance() }
        } finally {
            store.clear()
        }
    }

    @Test
    fun shieldCreditLandingBeforeFundingReturnsShowsSuccess() = runBlocking {
        val service = mockk<CoinJoinFundsMigrationService>()
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        val completion = CompletableDeferred<MixedFundsMigrationOutcome>()
        val markers = MutableStateFlow<de.schildbach.wallet.service.platform.sdk.InFlightMixedFundsMigration?>(null)
        var landings = 0
        every { service.verifiedLandings } answers { landings }
        coEvery { service.restorePendingShieldMigration() } returns false
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.observeInFlightMigration() } returns markers
        coEvery { service.shieldMixedFunds(any()) } coAnswers { completion.await() }
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            model.shieldMixedFunds()
            markers.value = de.schildbach.wallet.service.platform.sdk.InFlightMixedFundsMigration(
                de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction.SHIELD,
                System.currentTimeMillis()
            )
            assertTrue(model.uiState.value.awaitingResult)
            landings++ // the watcher verified the credit before clearing
            markers.value = null
            completion.complete(MixedFundsMigrationOutcome.STARTED)
            assertFalse(model.uiState.value.inProgress)
            assertTrue(model.uiState.value.resultLanded)
        } finally {
            store.clear()
        }
    }

    @Test
    fun laterDefersChoiceWithoutStartingASpend() = runBlocking {
        val service = choiceService()
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            model.deferMigration()
            coVerify(exactly = 1) { service.deferMigration(Coin.COIN) }
            coVerify(exactly = 0) { service.shieldMixedFunds(any()) }
            coVerify(exactly = 0) { service.combineIntoUnmixedBalance() }
        } finally { store.clear() }
    }

    @Test
    fun timedOutShieldRetriesExistingLockOnly() {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        coEvery { service.restorePendingShieldMigration() } returns true
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.observeInFlightMigration() } returns MutableStateFlow(
            de.schildbach.wallet.service.platform.sdk.InFlightMixedFundsMigration(
                de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction.SHIELD, 1L
            )
        )
        coEvery { service.retryPendingMigration() } returns MixedFundsMigrationOutcome.UNCONFIRMED
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            assertTrue(model.uiState.value.resultTimedOut)
            model.retryMigration(authenticatedMove = null)
            coVerify(exactly = 1) { service.retryPendingMigration() }
            coVerify(exactly = 0) { service.shieldMixedFunds(any()) }
            assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, model.uiState.value.outcome)
        } finally { store.clear() }
    }

    @Test
    fun timedOutRegularTransferResumesSavedMigrationOnly() {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        coEvery { service.restorePendingShieldMigration() } returns true
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.observeInFlightMigration() } returns MutableStateFlow(
            de.schildbach.wallet.service.platform.sdk.InFlightMixedFundsMigration(
                de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction.COMBINE, 1L
            )
        )
        coEvery { service.retryPendingMigration() } returns MixedFundsMigrationOutcome.UNCONFIRMED
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            assertTrue(model.uiState.value.resultTimedOut)
            model.retryMigration(authenticatedMove = null)
            coVerify(exactly = 1) { service.retryPendingMigration() }
            coVerify(exactly = 0) { service.combineIntoUnmixedBalance() }
            coVerify(exactly = 0) { service.shieldMixedFunds(any()) }
        } finally { store.clear() }
    }

    /**
     * The activity-scoped ViewModel's first read runs at MainActivity
     * creation, usually pre-sync (null). The sheet raised later by the
     * post-sync prompt gate refreshes it, so the offered amount comes from
     * the same post-sync read as the prompt decision (field bug: amount null,
     * both choices disabled).
     */
    @Test
    fun refreshAmountReplacesThePreSyncNullRead() {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        var available: Coin? = null
        coEvery { service.restorePendingShieldMigration() } returns false
        coEvery { service.mixedFundsToMigrate() } answers { available }
        coEvery { service.observeInFlightMigration() } returns MutableStateFlow(null)
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            assertNull(model.uiState.value.amount)
            assertTrue(model.uiState.value.amountChecked)

            available = Coin.COIN
            model.refreshAmount()
            assertEquals(Coin.COIN, model.uiState.value.amount)
            assertTrue(model.uiState.value.amountChecked)
            assertFalse(model.uiState.value.migrationPending)

            // The funds moved elsewhere: the sheet must see "nothing to offer".
            available = null
            model.refreshAmount()
            assertNull(model.uiState.value.amount)
            assertTrue(model.uiState.value.amountChecked)
        } finally { store.clear() }
    }

    @Test
    fun refreshAmountFlagsAPendingMigrationSoTheSheetStaysUp() {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        coEvery { service.restorePendingShieldMigration() } returns true
        coEvery { service.mixedFundsToMigrate() } returns null
        coEvery { service.observeInFlightMigration() } returns MutableStateFlow(null)
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            assertTrue(model.uiState.value.amountChecked)
            assertTrue(model.uiState.value.migrationPending)
        } finally { store.clear() }
    }

    private fun marker(action: MixedFundsMigrationAction = MixedFundsMigrationAction.SHIELD, startedAt: Long = System.currentTimeMillis()) =
        InFlightMixedFundsMigration(action, startedAt)

    @Test
    fun rejectionClearedMarkerIsNeverReportedAsSuccess() = runBlocking {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        val markers = MutableStateFlow<InFlightMixedFundsMigration?>(null)
        val completion = CompletableDeferred<MixedFundsMigrationOutcome>()
        coEvery { service.restorePendingShieldMigration() } returns false
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.observeInFlightMigration() } returns markers
        coEvery { service.shieldMixedFunds(any()) } coAnswers { completion.await() }
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            model.shieldMixedFunds()
            markers.value = marker()
            completion.complete(MixedFundsMigrationOutcome.NOT_ATTEMPTED)
            // The rejection's clear is collected only after the call returned.
            markers.value = null
            assertFalse(model.uiState.value.resultLanded)
            assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, model.uiState.value.outcome)
            // A later set/cleared cycle with no verified landing stays honest too.
            markers.value = marker()
            markers.value = null
            assertFalse(model.uiState.value.resultLanded)
        } finally { store.clear() }
    }

    @Test
    fun repeatedNullMarkerEmissionsDoNotFakeALanding() = runBlocking {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        val emissions = kotlinx.coroutines.flow.MutableSharedFlow<InFlightMixedFundsMigration?>(replay = 1)
        var landings = 0
        every { service.verifiedLandings } answers { landings }
        coEvery { service.restorePendingShieldMigration() } returns true
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.observeInFlightMigration() } returns emissions
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            emissions.emit(marker())
            // Cleared without a verified landing (e.g. reconciled), then re-emitted.
            emissions.emit(null)
            emissions.emit(null)
            assertFalse(model.uiState.value.resultLanded)
            // A real, verified landing is still reported once.
            emissions.emit(marker())
            landings++
            emissions.emit(null)
            assertTrue(model.uiState.value.resultLanded)
        } finally { store.clear() }
    }

    @Test
    fun unconfirmedGuidanceIsClosableAndRetriesOnlyTheExistingTransaction() {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        coEvery { service.restorePendingShieldMigration() } returns false
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        val markers = MutableStateFlow<InFlightMixedFundsMigration?>(null)
        coEvery { service.observeInFlightMigration() } returns markers
        coEvery { service.shieldMixedFunds(any()) } coAnswers {
            markers.value = marker() // the early marker stays: outcome unknown
            MixedFundsMigrationOutcome.UNCONFIRMED
        }
        coEvery { service.retryPendingMigration() } returns MixedFundsMigrationOutcome.UNCONFIRMED
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            model.shieldMixedFunds()
            val state = model.uiState.value
            assertTrue(state.awaitingResult)
            val shown = state.presentation()
            assertTrue(shown.terminalGuidance)
            assertFalse(shown.processing)
            assertTrue(shown.showClose)
            assertTrue(shown.showRestart)
            assertFalse(shown.showTransferUnmixed)
            assertFalse(state.retryStartsFreshSpend())

            model.retryMigration(authenticatedMove = null)
            coVerify(exactly = 1) { service.retryPendingMigration() }
            coVerify(exactly = 1) { service.shieldMixedFunds(any()) }
        } finally { store.clear() }
    }

    @Test
    fun timedOutWhileTheNativeCallIsStillRunningCanBeClosedButNotRetried() {
        val state = MixedFundsMigrationUIState(
            amount = Coin.COIN, amountChecked = true, inProgress = true,
            attemptedAction = MixedFundsMigrationAction.SHIELD, resultTimedOut = true
        )
        val shown = state.presentation()
        assertTrue(shown.terminalGuidance)
        assertFalse(shown.processing)
        assertTrue(shown.showClose)
        assertFalse(shown.showRestart)
        // Plain in-flight processing still cannot be dismissed.
        val processing = state.copy(resultTimedOut = false).presentation()
        assertTrue(processing.processing)
        assertFalse(processing.showClose)
        assertFalse(processing.cancelable)
    }

    @Test
    fun definitiveShieldFailureOffersTheWalletFallbackAndClosingReturnsToTheChoice() {
        val service = choiceService()
        coEvery { service.shieldMixedFunds(any()) } returns MixedFundsMigrationOutcome.NOT_ATTEMPTED
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            model.shieldMixedFunds()
            val failed = model.uiState.value
            assertTrue(failed.presentation().showTransferUnmixed)
            assertTrue(failed.presentation().showRestart)
            assertTrue(failed.retryStartsFreshSpend())

            model.onSheetClosed()
            val reopened = model.uiState.value
            assertNull(reopened.outcome)
            assertTrue(reopened.presentation().choiceMode)
        } finally { store.clear() }
    }

    /**
     * Shielding open but the Combine send gate shut: the choice offers Shield
     * only, and a definitive shield failure offers Try again without the
     * wallet-balance (Combine) fallback. The reverse hides Shield and a failed
     * shield's Try again, while still offering the fallback.
     */
    @Test
    fun inoperableRouteIsNeverOfferedInTheChoiceOrAsAFallback() {
        val service = choiceService(routes = MixedFundsRoutes(shieldOperable = true, combineOperable = false))
        coEvery { service.shieldMixedFunds(any()) } returns MixedFundsMigrationOutcome.NOT_ATTEMPTED
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            val choice = model.uiState.value.presentation()
            assertTrue(choice.choiceMode)
            assertTrue(choice.showShieldChoice)
            assertFalse(choice.showCombineChoice)

            model.shieldMixedFunds()
            val failed = model.uiState.value.presentation()
            assertTrue(failed.failed)
            assertTrue(failed.showRestart)
            assertFalse(failed.showTransferUnmixed)

            // The routes flip (re-read on the next showing): Combine only.
            coEvery { service.routeAvailability() } returns
                MixedFundsRoutes(shieldOperable = false, combineOperable = true)
            model.refreshAmount()
            val flipped = model.uiState.value.presentation()
            assertTrue(flipped.failed)
            assertFalse(flipped.showRestart)
            assertTrue(flipped.showTransferUnmixed)
            model.onSheetClosed()
            val reopened = model.uiState.value.presentation()
            assertTrue(reopened.choiceMode)
            assertFalse(reopened.showShieldChoice)
            assertTrue(reopened.showCombineChoice)

            // An unreadable availability offers neither route.
            coEvery { service.routeAvailability() } throws IllegalStateException("probe failed")
            model.refreshAmount()
            val unknown = model.uiState.value.presentation()
            assertFalse(unknown.showShieldChoice)
            assertFalse(unknown.showCombineChoice)
        } finally { store.clear() }
    }

    /** A FAILED marker written later by the watcher, and its clearing, re-read the routes. */
    @Test
    fun laterFailedMarkerAndItsClearingUseCurrentRoutes() {
        val markers = MutableStateFlow<InFlightMixedFundsMigration?>(null)
        val service = choiceService(markers)
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            val now = System.currentTimeMillis()
            markers.value = InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, now)
            // The send gate closes while the migration runs; the watcher then fails it.
            coEvery { service.routeAvailability() } returns
                MixedFundsRoutes(shieldOperable = true, combineOperable = false)
            markers.value = markers.value?.copy(failed = true)
            val failed = model.uiState.value.presentation()
            assertTrue(failed.failed)
            assertTrue(failed.showRestart)
            assertFalse(failed.showTransferUnmixed)
            // Released: the choice is offered on the routes as they are now.
            coEvery { service.routeAvailability() } returns
                MixedFundsRoutes(shieldOperable = false, combineOperable = true)
            markers.value = null
            assertFalse(model.uiState.value.shieldOperable)
            assertTrue(model.uiState.value.combineOperable)
        } finally { store.clear() }
    }

    /** Try again runs only in the mode the sheet confirmed: a fresh spend only when authenticated for that move. */
    @Test
    fun retryRunsOnlyTheConfirmedMode() {
        val service = choiceService()
        coEvery { service.shieldMixedFunds(any()) } returns MixedFundsMigrationOutcome.NOT_ATTEMPTED
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            model.shieldMixedFunds()
            assertTrue(model.uiState.value.retryStartsFreshSpend())
            // Confirmed as a resume, or authenticated for the other move: nothing runs.
            model.retryMigration(authenticatedMove = null)
            model.retryMigration(authenticatedMove = MixedFundsMigrationAction.COMBINE)
            coVerify(exactly = 1) { service.shieldMixedFunds(any()) }
            coVerify(exactly = 0) { service.retryPendingMigration() }
            coVerify(exactly = 0) { service.combineIntoUnmixedBalance() }
            // Authenticated for the failed move: it runs again.
            model.retryMigration(authenticatedMove = MixedFundsMigrationAction.SHIELD)
            coVerify(exactly = 2) { service.shieldMixedFunds(any()) }
        } finally { store.clear() }
    }

    @Test
    fun persistedDefinitiveFailureAfterConsolidationPresentsTheFallback() {
        val service = mockk<CoinJoinFundsMigrationService>(relaxed = true)
        coEvery { service.routeAvailability() } returns MixedFundsRoutes(shieldOperable = true, combineOperable = true)
        coEvery { service.restorePendingShieldMigration() } returns true
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        // The watcher recorded the shield's definitive failure; the sheet opens on it later.
        coEvery { service.observeInFlightMigration() } returns MutableStateFlow(
            InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis(), failed = true)
        )
        coEvery { service.releaseFailedMigration() } returns true
        val store = ViewModelStore()
        val model = MixedFundsMigrationViewModel(service)
        store.put("migration", model)
        try {
            val state = model.uiState.value
            assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, state.outcome)
            assertFalse(state.awaitingResult)
            assertFalse(state.resultTimedOut)
            val presentation = state.presentation()
            assertTrue(presentation.failed)
            assertFalse(presentation.terminalGuidance)
            assertTrue(presentation.showTransferUnmixed)
            // Try again is a fresh (authenticated) spend, not a resume of the failed stage.
            assertTrue(state.retryStartsFreshSpend())

            model.onSheetClosed()
            assertTrue(model.uiState.value.presentation().choiceMode)
            coVerify(exactly = 1) { service.releaseFailedMigration() }
        } finally { store.clear() }
    }
}
