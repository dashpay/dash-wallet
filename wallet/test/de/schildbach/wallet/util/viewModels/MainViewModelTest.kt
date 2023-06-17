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

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import de.schildbach.wallet.transactions.TxFilterType
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletUIConfig
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
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
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

    private val uiConfigMock = mockk<WalletUIConfig> {
        every { observePreference(any<Preferences.Key<Boolean>>()) } returns MutableStateFlow(false)
    }

    @get:Rule
    var rule: TestRule = InstantTaskExecutorRule()

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    @Before
    fun setup() {
        every { configMock.exchangeCurrencyCode } returns "USD"
        every { configMock.format } returns MonetaryFormat()
        every { configMock.registerOnSharedPreferenceChangeListener(any()) } just runs

        every { blockchainStateMock.observeState() } returns flow { BlockchainState() }
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

        every { savedStateMock.get<TxFilterType>(eq("tx_direction")) } returns TxFilterType.ALL
        every { savedStateMock.set<TxFilterType>(any(), any()) } just runs
    }

    @Test
    fun getClipboardInput_noClip_returnsEmptyString() {
        val clipboardManagerMock = mockk<ClipboardManager>()
        every { clipboardManagerMock.hasPrimaryClip() } returns false

        val viewModel = spyk(
            MainViewModel(
                analyticsService, clipboardManagerMock, configMock, uiConfigMock,
                exchangeRatesMock, walletDataMock, savedStateMock, transactionMetadataMock,
                blockchainStateMock, mockk()
            )
        )

        val clipboardInput = viewModel.getClipboardInput()
        assertEquals("", clipboardInput)
    }

    @Test
    fun getClipboardInput_returnsCorrectText() {
        val mockUri = "mock://example.uri"
        val mockText = "some text"
        val clipboardManagerMock = mockk<ClipboardManager>()
        val clipDescription = mockk<ClipDescription>()

        every { clipboardManagerMock.hasPrimaryClip() } returns true
        every { clipboardManagerMock.primaryClip?.description } returns clipDescription

        val viewModel = spyk(
            MainViewModel(
                analyticsService, clipboardManagerMock, configMock, uiConfigMock,
                exchangeRatesMock, walletDataMock, savedStateMock, transactionMetadataMock,
                blockchainStateMock, mockk()
            )
        )

        every { clipboardManagerMock.primaryClip?.getItemAt(0)?.uri?.toString() } returns mockUri
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) } returns true
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) } returns false
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) } returns false

        var clipboardInput = viewModel.getClipboardInput()
        assertEquals(mockUri, clipboardInput)

        every { clipboardManagerMock.primaryClip?.getItemAt(0)?.text?.toString() } returns mockText
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) } returns false
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) } returns true
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) } returns false

        clipboardInput = viewModel.getClipboardInput()
        assertEquals(mockText, clipboardInput)

        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) } returns false
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) } returns false
        every { clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) } returns true

        clipboardInput = viewModel.getClipboardInput()
        assertEquals(mockText, clipboardInput)
    }

    @Test
    fun observeBlockchainState_replaying_notSynced() {
        every { blockchainStateMock.observeState() } returns MutableStateFlow(BlockchainState(replaying = true))
        val viewModel = spyk(
            MainViewModel(
                analyticsService, mockk(), configMock, uiConfigMock,
                exchangeRatesMock, walletDataMock, savedStateMock, transactionMetadataMock,
                blockchainStateMock, mockk()
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
                analyticsService, mockk(), configMock, uiConfigMock,
                exchangeRatesMock, walletDataMock, savedStateMock, transactionMetadataMock,
                blockchainStateMock, mockk()
            )
        )

        runBlocking(viewModel.viewModelWorkerScope.coroutineContext) {
            assertEquals(true, viewModel.isBlockchainSynced.value)
            assertEquals(false, viewModel.isBlockchainSyncFailed.value)
        }
    }
}
