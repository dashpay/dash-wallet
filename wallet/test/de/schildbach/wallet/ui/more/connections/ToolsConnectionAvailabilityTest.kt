package de.schildbach.wallet.ui.more.connections

import android.app.Application
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.ui.more.ToolsViewModel
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.WalletDataProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ToolsConnectionAvailabilityTest {
    private val dispatcher = StandardTestDispatcher()
    private val availability = mockk<ConnectIdentityAvailability>()
    private val status = MutableStateFlow(ConnectIdentityStatus.LOADING)
    private lateinit var model: ToolsViewModel
    private var originalSupport = false

    @Before fun setup() {
        originalSupport = Constants.SUPPORTS_PLATFORM
        org.junit.Assume.assumeTrue(Constants.SUPPORTS_CONNECT)
        Constants.SUPPORTS_PLATFORM = true
        Dispatchers.setMain(dispatcher)
        every { availability.observe() } returns status
        val wallet = mockk<WalletDataProvider>(relaxed = true)
        val walletObject = mockk<org.bitcoinj.wallet.Wallet>(relaxed = true)
        every { wallet.wallet } returns walletObject
        val key = mockk<org.bitcoinj.crypto.DeterministicKey>(relaxed = true)
        every { walletObject.watchingKey } returns key
        every { key.serializePubB58(any()) } returns "public-test-key"
        val chain = mockk<BlockchainStateDao>(relaxed = true)
        every { chain.observeState() } returns emptyFlow()
        val identityConfig = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityConfig>(relaxed = true)
        coEvery { identityConfig.get(de.schildbach.wallet.database.entity.BlockchainIdentityConfig.IDENTITY_ID) } returns null
        model = ToolsViewModel(wallet, mockk(relaxed = true), mockk(relaxed = true), chain,
            mockk(relaxed = true), identityConfig, mockk(relaxed = true), availability)
        dispatcher.scheduler.runCurrent()
    }

    @After fun teardown() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        Constants.SUPPORTS_PLATFORM = originalSupport
        Dispatchers.resetMain()
    }

    @Test fun `only confirmed absence hides the Tools recovery entry`() {
        assertTrue(model.uiState.value.showConnections)
        status.value = ConnectIdentityStatus.NO_IDENTITY
        dispatcher.scheduler.runCurrent()
        assertFalse(model.uiState.value.showConnections)
        for (value in listOf(ConnectIdentityStatus.LOADING, ConnectIdentityStatus.UNAVAILABLE, ConnectIdentityStatus.AVAILABLE)) {
            status.value = value
            dispatcher.scheduler.runCurrent()
            assertTrue(model.uiState.value.showConnections)
        }
    }

    @Test fun `returning to Tools rechecks availability instead of retaining a cached hidden entry`() {
        status.value = ConnectIdentityStatus.NO_IDENTITY
        dispatcher.scheduler.runCurrent()
        assertFalse(model.uiState.value.showConnections)
        every { availability.observe() } returns MutableStateFlow(ConnectIdentityStatus.UNAVAILABLE)
        model.refreshConnectionsAvailability()
        assertTrue(model.uiState.value.showConnections)
        dispatcher.scheduler.runCurrent()
        assertTrue(model.uiState.value.showConnections)
    }
}
