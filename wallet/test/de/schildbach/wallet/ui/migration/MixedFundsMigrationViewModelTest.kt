package de.schildbach.wallet.ui.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import de.schildbach.wallet.service.platform.sdk.CoinJoinFundsMigrationService
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome
import de.schildbach.wallet.util.viewModels.MainCoroutineRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MixedFundsMigrationViewModelTest {
    @get:Rule val coroutineRule = MainCoroutineRule()

    @Test
    fun reopeningSheetDuringShieldKeepsProgressAndDoesNotSubmitAgain() = runBlocking {
        val service = mockk<CoinJoinFundsMigrationService>()
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
        coEvery { service.restorePendingShieldMigration() } returns true
        coEvery { service.mixedFundsToMigrate() } returns Coin.COIN
        coEvery { service.observeInFlightMigration() } returns MutableStateFlow(
            de.schildbach.wallet.service.platform.sdk.InFlightMixedFundsMigration(
                de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction.SHIELD,
                System.currentTimeMillis(), 0L
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
        val completion = CompletableDeferred<MixedFundsMigrationOutcome>()
        val markers = MutableStateFlow<de.schildbach.wallet.service.platform.sdk.InFlightMixedFundsMigration?>(null)
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
                System.currentTimeMillis(), 0L
            )
            assertTrue(model.uiState.value.awaitingResult)
            markers.value = null
            completion.complete(MixedFundsMigrationOutcome.STARTED)
            assertFalse(model.uiState.value.inProgress)
            assertTrue(model.uiState.value.resultLanded)
        } finally {
            store.clear()
        }
    }
}
