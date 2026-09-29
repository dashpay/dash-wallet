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

package de.schildbach.wallet.ui.more

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.WorkContinuation
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.database.dao.TransactionMetadataDao
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.platform.work.PublishTransactionMetadataOperation
import de.schildbach.wallet.service.platform.work.TransactionMetadataSaveQueue
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.utils.TransactionMetadataSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The metadata settings screen pops itself right after asking for a save, so
 * the save must keep running after the ViewModel is cleared (D-T1-01), and a
 * save from a reopened screen must not be overtaken by an earlier one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionMetadataSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val saveQueue = TransactionMetadataSaveQueue(applicationScope)

    private val dashPayConfig = mockk<DashPayConfig>(relaxed = true) {
        every { observeTransactionMetadataSettings() } returns flowOf(TransactionMetadataSettings())
        every { observe(DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE) } returns flowOf(null)
        every { observe(DashPayConfig.TRANSACTION_METADATA_SAVE_AFTER) } returns flowOf(null)
        every { observe(DashPayConfig.TRANSACTION_METADATA_SAVE_TO_NETWORK) } returns flowOf(null)
    }
    private val walletUIConfig = mockk<WalletUIConfig> {
        every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns emptyFlow()
    }
    private val transactionMetadataDao = mockk<TransactionMetadataDao> {
        every { observeByTimestampRange(any(), any()) } returns emptyFlow()
    }
    private val platformSyncService = mockk<PlatformSyncService> {
        coEvery { getUnsavedTransactions() } returns Pair(emptyList(), 0L)
    }
    private val continuation = mockk<WorkContinuation> {
        every { enqueue() } returns mockk()
    }
    private val publishOperation = mockk<PublishTransactionMetadataOperation> {
        every { create(any()) } returns continuation
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel() = TransactionMetadataSettingsViewModel(
        walletApplication = mockk<WalletApplication>(),
        dashPayConfig = dashPayConfig,
        walletUIConfig = walletUIConfig,
        exchangeRates = mockk<ExchangeRatesRepository>(),
        analyticsService = mockk<AnalyticsService>(relaxed = true),
        transactionMetadataDao = transactionMetadataDao,
        transactionMetadataChangeCacheDao = mockk<TransactionMetadataChangeCacheDao>(),
        platformSyncService = platformSyncService,
        saveQueue = saveQueue,
        publishOperation = publishOperation
    )

    @Test
    fun saveToNetwork_survivesViewModelClearedMidWrite() = runTest(dispatcher) {
        // Park the save on its first DataStore read, where the old code lost it.
        val gate = CompletableDeferred<Unit>()
        coEvery { dashPayConfig.getTransactionMetadataSettings() } coAnswers {
            gate.await()
            TransactionMetadataSettings()
        }
        val viewModel = viewModel()
        advanceUntilIdle()
        val edited = TransactionMetadataSettings(savePastTxToNetwork = true, saveToNetwork = true)
        viewModel.updatePreferences(edited)

        viewModel.saveToNetwork(forceSave = false)
        advanceUntilIdle()

        // What popBackStack() does to the screen: cancel viewModelScope and run onCleared().
        val viewModelJob = viewModel.viewModelScope.coroutineContext[Job]
        ViewModelStore().apply { put("vm", viewModel) }.clear()
        assertFalse(viewModelJob?.isActive ?: true)

        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            dashPayConfig.setTransactionMetadataSettings(match { it.savePastTxToNetwork && it.saveToNetwork })
        }
        coVerify(exactly = 1) { dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_LAST_SAVE_WORK_ID, any()) }
        verify(exactly = 1) { publishOperation.create(any()) }
        verify(exactly = 1) { continuation.enqueue() }
    }

    @Test
    fun saveToNetwork_runsWhenViewModelIsClearedBeforeTheSaveStarts() = runTest(dispatcher) {
        // MockK resumes a coAnswers suspension even after its caller is
        // cancelled, so the mid-write test above cannot see a save killed by
        // onCleared(). Clearing before the save has started is observable: a
        // save owned by the ViewModel never runs at all.
        coEvery { dashPayConfig.getTransactionMetadataSettings() } returns TransactionMetadataSettings()
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.updatePreferences(TransactionMetadataSettings(savePastTxToNetwork = true))

        viewModel.saveToNetwork(forceSave = false)
        ViewModelStore().apply { put("vm", viewModel) }.clear()
        advanceUntilIdle()

        coVerify(exactly = 1) { dashPayConfig.setTransactionMetadataSettings(any()) }
        verify(exactly = 1) { continuation.enqueue() }
    }

    @Test
    fun saveToNetwork_savesFromTwoScreensRunInSubmissionOrder() = runTest(dispatcher) {
        // The first screen's save parks on its first DataStore read; the
        // reopened screen's save is submitted behind it.
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        coEvery { dashPayConfig.getTransactionMetadataSettings() } coAnswers {
            if (reads++ == 0) gate.await()
            TransactionMetadataSettings()
        }
        val firstScreen = viewModel()
        val secondScreen = viewModel()
        advanceUntilIdle()
        firstScreen.updatePreferences(TransactionMetadataSettings(savePastTxToNetwork = true, saveTaxCategory = false))
        secondScreen.updatePreferences(TransactionMetadataSettings(savePastTxToNetwork = true, saveTaxCategory = true))

        firstScreen.saveToNetwork(forceSave = false)
        advanceUntilIdle()
        secondScreen.saveToNetwork(forceSave = false)
        advanceUntilIdle()

        // the later save must wait for the earlier one, not write past it
        coVerify(exactly = 0) { dashPayConfig.setTransactionMetadataSettings(match { it.saveTaxCategory }) }

        gate.complete(Unit)
        advanceUntilIdle()

        coVerifyOrder {
            dashPayConfig.setTransactionMetadataSettings(match { !it.saveTaxCategory })
            dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_LAST_SAVE_WORK_ID, any())
            dashPayConfig.setTransactionMetadataSettings(match { it.saveTaxCategory })
            dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_LAST_SAVE_WORK_ID, any())
        }
        verify(exactly = 2) { continuation.enqueue() }
    }

    @Test
    fun futureSaveDate_staysUnsetWhenNothingIsStored() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(0L, viewModel.uiState.value.futureSaveDate)
    }

    @Test
    fun unsavedSinceDate_isNullWhenNoDateIsKnown() {
        // e.g. only cached edits, or the unsaved-transaction scan still running
        assertNull(TransactionMetadataSettingsUIState(hasPastTransactionsToSave = true).unsavedSinceDate)
    }

    @Test
    fun unsavedSinceDate_prefersLastSaveDate() {
        val state = TransactionMetadataSettingsUIState(lastSaveDate = 2_000L, firstUnsavedTxDate = 1_000L)

        assertEquals(2_000L, state.unsavedSinceDate)
    }

    @Test
    fun unsavedSinceDate_fallsBackToFirstUnsavedTx() {
        val state = TransactionMetadataSettingsUIState(lastSaveDate = 0L, firstUnsavedTxDate = 1_000L)

        assertEquals(1_000L, state.unsavedSinceDate)
    }
}
