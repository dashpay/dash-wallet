/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.util.viewModels

import android.os.Looper
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import androidx.work.WorkManager
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.Constants
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.EntryPointAccessors
import de.schildbach.wallet.database.AppDatabase
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.transactions.TxFilterType
import androidx.datastore.preferences.core.Preferences
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.main.MainViewModel
import io.mockk.*
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.bitcoinj.core.Coin
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.RateRetrievalState
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainCoroutineRule(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class MainViewModelTest {
    private val configMock = mockk<Configuration>()
    private val exchangeRatesMock = mockk<ExchangeRatesProvider>()
    private val walletApp = mockk<WalletApplication> {
        every { applicationContext } returns mockk()
        every { mainLooper } returns Looper.getMainLooper()
    }
    private val mockIdentityData = BlockchainIdentityBaseData(-1, BlockchainIdentityData.CreationState.NONE, null, null, null, false,null, false)
    private val blockchainIdentityConfigMock = mockk<BlockchainIdentityConfig> {
        coEvery { loadBase() } returns mockIdentityData
        every { observeBase() } returns MutableStateFlow(mockIdentityData)
    }
    private val dashPayProfileDaoMock = mockk<DashPayProfileDao> {
        every { observeByUserId(any()) } returns MutableStateFlow(null)
    }
    private val invitationsDaoMock = mockk<InvitationsDao> {
        coEvery { loadAll() } returns listOf()
    }
    private val appDatabaseMock = mockk<AppDatabase> {
        every { dashPayProfileDao() } returns dashPayProfileDaoMock
        every { dashPayContactRequestDao() } returns mockk()
        every { invitationsDao() } returns invitationsDaoMock
        every { userAlertDao() } returns mockk()
        every { transactionMetadataDocumentDao() } returns mockk()
        every { transactionMetadataCacheDao() } returns mockk()
    }
    private val workManagerMock = mockk<WorkManager> {
        every { getWorkInfosByTagLiveData(any()) } returns MutableLiveData(listOf())
    }
    private val savedStateMock = mockk<SavedStateHandle>()

    private val analyticsService = mockk<AnalyticsService> {
        every { logError(any(), any()) } returns Unit
    }

    private val walletDataMock = mockk<WalletDataProvider> {
        every { wallet } returns null
    }

    private val blockchainStateMock = mockk<BlockchainStateProvider> {
        every { getMasternodeAPY() } returns 5.9
    }

    private val transactionMetadataMock = mockk<TransactionMetadataProvider> {
        every { observePresentableMetadata() } returns MutableStateFlow(mapOf())
    }

    private val mockDashPayConfig = mockk<DashPayConfig> {
        every { observe<Long>(any()) } returns MutableStateFlow(0L)
        coEvery { areNotificationsDisabled() } returns false
    }

    private val uiConfigMock = mockk<WalletUIConfig> {
        every { observe(any<Preferences.Key<Boolean>>()) } returns MutableStateFlow(false)
        every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns MutableStateFlow("USD")
    }

    private val userAgentDaoMock = mockk<UserAlertDao> {
        every { observe(any()) } returns flow { }
    }

    private val platformRepo = mockk<PlatformRepo>()

    @get:Rule
    var rule: TestRule = InstantTaskExecutorRule()

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    @Before
    fun setup() {
        every { configMock.format } returns MonetaryFormat()
        every { configMock.registerOnSharedPreferenceChangeListener(any()) } just runs

        every { blockchainStateMock.observeState() } returns flow { BlockchainState() }
        every { blockchainStateMock.observeSyncStage() } returns MutableStateFlow(PeerGroup.SyncStage.BLOCKS)
        every { exchangeRatesMock.observeExchangeRate(any()) } returns flow { ExchangeRate("USD", "100") }
        every { walletDataMock.observeBalance() } returns flow { Coin.COIN }
        every { walletDataMock.observeMostRecentTransaction() } returns flow {
            Transaction(
                TestNet3Params.get(),
                Constants.HEX.decode(
                    "01000000013511fbb91663e90da67107e1510521440a9bf73878e45549ac169c7cd30c826e010000006a473044022048edae0ab0abcb736ca1a8702c2e99673d7958f4661a4858f437b03a359c0375022023f4a45b8817d9fcdad073cfb43320eae7e064a7873564e4cbc8853da548321a01210359c815be43ce68de8188f02b1b3ecb589fb8facdc2d694104a13bb2a2055f5ceffffffff0240420f00000000001976a9148017fd8d70d8d4b8ddb289bb73bcc0522bc06e0888acb9456900000000001976a914c9e6676121e9f38c7136188301a95d800ceade6588ac00000000" // ktlint-disable max-line-length
                ),
                0
            )
        }
        every { walletDataMock.observeMostRecentTransaction() } returns flow {
            Transaction(
                TestNet3Params.get(),
                Constants.HEX.decode(
                    "01000000013511fbb91663e90da67107e1510521440a9bf73878e45549ac169c7cd30c826e010000006a473044022048edae0ab0abcb736ca1a8702c2e99673d7958f4661a4858f437b03a359c0375022023f4a45b8817d9fcdad073cfb43320eae7e064a7873564e4cbc8853da548321a01210359c815be43ce68de8188f02b1b3ecb589fb8facdc2d694104a13bb2a2055f5ceffffffff0240420f00000000001976a9148017fd8d70d8d4b8ddb289bb73bcc0522bc06e0888acb9456900000000001976a914c9e6676121e9f38c7136188301a95d800ceade6588ac00000000" // ktlint-disable max-line-length
                ),
                0
            )
        }
        every { exchangeRatesMock.observeStaleRates(any()) } returns flow { RateRetrievalState(false, false, false) }
        mockkStatic(WalletApplication::class)
        every { WalletApplication.getInstance() } returns walletApp

        val mockPlatformRepoEntryPoint = mockk<PlatformRepo.PlatformRepoEntryPoint> {
            every { provideAppDatabase() } returns appDatabaseMock
        }
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(walletApp, PlatformRepo.PlatformRepoEntryPoint::class.java)
        } returns mockPlatformRepoEntryPoint

        mockkObject(PlatformRepo.Companion)
        //every { PlatformRepo.Companion.getInstance() } returns mockk {
        //    every { walletApplication } returns walletApp
        //}

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManagerMock
        every { savedStateMock.get<TxFilterType>(eq("tx_direction")) } returns TxFilterType.ALL
        every { savedStateMock.set<TxFilterType>(any(), any()) } just runs
    }

    @Test
    fun observeBlockchainState_replaying_notSynced() {
        every { blockchainStateMock.observeState() } returns MutableStateFlow(BlockchainState(replaying = true))

        val viewModel = spyk(
            MainViewModel(
                analyticsService, configMock, uiConfigMock,
                exchangeRatesMock, walletDataMock, walletApp, platformRepo,
                mockk(), mockk(), blockchainIdentityConfigMock, savedStateMock, transactionMetadataMock,
                blockchainStateMock, mockk(), mockk(), mockk(), userAgentDaoMock, mockk(), mockDashPayConfig, mockk(), mockk()
            )
        )

        runBlocking(viewModel.viewModelWorkerScope.coroutineContext) {
            assertEquals(false, viewModel.isBlockchainSynced.value)
            assertEquals(false, viewModel.isBlockchainSyncFailed.value)
        }
    }

    @Test
    @Ignore("Unreliable test. Needs investigating")
    fun observeBlockchainState_progress100percent_synced() {
        val state = BlockchainState().apply { replaying = false; percentageSync = 100 }
        every { blockchainStateMock.observeState() } returns MutableStateFlow(state)
        val viewModel = spyk(
            MainViewModel(
                analyticsService, configMock, uiConfigMock,
                exchangeRatesMock, walletDataMock, walletApp, platformRepo,
                mockk(), mockk(), blockchainIdentityConfigMock, savedStateMock, transactionMetadataMock,
                blockchainStateMock, mockk(), mockk(), mockk(), mockk(), mockk(), mockDashPayConfig, mockk(), mockk()
            )
        )

        runBlocking(viewModel.viewModelWorkerScope.coroutineContext) {
            assertEquals(true, viewModel.isBlockchainSynced.value)
            assertEquals(false, viewModel.isBlockchainSyncFailed.value)
        }
    }
}
