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

package de.schildbach.wallet.ui.dashpay.utils

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.database.dao.TransactionMetadataDao
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.platform.work.PublishTransactionMetadataOperation
import de.schildbach.wallet.service.platform.work.TransactionMetadataSaveQueue
import de.schildbach.wallet.ui.more.TransactionMetadataSettingsViewModel
import de.schildbach.wallet.ui.more.TxMetadataSaveFrequency
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * [DashPayConfig.setTransactionMetadataSettings] against a REAL preferences
 * DataStore (Robolectric context): the seven settings keys must land in one
 * transaction, so [DashPayConfig.observeTransactionMetadataSettings] never
 * emits a half-written combination.
 *
 * Collectors run on [Dispatchers.Unconfined], so they are resumed inside the
 * DataStore's own state update and see every committed state — a per-key
 * write would show up as intermediate emissions rather than being conflated
 * away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class DashPayConfigTransactionMetadataSettingsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // Tracked so tearDown can clear each ViewModel BEFORE resetMain(): its
    // viewModelScope collectors never complete on their own, and one that
    // outlives the test would touch a reset Main in a later test.
    private val createdViewModels = mutableListOf<TransactionMetadataSettingsViewModel>()

    private fun config() = DashPayConfig(
        RuntimeEnvironment.getApplication(),
        mockk<WalletDataProvider>(relaxed = true)
    )

    /** what the observed flow carries, minus its per-emission clock default */
    private fun TransactionMetadataSettings.persisted() = copy(saveAfterTimestamp = 0L)

    @After
    fun tearDown() {
        createdViewModels.forEach { vm ->
            val scopeJob = vm.viewModelScope.coroutineContext[Job]
            // the real lifecycle path (cancels viewModelScope and runs
            // onCleared()), then join so cancellation has finished before resetMain()
            ViewModelStore().apply { put("vm", vm) }.clear()
            scopeJob?.let { runBlocking { it.join() } }
        }
        createdViewModels.clear()
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun setTransactionMetadataSettings_emitsOnlyTheBeforeAndAfterStates() = runBlocking {
        val config = config()
        val before = config.observeTransactionMetadataSettings().first().persisted()
        // every field differs from the empty store, so each per-key write would
        // be a distinct intermediate state
        val target = TransactionMetadataSettings(
            saveToNetwork = true,
            saveFrequency = TxMetadataSaveFrequency.oncePerWeek,
            savePaymentCategory = true,
            saveTaxCategory = true,
            saveExchangeRates = true,
            savePrivateMemos = true,
            saveGiftcardInfo = true,
            saveAfterTimestamp = 0L
        )
        assertFalse(before.isEqual(target))

        val emissions = Collections.synchronizedList(mutableListOf<TransactionMetadataSettings>())
        scope.launch {
            config.observeTransactionMetadataSettings().collect { emissions.add(it.persisted()) }
        }
        withTimeout(5_000) { while (emissions.isEmpty()) delay(10) }

        config.setTransactionMetadataSettings(target)
        withTimeout(5_000) { while (emissions.last() != target) delay(10) }

        val unexpected = synchronized(emissions) { emissions.filter { it != before && it != target } }
        assertTrue("half-written settings were emitted: $unexpected", unexpected.isEmpty())
        assertEquals(target, config.getTransactionMetadataSettings().copy(saveAfterTimestamp = 0L))
    }

    /**
     * The review scenario end to end: screen A queued "save to network + memos";
     * a reopened screen B has edited only "save to network" when A's save lands.
     * B's draft must survive, still marked modified so Save stays enabled.
     */
    @Test
    fun earlierScreensSave_doesNotOverwriteReopenedScreensDraft() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val config = config()
        val viewModel = viewModel(config)
        val persisted = withTimeout(5_000) {
            config.observeTransactionMetadataSettings().first()
        }
        withTimeout(5_000) { viewModel.uiState.first { it.settings.isEqual(persisted) } }

        val draftB = persisted.copy(saveToNetwork = true)
        viewModel.updatePreferences(draftB)
        assertTrue(viewModel.uiState.value.settings.modified)

        // what screen A's queued save runs once it gets its turn
        config.setTransactionMetadataSettings(persisted.copy(saveToNetwork = true, savePrivateMemos = true))
        withTimeout(5_000) { config.observeTransactionMetadataSettings().first { it.savePrivateMemos } }

        val settings = viewModel.uiState.value.settings
        assertTrue("draft B was replaced: $settings", settings.isEqual(draftB))
        assertTrue("draft B no longer reads as modified", settings.modified)
    }

    private fun viewModel(config: DashPayConfig) = TransactionMetadataSettingsViewModel(
        walletApplication = mockk<WalletApplication>(),
        dashPayConfig = config,
        walletUIConfig = mockk<WalletUIConfig> {
            every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns emptyFlow()
        },
        exchangeRates = mockk<ExchangeRatesRepository>(),
        analyticsService = mockk<AnalyticsService>(relaxed = true),
        transactionMetadataDao = mockk<TransactionMetadataDao> {
            every { observeByTimestampRange(any(), any()) } returns emptyFlow()
        },
        transactionMetadataChangeCacheDao = mockk<TransactionMetadataChangeCacheDao>(),
        platformSyncService = mockk<PlatformSyncService> {
            coEvery { getUnsavedTransactions() } returns Pair(emptyList(), 0L)
        },
        saveQueue = TransactionMetadataSaveQueue(scope),
        publishOperation = mockk<PublishTransactionMetadataOperation>()
    ).also { createdViewModels.add(it) }
}
