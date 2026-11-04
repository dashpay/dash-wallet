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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DisabledConnectRequestTest {
    private val dispatcher = StandardTestDispatcher()
    private val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
    private val repository = mockk<DashConnectRepository>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
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
        Constants.SUPPORTS_PLATFORM = originalSupportsPlatform
        Dispatchers.resetMain()
    }

    private fun rejectsBeforeInitialization(openedFromUrl: Boolean, purchase: Boolean) {
        // A network still below Platform protocol version 14, on any network or flavour.
        coEvery { repository.isAvailable() } returns false
        val current = ConnectionsViewModel(repository, identities, applicationScope, mockk(relaxed = true))
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
            assertEquals("DashConnect requires Platform protocol version 14",
                (current.scanOutcome.value as ConnectionsViewModel.ScanOutcome.Error).message)
        }
        verify(exactly = 0) { identities.initializationResult }
        coVerify(exactly = 0) { repository.parseQr(any()) }
        coVerify(exactly = 0) { repository.prepareLogin(any()) }
        coVerify(exactly = 0) { repository.prepareTokenPurchase(any()) }
    }

    @Test fun `network below protocol 14 rejects scanned login before identity initialization`() =
        rejectsBeforeInitialization(openedFromUrl = false, purchase = false)

    @Test fun `network below protocol 14 rejects login URL before identity initialization`() =
        rejectsBeforeInitialization(openedFromUrl = true, purchase = false)

    @Test fun `network below protocol 14 rejects scanned purchase before identity initialization`() =
        rejectsBeforeInitialization(openedFromUrl = false, purchase = true)

    @Test fun `network below protocol 14 rejects purchase URL before identity initialization`() =
        rejectsBeforeInitialization(openedFromUrl = true, purchase = true)
}
