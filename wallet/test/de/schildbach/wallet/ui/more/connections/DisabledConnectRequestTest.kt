package de.schildbach.wallet.ui.more.connections

import android.app.Application
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.IdentityRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.bitcoinj.core.NetworkParameters
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DisabledConnectRequestTest {
    private val dispatcher = StandardTestDispatcher()
    private val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
    private val repository = mockk<DashConnectRepository>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val network = Constants.NETWORK_PARAMETERS
    private val originalNetworkId = network.id
    private var originalSupportsPlatform = false
    private var model: ConnectionsViewModel? = null

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        originalSupportsPlatform = Constants.SUPPORTS_PLATFORM
        Constants.SUPPORTS_PLATFORM = true
        every { repository.observeConnections() } returns MutableStateFlow(emptyList())
        every { identities.initializationResult } returns MutableStateFlow<Result<Unit>?>(null)
    }

    @After fun teardown() {
        model?.viewModelScope?.cancel()
        applicationScope.cancel()
        ReflectionHelpers.setField(network, "id", originalNetworkId)
        Constants.SUPPORTS_PLATFORM = originalSupportsPlatform
        Dispatchers.resetMain()
    }

    private fun rejectsBeforeInitialization(openedFromUrl: Boolean, purchase: Boolean) {
        for (networkId in listOf(NetworkParameters.ID_MAINNET, NetworkParameters.ID_TESTNET)) {
            // Test mainnet alone on non-production flavors, and production alone/on mainnet in prod.
            if (!Constants.IS_PROD_BUILD && networkId != NetworkParameters.ID_MAINNET) continue
            ReflectionHelpers.setField(network, "id", networkId)
            model?.viewModelScope?.cancel()
            val current = ConnectionsViewModel(repository, identities, applicationScope)
            model = current
            current.bindWalletUnlockCheck { true }
            val uri = if (purchase) "dash-st:2?n=d&v=1" else "dash-key:2?n=d&v=2"
            repeat(2) {
                current.resetScanOutcome()
                if (openedFromUrl) current.onUrlOpened(uri) else current.onQrScanned(uri)
                dispatcher.scheduler.runCurrent()
                assertTrue(current.uiState.value.featureUnavailable)
                assertFalse(current.uiState.value.isLoading)
                assertNull(current.uiState.value.pendingRequest)
                assertNull(current.uiState.value.pendingPurchase)
                assertEquals("App Connect is unavailable in production or on mainnet",
                    (current.scanOutcome.value as ConnectionsViewModel.ScanOutcome.Error).message)
            }
        }
        verify(exactly = 0) { identities.initializationResult }
        coVerify(exactly = 0) { repository.parseQr(any()) }
        coVerify(exactly = 0) { repository.prepareLogin(any()) }
        coVerify(exactly = 0) { repository.prepareTokenPurchase(any()) }
    }

    @Test fun `disabled network rejects scanned login before identity initialization`() =
        rejectsBeforeInitialization(openedFromUrl = false, purchase = false)

    @Test fun `disabled network rejects login URL before identity initialization`() =
        rejectsBeforeInitialization(openedFromUrl = true, purchase = false)

    @Test fun `disabled network rejects scanned purchase before identity initialization`() =
        rejectsBeforeInitialization(openedFromUrl = false, purchase = true)

    @Test fun `disabled network rejects purchase URL before identity initialization`() =
        rejectsBeforeInitialization(openedFromUrl = true, purchase = true)
}
