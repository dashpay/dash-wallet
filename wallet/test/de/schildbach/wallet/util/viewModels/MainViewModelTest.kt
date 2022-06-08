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
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.data.BlockchainStateDao
import de.schildbach.wallet.ui.main.MainViewModel
import io.mockk.*
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@ExperimentalCoroutinesApi
class MainCoroutineRule(
    private val dispatcher: TestCoroutineDispatcher = TestCoroutineDispatcher()
) : TestWatcher(), TestCoroutineScope by TestCoroutineScope(dispatcher) {
    override fun starting(description: Description) {
        super.starting(description)
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        super.finished(description)
        cleanupTestCoroutines()
        Dispatchers.resetMain()
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
class MainViewModelTest {
    private val configMock = mockk<Configuration>()
    private val blockChainStateMock = mockk<BlockchainStateDao>()
    private val exchangeRatesMock = mockk<ExchangeRatesProvider>()
    private val walletDataMock = mockk<WalletDataProvider>()

    @get:Rule
    var rule: TestRule = InstantTaskExecutorRule()

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    @Before
    fun setup() {
        every { configMock.exchangeCurrencyCode } returns "USD"
        every { configMock.format } returns MonetaryFormat()
        every { configMock.hideBalance } returns false
        every { configMock.registerOnSharedPreferenceChangeListener(any()) } just runs

        every { blockChainStateMock.observeState() } returns flow { BlockchainState() }
        every { exchangeRatesMock.observeExchangeRate(any()) } returns flow { ExchangeRate("USD", "100") }
        every { walletDataMock.observeBalance() } returns flow { Coin.COIN }
    }

    @Test
    fun getClipboardInput_noClip_returnsEmptyString() {
        val clipboardManagerMock = mockk<ClipboardManager>()
        every { clipboardManagerMock.hasPrimaryClip() } returns false

        val viewModel = spyk(MainViewModel(
            mockk(), clipboardManagerMock, configMock, blockChainStateMock,
            exchangeRatesMock, walletDataMock, mockk(), mockk()
        ))

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

        val viewModel = spyk(MainViewModel(
            mockk(), clipboardManagerMock, configMock, blockChainStateMock,
            exchangeRatesMock, walletDataMock, mockk(), mockk()
        ))

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
        every { blockChainStateMock.observeState() } returns MutableStateFlow(BlockchainState(replaying = true))
        val viewModel = spyk(MainViewModel(
            mockk(), mockk(), configMock, blockChainStateMock,
            exchangeRatesMock, walletDataMock, mockk(), mockk()
        ))

        assertEquals(false, viewModel.isBlockchainSynced.value)
        assertEquals(false, viewModel.isBlockchainSyncFailed.value)
    }

    @Test
    fun observeBlockchainState_progress100percent_synced() {
        val state = BlockchainState().apply { replaying = false; percentageSync = 100 }
        every { blockChainStateMock.observeState() } returns MutableStateFlow(state)
        val viewModel = spyk(MainViewModel(
            mockk(), mockk(), configMock, blockChainStateMock,
            exchangeRatesMock, walletDataMock, mockk(), mockk()
        ))

        assertEquals(true, viewModel.isBlockchainSynced.value)
        assertEquals(false, viewModel.isBlockchainSyncFailed.value)
    }
}