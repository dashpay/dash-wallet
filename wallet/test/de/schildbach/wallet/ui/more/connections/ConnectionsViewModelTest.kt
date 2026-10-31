package de.schildbach.wallet.ui.more.connections

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.more.connections.protocol.DashConnectUri
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.ui.compose_views.ApproveConnectionDialog
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ConnectionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
    private val repository = mockk<DashConnectRepository>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private val identityConfig = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityConfig>(relaxed = true)
    private val auditConfig = mockk<DashConnectConfig>(relaxed = true)
    private val walletData = mockk<org.dash.wallet.common.WalletDataProvider>(relaxed = true)
    private fun availability(owner: IdentityRepository = identities) =
        ConnectIdentityAvailability(owner, identityConfig, auditConfig, walletData)
    private lateinit var model: ConnectionsViewModel
    private var originalSupportsPlatform = false
    private val vector = JsonParser.parseReader(javaClass.getResourceAsStream("/app-connect-vectors.json")!!.reader()).asJsonObject
    private val decoded = DashConnectUri.parseKeyRequest(vector.getAsJsonObject("request")["uri"].asString)
    private val request = decoded.copy(appConnect = decoded.appConnect!!.copy(callback = java.net.URI("https://example.org/return")))
    private val groupId = org.dashj.platform.dpp.identifier.Identifier.from(request.appConnect!!.auth.first().id)
    private val group = org.dashj.platform.dpp.contract.VerifiedContractGroup(groupId, groupId, emptySet(), null, null,
        emptySet(), emptySet(), emptySet())
    private val plan = AppConnectPlan("hash", "scope", "identity", request.appConnect!!.auth, "Consent",
        groupScopes = mapOf(groupId.toString() to group))

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        originalSupportsPlatform = Constants.SUPPORTS_PLATFORM
        org.junit.Assume.assumeFalse(Constants.IS_PROD_BUILD ||
            Constants.NETWORK_PARAMETERS.id == org.bitcoinj.core.NetworkParameters.ID_MAINNET)
        Constants.SUPPORTS_PLATFORM = true
        every { repository.observeConnections() } returns MutableStateFlow(emptyList())
        every { identities.blockchainIdentity } returns null
        every { identities.blockchainIdentityFlow } returns MutableStateFlow(null)
        every { identities.initializationResult } returns MutableStateFlow(Result.success(Unit))
        every { identityConfig.observeIdentityPresence() } returns MutableStateFlow(true)
        every { auditConfig.observeHasRecords() } returns MutableStateFlow(false)
        every { walletData.wallet } returns mockk(relaxed = true)
        coEvery { repository.parseQr(any()) } returns DashConnectQr(request)
        coEvery { repository.prepareLogin(any()) } returns plan
        coEvery { repository.resolveWalletUsername() } returns "Alice"
        coEvery { repository.resolveAppDisplay(any(), any()) } returns AppDisplay("App", "example.org")
        coEvery { repository.approvePreparedLogin(any(), any()) } throws IllegalStateException("Confirmation uncertain")
        coEvery { repository.revokePreparedLogin(any(), any()) } returns Unit
        model = ConnectionsViewModel(repository, identities, applicationScope, availability())
        model.bindWalletUnlockCheck { true }
        model.onForegroundChanged(true)
    }

    @After fun teardown() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        applicationScope.cancel()
        Constants.SUPPORTS_PLATFORM = originalSupportsPlatform
        Dispatchers.resetMain()
    }

    private fun assertUnsupportedRequestRejected(openedFromUrl: Boolean, purchase: Boolean) {
        model.viewModelScope.cancel()
        Constants.SUPPORTS_PLATFORM = false
        every { identities.initializationResult } returns MutableStateFlow<Result<Unit>?>(null)
        clearMocks(identities, answers = false)
        model = ConnectionsViewModel(repository, identities, applicationScope, availability())
        model.bindWalletUnlockCheck { true }
        model.onForegroundChanged(true)
        val uri = if (purchase) "dash-st:2?n=d&v=1" else vector.getAsJsonObject("request")["uri"].asString
        repeat(2) {
            model.resetScanOutcome()
            if (openedFromUrl) model.onUrlOpened(uri) else model.onQrScanned(uri)
            dispatcher.scheduler.runCurrent()
            assertTrue(model.uiState.value.featureUnavailable)
            assertFalse(model.uiState.value.isLoading)
            assertNull(model.uiState.value.pendingRequest)
            assertNull(model.uiState.value.pendingPurchase)
            assertEquals("App Connect is unavailable on this device",
                (model.scanOutcome.value as ConnectionsViewModel.ScanOutcome.Error).message)
        }
        verify(exactly = 0) { identities.initializationResult }
        coVerify(exactly = 0) { repository.parseQr(any()) }
        coVerify(exactly = 0) { repository.prepareLogin(any()) }
        coVerify(exactly = 0) { repository.prepareTokenPurchase(any()) }
    }

    @Test fun `unsupported device rejects scanned login without waiting for initialization`() =
        assertUnsupportedRequestRejected(openedFromUrl = false, purchase = false)

    @Test fun `unsupported device rejects login URL without waiting for initialization`() =
        assertUnsupportedRequestRejected(openedFromUrl = true, purchase = false)

    @Test fun `unsupported device rejects scanned purchase without waiting for initialization`() =
        assertUnsupportedRequestRejected(openedFromUrl = false, purchase = true)

    @Test fun `unsupported device rejects purchase URL without waiting for initialization`() =
        assertUnsupportedRequestRejected(openedFromUrl = true, purchase = true)

    @Test fun `resume refresh replaces observer and reprojects unchanged stored records`() {
        model.viewModelScope.cancel()
        var activeObservers = 0
        var status = ConnectionStatus.ACTIVE
        every { repository.observeConnections() } answers {
            kotlinx.coroutines.flow.flow {
                activeObservers++
                try {
                    emit(listOf(DAppConnection("app", "App", "", status, 1)))
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    activeObservers--
                }
            }
        }
        model = ConnectionsViewModel(repository, identities, applicationScope, availability())
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionStatus.ACTIVE, model.uiState.value.connections.single().status)
        status = ConnectionStatus.APPROVED
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionStatus.APPROVED, model.uiState.value.connections.single().status)
        assertEquals(1, activeObservers)
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, activeObservers)
        model.viewModelScope.cancel()
        dispatcher.scheduler.runCurrent()
        assertEquals(0, activeObservers)
    }

    private fun scan(openedFromUrl: Boolean = false) {
        if (openedFromUrl) model.onUrlOpened("qr") else model.onQrScanned("qr")
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun assertLegacyLoginRejected(openedFromUrl: Boolean) {
        coEvery { repository.parseQr(any()) } coAnswers {
            DashConnectQr(DashConnectUri.parseKeyRequest(firstArg()))
        }
        val bytes = request.requireAppConnect().bytes.copyOf().also { it[0] = 1 }
        val uri = "dash-key:${org.bitcoinj.core.Base58.encode(bytes)}?n=d&v=1"
        if (openedFromUrl) model.onUrlOpened(uri) else model.onQrScanned(uri)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DashConnectUri.LEGACY_KEY_REQUEST_ERROR,
            (model.scanOutcome.value as ConnectionsViewModel.ScanOutcome.Error).message)
        assertNull(model.uiState.value.pendingRequest)
        assertNull(model.uiState.value.pendingPurchase)
        assertNull(model.callbackOffer.value)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { repository.prepareLogin(any()) }
        coVerify(exactly = 0) { repository.approvePreparedLogin(any(), any()) }
        coVerify(exactly = 0) { repository.prepareTokenPurchase(any()) }
    }

    @Test fun `legacy scanner login is rejected without preparing or approving a grant`() = assertLegacyLoginRejected(false)
    @Test fun `legacy URL login is rejected without preparing or approving a grant`() = assertLegacyLoginRejected(true)

    private fun failApproval(openedFromUrl: Boolean = false) {
        scan(openedFromUrl)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Error)
        assertTrue(model.uiState.value.requiresRevocation)
    }

    @Test fun `uncertain publication refreshes to persisted recovery plan before deliberate retry`() {
        scan(openedFromUrl = true)
        val recovered = plan.copy(recovering = true, previousGroupScopes = plan.groupScopes)
        coEvery { repository.prepareLogin(any()) } returns recovered
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.pendingRequest!!.recovering)
        assertFalse(model.uiState.value.pendingRequest!!.requiresGroupReapproval)
        assertEquals(recovered.choices(), model.uiState.value.pendingRequest!!.grantChoices)
        assertTrue(model.uiState.value.requiresRevocation)
        assertNull(model.callbackOffer.value)
        coVerify(exactly = 1) { repository.approvePreparedLogin(request, plan) }
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.approvePreparedLogin(request, recovered) }
    }

    @Test fun `uncertain publication still requires acknowledgement of actual group changes`() {
        scan()
        val changed = plan.copy(recovering = true, previousGroupScopes = plan.groupScopes,
            groupScopes = mapOf(groupId.toString() to group.copy(adminIds = setOf(groupId))))
        coEvery { repository.prepareLogin(any()) } returns changed
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.pendingRequest!!.requiresGroupReapproval)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.approvePreparedLogin(any(), any()) }
        model.approveGroupScopeChange(true)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.approvePreparedLogin(request, changed.copy(groupScopeChangeApproved = true)) }
    }

    @Test fun `failed recovery refresh blocks stale retry but retains revocation`() {
        scan()
        coEvery { repository.prepareLogin(any()) } throws IllegalStateException("Proof unavailable")
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.approvePreparedLogin(any(), any()) }
        assertTrue(model.uiState.value.requiresRevocation)
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.revokePreparedLogin(request, plan) }
    }

    @Test fun `changed group refreshes consent without automatically approving or opening callback`() {
        scan(openedFromUrl = true)
        val refreshed = plan.copy(permissionText = "Updated verified scope",
            groupScopes = mapOf(groupId.toString() to group.copy(adminIds = setOf(groupId))))
        coEvery { repository.approvePreparedLogin(any(), any()) } throws GroupScopeChangedException()
        coEvery { repository.prepareLogin(any()) } returns refreshed
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(refreshed.permissionText, model.uiState.value.pendingRequest!!.permissionText)
        assertEquals(refreshed.choices(), model.uiState.value.pendingRequest!!.grantChoices)
        assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Error)
        assertFalse(model.uiState.value.requiresRevocation)
        assertNull(model.callbackOffer.value)
        coVerify(exactly = 1) { repository.approvePreparedLogin(request, plan) }
        // A second deliberate tap is required, using the newly displayed scope.
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.approvePreparedLogin(request, refreshed) }
    }

    @Test fun `fresh refreshed consent remains editable while persisted recovery stays frozen`() {
        for (recovering in listOf(false, true)) {
            model.closePendingRequest()
            dispatcher.scheduler.runCurrent()
            scan()
            val refreshed = plan.copy(recovering = recovering, blockTime = System.currentTimeMillis())
            coEvery { repository.approvePreparedLogin(any(), any()) } throws GroupScopeChangedException()
            coEvery { repository.prepareLogin(any()) } returns refreshed
            model.approvePendingRequest()
            dispatcher.scheduler.runCurrent()
            assertEquals(recovering, model.uiState.value.requiresRevocation)
            model.selectGrant(1, false, false)
            model.selectLimits(1, 1)
            val shown = model.uiState.value.pendingRequest!!
            assertEquals(recovering, shown.grantChoices.first { it.index == 1 && !it.encryption }.selected)
            assertEquals(if (recovering) 0 else 1, shown.budgetPreset)
            assertEquals(if (recovering) 0 else 1, shown.lifetimePreset)
        }
    }

    @Test fun `failed scope refresh retains recovery controls but prevents stale approval`() {
        scan()
        coEvery { repository.approvePreparedLogin(any(), any()) } throws GroupScopeChangedException()
        coEvery { repository.prepareLogin(any()) } throws IllegalStateException("Proof unavailable")
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(model.uiState.value.pendingRequest)
        assertTrue(model.uiState.value.requiresRevocation)
        assertNull(model.callbackOffer.value)
        assertTrue((model.approveResult.value as ConnectionsViewModel.ApproveResult.Error).message!!.contains("Scan a new QR"))
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.revokePreparedLogin(request, plan) }
    }

    @Test fun `changed recovered scope needs a new acknowledgement and still permits revoke`() {
        val changed = plan.copy(recovering = true, previousGroupScopes = mapOf(groupId.toString() to group),
            groupScopes = mapOf(groupId.toString() to group.copy(adminIds = setOf(groupId))))
        coEvery { repository.prepareLogin(any()) } returns changed
        scan()
        assertTrue(model.uiState.value.pendingRequest!!.requiresGroupReapproval)
        assertFalse(model.uiState.value.pendingRequest!!.groupScopeChangeApproved)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { repository.approvePreparedLogin(any(), any()) }
        model.approveGroupScopeChange(true)
        assertTrue(model.uiState.value.pendingRequest!!.groupScopeChangeApproved)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.approvePreparedLogin(request, changed.copy(groupScopeChangeApproved = true)) }
        model.approveGroupScopeChange(false)
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.revokePreparedLogin(request, changed) }
    }

    @Test fun `scope refresh resets the prior acknowledgement`() {
        val changed = plan.copy(recovering = true)
        coEvery { repository.prepareLogin(any()) } returns changed
        coEvery { repository.approvePreparedLogin(any(), any()) } throws GroupScopeChangedException()
        scan()
        model.approveGroupScopeChange(true)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.pendingRequest!!.requiresGroupReapproval)
        assertFalse(model.uiState.value.pendingRequest!!.groupScopeChangeApproved)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.approvePreparedLogin(any(), any()) }
        assertNull(model.callbackOffer.value)
    }

    @Test fun `cold URL is retained until identity initialization completes`() {
        val initialized = MutableStateFlow<Result<Unit>?>(null)
        every { identities.initializationResult } returns initialized
        scan(openedFromUrl = true)
        assertTrue(model.uiState.value.isLoading)
        assertNull(model.uiState.value.pendingRequest)
        coVerify(exactly = 0) { repository.parseQr(any()) }
        initialized.value = Result.success(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(model.uiState.value.pendingRequest)
        assertFalse(model.uiState.value.isLoading)
        coEvery { repository.approvePreparedLogin(any(), any()) } returns mockk(relaxed = true)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(model.callbackOffer.value)
    }

    @Test fun `initialization error is surfaced without preparing a grant`() {
        every { identities.initializationResult } returns MutableStateFlow(Result.failure(IllegalStateException("Identity load failed")))
        scan(openedFromUrl = true)
        assertFalse(model.uiState.value.isLoading)
        assertEquals("Identity load failed", model.uiState.value.error)
        coVerify(exactly = 0) { repository.prepareLogin(any()) }
    }

    @Test fun `completed initialization with no identity reports absence rather than waiting forever`() {
        coEvery { repository.prepareLogin(any()) } throws IllegalStateException("Identity unavailable")
        scan(openedFromUrl = true)
        assertFalse(model.uiState.value.isLoading)
        assertEquals("Identity unavailable", model.uiState.value.error)
    }

    @Test fun `uncertain approval can close without revoking or reporting declined`() {
        failApproval(openedFromUrl = true)
        model.closePendingRequest()
        assertNull(model.uiState.value.pendingRequest)
        assertNull(model.callbackOffer.value)
        assertTrue(model.scanOutcome.value is ConnectionsViewModel.ScanOutcome.Error)
        coVerify(exactly = 0) { repository.revokePreparedLogin(any(), any()) }
    }

    @Test fun `unreadable cold records are unavailable and block requests until retry succeeds`() {
        every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow { throw java.io.IOException("Unreadable") }
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        for (url in listOf(false, true)) {
            model.resetScanOutcome()
            if (url) model.onUrlOpened("dash-key:2?n=d&v=2") else model.onQrScanned("dash-st:2?n=d&v=1")
            dispatcher.scheduler.runCurrent()
            assertTrue(model.scanOutcome.value is ConnectionsViewModel.ScanOutcome.Error)
            assertFalse(model.uiState.value.isLoading)
        }
        coVerify(exactly = 0) { repository.parseQr(any()) }
        coVerify(exactly = 0) { repository.prepareTokenPurchase(any()) }
        every { repository.observeConnections() } returns MutableStateFlow(emptyList())
        model.refreshConnections()
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        scan()
        assertNotNull(model.uiState.value.pendingRequest)
    }

    @Test fun `late storage failure removes stale rows and blocks approval details and mutations`() {
        val failed = CompletableDeferred<Unit>()
        val connection = DAppConnection("app", "App", "", ConnectionStatus.ACTIVE, 1)
        every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow {
            emit(listOf(connection))
            failed.await()
            throw java.io.IOException("Read failed")
        }
        model.refreshConnections()
        scan()
        assertEquals(listOf(connection), model.uiState.value.connections)
        failed.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        assertTrue(model.uiState.value.connections.isEmpty())
        model.approvePendingRequest()
        model.disconnect(connection)
        model.removeConnection(connection)
        dispatcher.scheduler.runCurrent()
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { model.connectionDetails(connection) } }
        coVerify(exactly = 0) { repository.approvePreparedLogin(any(), any()) }
        coVerify(exactly = 0) { repository.disconnect(any(), any()) }
        coVerify(exactly = 0) { repository.removeConnection(any(), any()) }
        coVerify(exactly = 0) { repository.connectionDetails(any(), any()) }
    }

    @Test fun `pending identity initialization stays loading rather than ready empty`() {
        val initialized = MutableStateFlow<Result<Unit>?>(null)
        every { identities.initializationResult } returns initialized
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
        assertTrue(model.uiState.value.connections.isEmpty())
        initialized.value = Result.success(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        assertTrue(model.uiState.value.connections.isEmpty())
        coVerify(exactly = 0) { identities.init() }
    }

    @Test fun `failed initialization is unavailable and automatically recovers when initialization succeeds`() {
        val initialized = MutableStateFlow<Result<Unit>?>(Result.failure(IllegalStateException("Identity load failed")))
        every { identities.initializationResult } returns initialized
        val connection = DAppConnection("app", "App", "", ConnectionStatus.ACTIVE, 1, "scope")
        every { repository.observeConnections() } returns MutableStateFlow(listOf(connection))
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        assertFalse(model.canUseConnections)
        assertTrue(model.uiState.value.connections.isEmpty())
        initialized.value = null
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
        initialized.value = Result.success(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        assertEquals(listOf(connection), model.uiState.value.connections)
    }

    @Test fun `scope failure is unavailable and retry reads scope again instead of reporting empty`() {
        every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow {
            throw IllegalStateException("Wallet unavailable")
        }
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        assertFalse(model.canUseConnections)
        every { repository.observeConnections() } returns MutableStateFlow(emptyList())
        model.refreshConnections()
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        assertTrue(model.uiState.value.connections.isEmpty())
    }

    @Test fun `confirmed fresh wallet has its own state and cannot prepare scanned or URL requests`() {
        every { identityConfig.observeIdentityPresence() } returns MutableStateFlow(false)
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.NO_IDENTITY, model.uiState.value.recordsStatus)
        assertFalse(model.canUseConnections)
        assertTrue(model.uiState.value.connections.isEmpty())
        for (url in listOf(false, true)) {
            if (url) model.onUrlOpened("dash-key:test") else model.onQrScanned("dash-key:test")
            dispatcher.scheduler.runCurrent()
            assertEquals("Create a username to connect apps.",
                (model.scanOutcome.value as ConnectionsViewModel.ScanOutcome.Error).message)
            model.resetScanOutcome()
        }
        verify(exactly = 0) { repository.observeConnections() }
        coVerify(exactly = 0) { repository.prepareLogin(any()) }
    }

    @Test fun `saved audits without an identity remain unavailable rather than confirmed empty`() {
        every { identityConfig.observeIdentityPresence() } returns MutableStateFlow(false)
        every { auditConfig.observeHasRecords() } returns MutableStateFlow(true)
        every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow { error("Identity unavailable") }
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        assertFalse(model.canUseConnections)
    }

    @Test fun `unreadable strict identity snapshot preserves retryable unavailable state`() {
        every { identityConfig.observeIdentityPresence() } returns kotlinx.coroutines.flow.flow { throw java.io.IOException("Read failed") }
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
    }

    @Test fun `explicit Retry reloads cached successful missing identity and coalesces repeated taps`() {
        val initialized = MutableStateFlow<Result<Unit>?>(Result.success(Unit))
        val identity = MutableStateFlow<org.dashj.platform.dashpay.BlockchainIdentity?>(null)
        every { identities.initializationResult } returns initialized
        every { identities.blockchainIdentityFlow } returns identity
        every { identities.blockchainIdentity } answers { identity.value }
        val connection = DAppConnection("app", "App", "", ConnectionStatus.ACTIVE, 1)
        every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow {
            check(identity.value != null) { "Identity unavailable" }
            emit(listOf(connection))
        }
        val loaded = CompletableDeferred<Unit>()
        coEvery { identities.init() } coAnswers {
            initialized.value = null
            loaded.await()
            identity.value = mockk(relaxed = true)
            initialized.value = Result.success(Unit)
        }
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        model.retryConnections()
        model.retryConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
        coVerify(exactly = 1) { identities.init() }
        loaded.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        assertEquals(listOf(connection), model.uiState.value.connections)
    }

    @Test fun `explicit Retry reloads failed initialization rather than the cached failure`() {
        val initialized = MutableStateFlow<Result<Unit>?>(Result.failure(IllegalStateException("Read failed")))
        every { identities.initializationResult } returns initialized
        coEvery { identities.init() } coAnswers {
            initialized.value = null
            initialized.value = Result.success(Unit)
        }
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        model.retryConnections()
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { identities.init() }
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
    }

    @Test fun `Retry does not start another loader while startup initialization is pending`() {
        every { identities.initializationResult } returns MutableStateFlow<Result<Unit>?>(null)
        model.retryConnections()
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { identities.init() }
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
    }

    @Test fun `Retry with an initialized identity only reloads the connection records`() {
        every { identities.blockchainIdentity } returns mockk(relaxed = true)
        model.retryConnections()
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { identities.init() }
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
    }

    @Test fun `explicit identity reload finishes after the initiating ViewModel is cleared`() {
        val initialized = MutableStateFlow<Result<Unit>?>(Result.success(Unit))
        every { identities.initializationResult } returns initialized
        val loaded = CompletableDeferred<Unit>()
        coEvery { identities.init() } coAnswers {
            initialized.value = null
            loaded.await()
            initialized.value = Result.success(Unit)
        }
        model.retryConnections()
        assertNull(initialized.value)
        model.viewModelScope.cancel()
        loaded.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertTrue(initialized.value!!.isSuccess)
    }

    @Test fun `real cancelled identity owner exposes Retry and explicit reload restores connections`() {
        model.viewModelScope.cancel()
        val storage = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityConfig>(relaxed = true)
        val provider = mockk<org.dash.wallet.common.WalletDataProvider>(relaxed = true)
        val wallet = mockk<org.bitcoinj.wallet.Wallet>(relaxed = true)
        every { provider.wallet } returns wallet
        val owner = spyk(de.schildbach.wallet.service.platform.IdentityRepositoryImpl(mockk(relaxed = true),
            mockk(relaxed = true), storage, provider, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)))
        val identity = mockk<org.dashj.platform.dashpay.BlockchainIdentity>(relaxed = true)
        val data = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityData>(relaxed = true)
        every { owner.initBlockchainIdentity(data, wallet) } returns identity
        coEvery { storage.load() } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val loading = applicationScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { owner.init() }
        val connection = DAppConnection("app", "App", "", ConnectionStatus.ACTIVE, 1)
        every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow {
            check(owner.blockchainIdentity != null) { "Identity unavailable" }
            emit(listOf(connection))
        }
        model = ConnectionsViewModel(repository, owner, applicationScope, availability(owner))
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
        loading.cancel()
        dispatcher.scheduler.runCurrent()
        assertTrue(loading.isCancelled)
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        assertFalse(model.canUseConnections)
        assertTrue(owner.initializationResult.value!!.exceptionOrNull()!!.cause is kotlinx.coroutines.CancellationException)
        coEvery { storage.load() } returns data
        model.retryConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        assertEquals(listOf(connection), model.uiState.value.connections)
        coVerify(exactly = 2) { storage.load() }
        verify(exactly = 0) { identity.addMissingKeys(any(), any()) }
    }

    @Test fun `real identity owner reloads a successful partial identity and restores saved connections`() {
        model.viewModelScope.cancel()
        val storage = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityConfig>(relaxed = true)
        val provider = mockk<org.dash.wallet.common.WalletDataProvider>(relaxed = true)
        val wallet = mockk<org.bitcoinj.wallet.Wallet>(relaxed = true)
        every { provider.wallet } returns wallet
        val owner = spyk(de.schildbach.wallet.service.platform.IdentityRepositoryImpl(mockk(relaxed = true),
            mockk(relaxed = true), storage, provider, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)))
        val partial = mockk<org.dashj.platform.dashpay.BlockchainIdentity>(relaxed = true)
        every { partial.uniqueIdentifier } throws UninitializedPropertyAccessException("uniqueId")
        val healthy = mockk<org.dashj.platform.dashpay.BlockchainIdentity>(relaxed = true)
        every { healthy.uniqueIdentifier } returns groupId
        val data = mockk<de.schildbach.wallet.database.entity.BlockchainIdentityData>(relaxed = true)
        val reread = CompletableDeferred<Unit>()
        var reads = 0
        coEvery { storage.load() } coAnswers {
            reads++
            if (reads == 2) reread.await()
            data
        }
        every { owner.initBlockchainIdentity(data, wallet) } returnsMany listOf(partial, partial, healthy)
        applicationScope.launch { owner.init() }
        dispatcher.scheduler.runCurrent()
        assertTrue(owner.initializationResult.value!!.isSuccess)
        assertSame(partial, owner.blockchainIdentity)
        val connection = DAppConnection("saved", "App", "", ConnectionStatus.ACTIVE, 1)
        every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow {
            check(owner.blockchainIdentity!!.uniqueIdentifier == groupId)
            emit(listOf(connection))
        }
        model = ConnectionsViewModel(repository, owner, applicationScope, availability(owner))
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        model.retryConnections()
        model.retryConnections()
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 2) { storage.load() }
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
        reread.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        model.retryConnections()
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 3) { storage.load() }
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        assertEquals(listOf(connection), model.uiState.value.connections)
        model.retryConnections()
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 3) { storage.load() }
        verify(exactly = 0) { partial.addMissingKeys(any(), any()) }
        verify(exactly = 0) { healthy.addMissingKeys(any(), any()) }
    }

    @Test fun `immediate failure after emitting a list cannot leave records ready`() {
        for (connections in listOf(emptyList(), listOf(DAppConnection("old", "App", "", ConnectionStatus.ACTIVE, 1)))) {
            every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow {
                emit(connections)
                throw java.io.IOException("Scope or records became unavailable")
            }
            model.refreshConnections()
            dispatcher.scheduler.runCurrent()
            assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
            assertTrue(model.uiState.value.connections.isEmpty())
            assertFalse(model.canUseConnections)
        }
    }

    @Test fun `initialization restart or failure immediately after emitting rows cannot restore stale ready state`() {
        for (next in listOf(null, Result.failure<Unit>(IllegalStateException("Identity unavailable")))) {
            val initialized = MutableStateFlow<Result<Unit>?>(Result.success(Unit))
            every { identities.initializationResult } returns initialized
            every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow {
                emit(listOf(DAppConnection("old", "App", "", ConnectionStatus.ACTIVE, 1)))
                initialized.value = next
                kotlinx.coroutines.awaitCancellation()
            }
            model.refreshConnections()
            dispatcher.scheduler.runCurrent()
            assertEquals(if (next == null) ConnectionRecordsStatus.LOADING else ConnectionRecordsStatus.UNAVAILABLE,
                model.uiState.value.recordsStatus)
            assertTrue(model.uiState.value.connections.isEmpty())
            assertFalse(model.canUseConnections)
        }
    }

    @Test fun `cancelled observer late failure cannot overwrite successful manual refresh`() {
        val release = CompletableDeferred<Unit>()
        every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    release.await()
                    throw java.io.IOException("Late failure from cancelled observation")
                }
            }
        }
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        val current = listOf(DAppConnection("current", "App", "", ConnectionStatus.ACTIVE, 1))
        every { repository.observeConnections() } returns MutableStateFlow(current)
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        release.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        assertEquals(current, model.uiState.value.connections)
    }

    @Test fun `identity changes replace the observer and clear stale rows while reloading scope`() {
        val identity = MutableStateFlow<org.dashj.platform.dashpay.BlockchainIdentity?>(null)
        every { identities.blockchainIdentityFlow } returns identity
        val connection = DAppConnection("old", "App", "", ConnectionStatus.ACTIVE, 1, "old-scope")
        every { repository.observeConnections() } returns MutableStateFlow(listOf(connection))
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(connection), model.uiState.value.connections)
        every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow {
            kotlinx.coroutines.awaitCancellation()
        }
        identity.value = mockk(relaxed = true)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
        assertTrue(model.uiState.value.connections.isEmpty())
        assertFalse(model.canUseConnections)
    }

    @Test fun `real repository preserves scope availability through cold start failure recovery and identity loss`() {
        model.viewModelScope.cancel()
        val identity = mockk<org.dashj.platform.dashpay.BlockchainIdentity>(relaxed = true)
        every { identity.uniqueIdentifier } returns org.dashj.platform.dpp.identifier.Identifier.from(ByteArray(32) { 7 })
        val identityFlow = MutableStateFlow<org.dashj.platform.dashpay.BlockchainIdentity?>(identity)
        val initialized = MutableStateFlow<Result<Unit>?>(null)
        every { identities.blockchainIdentity } answers { identityFlow.value }
        every { identities.blockchainIdentityFlow } returns identityFlow
        every { identities.initializationResult } returns initialized
        val platform = mockk<de.schildbach.wallet.service.platform.PlatformService>(relaxed = true)
        val wallet = mockk<de.schildbach.wallet.ui.dashpay.PlatformRepo>(relaxed = true)
        every { wallet.walletApplication.wallet!!.watchingKey.pubKey } returns ByteArray(33) { 1 }
        val config = mockk<DashConnectConfig>()
        val scope = AppConnectSession(platform, wallet, identities, config).scope()
        val record = org.json.JSONObject().put("scope", scope).put("app", "app").put("state", "active")
            .put("keys", org.json.JSONArray().put(org.json.JSONObject().put("purpose", 0)
                .put("expiresAt", System.currentTimeMillis() + 60_000)))
        val records = MutableStateFlow(listOf(record))
        every { config.observeGrants() } returns records
        every { config.observeConnections() } returns MutableStateFlow(emptyList())
        identityFlow.value = null
        model = ConnectionsViewModel(PlatformDashConnectRepository(platform, wallet, identities, config), identities, applicationScope, availability())
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
        initialized.value = Result.failure(IllegalStateException("Identity initialization failed"))
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        initialized.value = null
        identityFlow.value = identity
        initialized.value = Result.success(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        assertEquals(ConnectionStatus.ACTIVE, model.uiState.value.connections.single().status)
        identityFlow.value = null
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        assertTrue(model.uiState.value.connections.isEmpty())
        assertEquals(1, records.value.size)
        identityFlow.value = identity
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        assertEquals(1, model.uiState.value.connections.size)
        records.value = emptyList()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        assertTrue(model.uiState.value.connections.isEmpty())
        coVerify(exactly = 0) { identities.init() }
    }

    @Test fun `signing key setup requires its own action and never purchases tokens`() {
        val needsKey = purchase().copy(requiresSigningKey = true)
        coEvery { repository.prepareTokenPurchase(any()) } returnsMany listOf(needsKey, purchase())
        model.onQrScanned("dash-st:2?n=d&v=1")
        dispatcher.scheduler.advanceUntilIdle()
        model.approveTokenPurchase()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { repository.setupPurchaseSigningKey(any()) }
        coVerify(exactly = 0) { repository.approveTokenPurchase(any()) }
        val setup = CompletableDeferred<Unit>()
        coEvery { repository.setupPurchaseSigningKey(any()) } coAnswers { setup.await() }
        model.setupPurchaseSigningKey()
        dispatcher.scheduler.runCurrent()
        assertEquals(TokenPurchaseStatus.SETTING_UP_KEY, model.uiState.value.purchaseStatus)
        model.setupPurchaseSigningKey()
        model.approveTokenPurchase()
        model.dismissTokenPurchase()
        setup.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(TokenPurchaseStatus.READY, model.uiState.value.purchaseStatus)
        assertFalse(model.uiState.value.pendingPurchase!!.requiresSigningKey)
        coVerify(exactly = 1) { repository.setupPurchaseSigningKey(needsKey) }
        coVerify(exactly = 0) { repository.approveTokenPurchase(any()) }
        coEvery { repository.approveTokenPurchase(any()) } returns Unit
        model.approveTokenPurchase()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.approveTokenPurchase(any()) }
    }

    @Test fun `lock prevents queued signing key setup`() {
        var unlocked = true
        model.bindWalletUnlockCheck { unlocked }
        coEvery { repository.prepareTokenPurchase(any()) } returns purchase().copy(requiresSigningKey = true)
        model.onQrScanned("dash-st:2?n=d&v=1")
        dispatcher.scheduler.advanceUntilIdle()
        model.setupPurchaseSigningKey()
        unlocked = false
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { repository.setupPurchaseSigningKey(any()) }
        coVerify(exactly = 0) { repository.approveTokenPurchase(any()) }
    }

    private fun assertLockCancelsCapturedOperation(kind: String, background: Boolean = false, nativeStarted: Boolean = false) {
        val locked = MutableStateFlow(false)
        model.bindWalletUnlockCheck { !locked.value }
        model.bindWalletLockState(locked)
        val gate = CompletableDeferred<Unit>()
        var authorize: (() -> Unit)? = null
        var submitted = false
        suspend fun capture() {
            authorize = kotlin.coroutines.coroutineContext.connectionAuthorization()
            gate.await()
            authorize!!()
            submitted = true
        }
        when (kind) {
            "login" -> {
                coEvery { repository.approvePreparedLogin(any(), any()) } coAnswers { capture(); mockk(relaxed = true) }
                scan(openedFromUrl = true)
                model.approvePendingRequest()
            }
            "setup" -> {
                coEvery { repository.prepareTokenPurchase(any()) } returns purchase().copy(requiresSigningKey = true)
                coEvery { repository.setupPurchaseSigningKey(any()) } coAnswers { capture() }
                model.onQrScanned("dash-st:2?n=d&v=1")
                dispatcher.scheduler.runCurrent()
                model.setupPurchaseSigningKey()
            }
            "purchase" -> {
                scanPurchase()
                coEvery { repository.approveTokenPurchase(any()) } coAnswers {
                    if (nativeStarted) kotlin.coroutines.coroutineContext[TokenPurchaseSubmission]!!.markStarted()
                    capture()
                }
                model.approveTokenPurchase()
            }
            else -> {
                coEvery { repository.disconnect(any(), any()) } coAnswers { capture() }
                dispatcher.scheduler.runCurrent()
                model.disconnect(DAppConnection("app", "App", "", ConnectionStatus.ACTIVE, 1, "scope"))
            }
        }
        dispatcher.scheduler.runCurrent()
        assertNotNull(authorize)
        if (background) model.onForegroundChanged(false) else locked.value = true
        dispatcher.scheduler.runCurrent()
        if (background) model.onForegroundChanged(true) else locked.value = false
        dispatcher.scheduler.runCurrent()
        // Captured native signer authorization must remain revoked even after unlocking again.
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { authorize!!() }
        gate.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertFalse(submitted)
        assertNull(model.callbackOffer.value)
        if (kind == "login") {
            assertNotNull(model.uiState.value.pendingRequest)
            assertTrue(model.uiState.value.requiresRevocation)
            assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Error)
            coVerify(exactly = 0) { repository.revokePreparedLogin(any(), any()) }
        } else if (kind == "setup" || kind == "purchase") {
            assertNotNull(model.uiState.value.pendingPurchase)
            assertEquals(if (kind == "purchase" && nativeStarted) TokenPurchaseStatus.OUTCOME_UNKNOWN else TokenPurchaseStatus.FAILED,
                model.uiState.value.purchaseStatus)
        }
    }

    @Test fun `lock cancels in flight login and retains recovery without callback`() = assertLockCancelsCapturedOperation("login")
    @Test fun `lock cancels in flight key setup and requires another explicit action`() = assertLockCancelsCapturedOperation("setup")
    @Test fun `lock cancels purchase preflight without claiming an uncertain debit`() = assertLockCancelsCapturedOperation("purchase")
    @Test fun `lock after purchase handoff retains uncertain debit state`() =
        assertLockCancelsCapturedOperation("purchase", nativeStarted = true)
    @Test fun `lock cancels in flight disconnect and invalidates captured signer`() = assertLockCancelsCapturedOperation("disconnect")

    @Test fun `background cancels login with auto logout unchanged`() = assertLockCancelsCapturedOperation("login", true)
    @Test fun `background cancels purchase key setup`() = assertLockCancelsCapturedOperation("setup", true)
    @Test fun `background cancels purchase preflight without claiming an uncertain debit`() = assertLockCancelsCapturedOperation("purchase", true)
    @Test fun `background after purchase handoff retains unknown outcome`() =
        assertLockCancelsCapturedOperation("purchase", background = true, nativeStarted = true)
    @Test fun `background cancels revocation without claiming completion`() = assertLockCancelsCapturedOperation("disconnect", true)

    @Test fun `rapid pause resume invalidates captured authorization before dispatch`() {
        lateinit var authorize: () -> Unit
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.approvePreparedLogin(any(), any()) } coAnswers {
            authorize = kotlin.coroutines.coroutineContext.connectionAuthorization()
            gate.await()
            authorize()
            mockk(relaxed = true)
        }
        scan(openedFromUrl = true)
        model.approvePendingRequest()
        dispatcher.scheduler.runCurrent()
        model.onForegroundChanged(false)
        model.onForegroundChanged(true)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { authorize() }
        gate.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertTrue(model.uiState.value.requiresRevocation)
        assertNull(model.callbackOffer.value)
        coEvery { repository.approvePreparedLogin(any(), any()) } returns mockk(relaxed = true)
        model.approvePendingRequest()
        dispatcher.scheduler.runCurrent()
        assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Success)
    }

    @Test fun `fast lock and unlock invalidates signing even before flow collection`() {
        val locked = MutableStateFlow(false)
        var generation = 0L
        model.bindWalletUnlockCheck { !locked.value }
        model.bindWalletLockState(locked) { generation }
        lateinit var authorize: () -> Unit
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.approvePreparedLogin(any(), any()) } coAnswers {
            authorize = kotlin.coroutines.coroutineContext.connectionAuthorization()
            gate.await()
            authorize()
            mockk(relaxed = true)
        }
        scan()
        model.approvePendingRequest()
        dispatcher.scheduler.runCurrent()
        generation++
        locked.value = true
        locked.value = false
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { authorize() }
        gate.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertTrue(model.uiState.value.requiresRevocation)
        assertNotNull(model.uiState.value.pendingRequest)
        assertFalse(model.approveResult.value is ConnectionsViewModel.ApproveResult.Success)
    }

    @Test fun `cancelled queued operation releases single flight for fresh approval`() {
        val locked = MutableStateFlow(false)
        model.bindWalletUnlockCheck { !locked.value }
        model.bindWalletLockState(locked)
        scan()
        model.approvePendingRequest()
        model.bindWalletUnlockCheck { false }
        dispatcher.scheduler.runCurrent()
        model.bindWalletUnlockCheck { true }
        dispatcher.scheduler.runCurrent()
        coEvery { repository.approvePreparedLogin(any(), any()) } returns mockk(relaxed = true)
        model.approvePendingRequest()
        dispatcher.scheduler.runCurrent()
        assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Success)
    }

    private fun purchase() = TokenPurchaseApproval(
        de.schildbach.wallet.ui.more.connections.protocol.DashStRequest(byteArrayOf(1),
            de.schildbach.wallet.ui.more.connections.protocol.DashConnectNetwork.DEVNET),
        "scope", "purchase", "identity", "contract", "token", java.math.BigInteger("100"),
        java.math.BigInteger("50000000000"), "Token", 2, byteArrayOf(1))

    private fun scanPurchase() {
        coEvery { repository.prepareTokenPurchase(any()) } returns purchase()
        model.onQrScanned("dash-st:2?n=d&v=1")
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun `approval fails closed without a bound unlocked wallet`() {
        model.viewModelScope.cancel()
        model = ConnectionsViewModel(repository, identities, applicationScope, availability())
        scan(openedFromUrl = true)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(model.uiState.value.pendingRequest)
        assertFalse(model.uiState.value.requiresRevocation)
        coVerify(exactly = 0) { repository.approvePreparedLogin(any(), any()) }
    }

    @Test fun `locked URL request remains pending and can be approved after unlock`() {
        var unlocked = false
        model.bindWalletUnlockCheck { unlocked }
        scan(openedFromUrl = true)
        val pending = model.uiState.value.pendingRequest
        model.approvePendingRequest()
        model.denyPendingRequest()
        model.closePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(pending, model.uiState.value.pendingRequest)
        assertNull(model.callbackOffer.value)
        coVerify(exactly = 0) { repository.approvePreparedLogin(any(), any()) }
        unlocked = true
        coEvery { repository.approvePreparedLogin(any(), any()) } returns mockk(relaxed = true)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Success)
    }

    @Test fun `lock before queued login submission prevents signing without creating recovery state`() {
        var unlocked = true
        model.bindWalletUnlockCheck { unlocked }
        scan()
        model.approvePendingRequest()
        unlocked = false
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.uiState.value.requiresRevocation)
        assertNotNull(model.uiState.value.pendingRequest)
        coVerify(exactly = 0) { repository.approvePreparedLogin(any(), any()) }
    }

    @Test fun `locked purchase cannot submit or clear pending approval`() {
        scanPurchase()
        var unlocked = false
        model.bindWalletUnlockCheck { unlocked }
        model.approveTokenPurchase()
        model.dismissTokenPurchase()
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(model.uiState.value.pendingPurchase)
        assertEquals(TokenPurchaseStatus.READY, model.uiState.value.purchaseStatus)
        coVerify(exactly = 0) { repository.approveTokenPurchase(any()) }
        unlocked = true
        coEvery { repository.approveTokenPurchase(any()) } returns Unit
        model.approveTokenPurchase()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(TokenPurchaseStatus.SUBMITTED, model.uiState.value.purchaseStatus)
    }

    @Test fun `lock before queued purchase submission prevents debit`() {
        var unlocked = true
        model.bindWalletUnlockCheck { unlocked }
        scanPurchase()
        model.approveTokenPurchase()
        unlocked = false
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(model.uiState.value.pendingPurchase)
        assertEquals(TokenPurchaseStatus.READY, model.uiState.value.purchaseStatus)
        coVerify(exactly = 0) { repository.approveTokenPurchase(any()) }
    }

    @Test fun `locking preserves uncertain grant recovery and does not revoke`() {
        failApproval()
        val pending = model.uiState.value.pendingRequest
        model.bindWalletUnlockCheck { false }
        model.closePendingRequest()
        model.denyPendingRequest()
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(pending, model.uiState.value.pendingRequest)
        assertTrue(model.uiState.value.requiresRevocation)
        coVerify(exactly = 0) { repository.revokePreparedLogin(any(), any()) }
        coVerify(exactly = 1) { repository.approvePreparedLogin(any(), any()) }
    }

    @Test fun `auto lock dismisses both nested approval sheets without clearing pending operations`() {
        verifyLockDismissal(purchase = false, restoredWhileLocked = false)
        verifyLockDismissal(purchase = true, restoredWhileLocked = false)
    }

    @Test fun `restored approval sheets cannot appear over the PIN screen`() {
        verifyLockDismissal(purchase = false, restoredWhileLocked = true)
        verifyLockDismissal(purchase = true, restoredWhileLocked = true)
    }

    private fun verifyLockDismissal(purchase: Boolean, restoredWhileLocked: Boolean) {
        ShadowChoreographer.setPaused(true)
        // Use a fresh model so the two pending request kinds cannot block each other.
        model.viewModelScope.cancel()
        model = ConnectionsViewModel(repository, identities, applicationScope, availability())
        var unlocked = !restoredWhileLocked
        model.onForegroundChanged(true)
        model.bindWalletUnlockCheck { unlocked }
        if (purchase) scanPurchase() else scan(openedFromUrl = true)
        ApprovalTestParent.model = model
        val controller = Robolectric.buildActivity(ApprovalTestActivity::class.java).setup()
        val activity = controller.get()
        val parent = ApprovalTestParent()
        activity.supportFragmentManager.beginTransaction().add(parent, "parent").commitNow()
        val sheet = if (purchase) de.schildbach.wallet.ui.compose_views.ApproveTokenPurchaseDialog()
            else ApproveConnectionDialog()
        try {
            sheet.showNow(parent.childFragmentManager, "approval")
            if (!restoredWhileLocked) {
                assertTrue(sheet.requireDialog().isShowing)
                unlocked = false
                // Exercise the activity's real lock traversal with an ordinary parent fragment,
                // the nesting that previously left approval sheets above the PIN screen.
                val lockActivity = Robolectric.buildActivity(de.schildbach.wallet.ui.LockScreenActivity::class.java).get()
                ReflectionHelpers.callInstanceMethod<Unit>(lockActivity, "notifyAndDismissFragments",
                    ReflectionHelpers.ClassParameter.from(androidx.fragment.app.FragmentManager::class.java,
                        activity.supportFragmentManager))
            }
            parent.childFragmentManager.executePendingTransactions()
            assertFalse(sheet.dialog?.isShowing == true)
            assertNotNull(if (purchase) model.uiState.value.pendingPurchase else model.uiState.value.pendingRequest)
            assertNull(model.callbackOffer.value)
            coVerify(exactly = 0) { repository.revokePreparedLogin(any(), any()) }
            coVerify(exactly = 0) { repository.approveTokenPurchase(any()) }
            coVerify(exactly = 0) { repository.approvePreparedLogin(any(), any()) }
            unlocked = true
            val reopened = if (purchase) de.schildbach.wallet.ui.compose_views.ApproveTokenPurchaseDialog()
                else ApproveConnectionDialog()
            reopened.showNow(parent.childFragmentManager, "approval")
            assertTrue(reopened.requireDialog().isShowing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `operation link only prepares approval and cancel never signs`() {
        scanPurchase()
        assertNotNull(model.uiState.value.pendingPurchase)
        coVerify(exactly = 0) { repository.approveTokenPurchase(any()) }
        model.dismissTokenPurchase()
        assertNull(model.uiState.value.pendingPurchase)
        coVerify(exactly = 0) { repository.approveTokenPurchase(any()) }
        coVerify(exactly = 0) { repository.prepareLogin(any()) }
    }

    @Test fun `purchase is single flight and an uncertain submission cannot be retried`() {
        scanPurchase()
        val submitted = CompletableDeferred<Unit>()
        coEvery { repository.approveTokenPurchase(any()) } coAnswers { submitted.await() }
        model.approveTokenPurchase()
        dispatcher.scheduler.runCurrent()
        model.approveTokenPurchase()
        model.dismissTokenPurchase()
        assertNotNull(model.uiState.value.pendingPurchase)
        coVerify(exactly = 1) { repository.approveTokenPurchase(any()) }
        submitted.completeExceptionally(TokenPurchaseOutcomeUnknown(IllegalStateException("Timeout")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(TokenPurchaseStatus.OUTCOME_UNKNOWN, model.uiState.value.purchaseStatus)
        model.approveTokenPurchase()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.approveTokenPurchase(any()) }
        model.dismissTokenPurchase()
        assertNull(model.uiState.value.pendingPurchase)
    }

    @Test fun `failure before submission may retry but successful submission may not`() {
        scanPurchase()
        coEvery { repository.approveTokenPurchase(any()) } throws IllegalStateException("Wallet locked")
        model.approveTokenPurchase()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(TokenPurchaseStatus.FAILED, model.uiState.value.purchaseStatus)
        coEvery { repository.approveTokenPurchase(any()) } returns Unit
        model.approveTokenPurchase()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(TokenPurchaseStatus.SUBMITTED, model.uiState.value.purchaseStatus)
        model.approveTokenPurchase()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 2) { repository.approveTokenPurchase(any()) }
        assertNull(model.callbackOffer.value)
    }

    @Test fun `purchase sheet restores while submitting without cancelling or duplicating the debit`() {
        ShadowChoreographer.setPaused(true)
        scanPurchase()
        val submitted = CompletableDeferred<Unit>()
        coEvery { repository.approveTokenPurchase(any()) } coAnswers { submitted.await() }
        ApprovalTestParent.model = model
        var controller = Robolectric.buildActivity(ApprovalTestActivity::class.java)
        val activity = controller.setup().get()
        val parent = ApprovalTestParent()
        activity.supportFragmentManager.beginTransaction().add(parent, "parent").commitNow()
        de.schildbach.wallet.ui.compose_views.ApproveTokenPurchaseDialog().showNow(parent.childFragmentManager, "purchase")
        model.approveTokenPurchase()
        dispatcher.scheduler.runCurrent()
        try {
            val saved = android.os.Bundle()
            ReflectionHelpers.setField(activity, "mChangingConfigurations", true)
            controller.pause().stop().saveInstanceState(saved).destroy()
            controller = Robolectric.buildActivity(ApprovalTestActivity::class.java).setup(saved)
            val restoredParent = controller.get().supportFragmentManager.findFragmentByTag("parent")!!
            val restored = restoredParent.childFragmentManager.findFragmentByTag("purchase") as
                de.schildbach.wallet.ui.compose_views.ApproveTokenPurchaseDialog
            assertTrue(restored.requireDialog().isShowing)
            assertFalse(restored.isCancelable)
            assertEquals(TokenPurchaseStatus.SUBMITTING, model.uiState.value.purchaseStatus)
            submitted.completeExceptionally(TokenPurchaseOutcomeUnknown(IllegalStateException("Timeout")))
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(restored.requireDialog().isShowing)
            assertEquals(TokenPurchaseStatus.OUTCOME_UNKNOWN, model.uiState.value.purchaseStatus)
            coVerify(exactly = 1) { repository.approveTokenPurchase(any()) }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `denying untouched request does not revoke`() {
        scan(openedFromUrl = true)
        model.denyPendingRequest()
        assertNull(model.uiState.value.pendingRequest)
        assertTrue(model.callbackOffer.value!!.contains("ac=declined"))
        coVerify(exactly = 0) { repository.revokePreparedLogin(any(), any()) }
    }

    @Test fun `denying after refreshed group consent reports failed without unnecessary revocation`() {
        scan(openedFromUrl = true)
        coEvery { repository.approvePreparedLogin(any(), any()) } throws GroupScopeChangedException()
        coEvery { repository.prepareLogin(any()) } returns plan.copy(recovering = false)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.uiState.value.requiresRevocation)
        model.denyPendingRequest()
        assertTrue(model.callbackOffer.value!!.contains("ac=failed"))
        coVerify(exactly = 0) { repository.revokePreparedLogin(any(), any()) }
    }

    @Test fun `failed approval must revoke before reporting failed`() {
        failApproval(openedFromUrl = true)
        val revoked = CompletableDeferred<Unit>()
        coEvery { repository.revokePreparedLogin(any(), any()) } coAnswers { revoked.await() }
        model.denyPendingRequest()
        dispatcher.scheduler.runCurrent()
        assertNotNull(model.uiState.value.pendingRequest)
        assertNull(model.callbackOffer.value)
        revoked.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(model.uiState.value.pendingRequest)
        assertTrue(model.callbackOffer.value!!.contains("ac=failed"))
        coVerify(exactly = 1) { repository.revokePreparedLogin(request, plan) }
    }

    @Test fun `failed revocation retains failure until a later confirmed revocation`() {
        failApproval(openedFromUrl = true)
        coEvery { repository.revokePreparedLogin(any(), any()) } throws IllegalStateException("Unknown outcome")
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(model.callbackOffer.value)
        assertNotNull(model.uiState.value.pendingRequest)
        coEvery { repository.revokePreparedLogin(any(), any()) } returns Unit
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("https://example.org/return?ac=failed", model.callbackOffer.value)
    }

    @Test fun `successful retry replaces failure status with ok`() {
        failApproval(openedFromUrl = true)
        coEvery { repository.approvePreparedLogin(any(), any()) } returns mockk(relaxed = true)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("https://example.org/return?ac=ok", model.callbackOffer.value)
    }

    @Test fun `new request does not inherit previous failure callback status`() {
        failApproval(openedFromUrl = true)
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        model.resetCallbackOffer()
        scan(openedFromUrl = true)
        model.denyPendingRequest()
        assertEquals("https://example.org/return?ac=declined", model.callbackOffer.value)
    }

    @Test fun `scanner approval never offers website callback`() {
        coEvery { repository.approvePreparedLogin(any(), any()) } returns mockk(relaxed = true)
        scan()
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Success)
        assertNull(model.callbackOffer.value)
    }

    @Test fun `URL approval offers website callback`() {
        coEvery { repository.approvePreparedLogin(any(), any()) } returns mockk(relaxed = true)
        scan(openedFromUrl = true)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Success)
        assertEquals("https://example.org/return?ac=ok", model.callbackOffer.value)
    }

    @Test fun `scanner denial never offers website callback`() {
        scan()
        model.denyPendingRequest()
        assertNull(model.uiState.value.pendingRequest)
        assertNull(model.callbackOffer.value)
    }

    @Test fun `scanner revocation after failed approval never offers callback`() {
        failApproval()
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.revokePreparedLogin(request, plan) }
        assertNull(model.uiState.value.pendingRequest)
        assertNull(model.callbackOffer.value)
    }

    @Test fun `URL origin does not leak into a later scan`() {
        scan(openedFromUrl = true)
        model.denyPendingRequest()
        model.resetCallbackOffer()
        scan()
        model.denyPendingRequest()
        assertNull(model.callbackOffer.value)
    }

    @Test fun `URL received during scanner approval cannot enable callback`() {
        scan()
        model.onUrlOpened("another request")
        model.denyPendingRequest()
        assertNull(model.callbackOffer.value)
    }

    @Test fun `URL origin survives failed approval and retry`() {
        failApproval(openedFromUrl = true)
        coEvery { repository.approvePreparedLogin(any(), any()) } returns mockk(relaxed = true)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Success)
        assertEquals("https://example.org/return?ac=ok", model.callbackOffer.value)
    }

    @Test fun `uncertain revocation retains request and does not report declined`() {
        failApproval()
        coEvery { repository.revokePreparedLogin(any(), any()) } throws IllegalStateException("Still pending")
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(model.uiState.value.pendingRequest)
        assertTrue(model.uiState.value.requiresRevocation)
        assertNull(model.callbackOffer.value)
        assertEquals("Still pending", (model.approveResult.value as ConnectionsViewModel.ApproveResult.Error).message)
        coEvery { repository.revokePreparedLogin(any(), any()) } returns Unit
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(model.uiState.value.pendingRequest)
    }

    @Test fun `rescanned persisted grant requires revocation even before another approval`() {
        coEvery { repository.prepareLogin(any()) } returns plan.copy(recovering = true)
        scan()
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.revokePreparedLogin(request, plan.copy(recovering = true)) }
    }

    @Test fun `failed approval terms stay frozen and retry uses original plan`() {
        failApproval()
        val original = model.uiState.value.pendingRequest
        model.selectLimits(1, 1)
        model.selectGrant(1, false, false)
        assertEquals(original, model.uiState.value.pendingRequest)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 2) { repository.approvePreparedLogin(request, plan) }
    }

    @Test fun `request and error replay after UI observer recreation`() {
        failApproval()
        model.resetScanOutcome()
        // The restorable sheet reads retained state, not a consumed scan event or disposal callback.
        assertNotNull(model.uiState.replayCache.single().pendingRequest)
        assertTrue(model.approveResult.replayCache.single() is ConnectionsViewModel.ApproveResult.Error)
        model.denyPendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        scan()
        assertNotNull(model.uiState.value.pendingRequest)
        assertFalse(model.uiState.value.requiresRevocation)
        assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Idle)
    }

    @Test fun `deny while approving cannot race submission`() {
        val result = CompletableDeferred<DAppConnection>()
        coEvery { repository.approvePreparedLogin(any(), any()) } coAnswers { result.await() }
        scan()
        model.approvePendingRequest()
        dispatcher.scheduler.runCurrent()
        model.denyPendingRequest()
        assertNull(model.callbackOffer.value)
        coVerify(exactly = 0) { repository.revokePreparedLogin(any(), any()) }
        result.completeExceptionally(IllegalStateException("Uncertain"))
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(model.uiState.value.pendingRequest)
        assertTrue(model.uiState.value.requiresRevocation)
    }

    @Test fun `fragment rendered Retry recovers the same model used by connection actions`() {
        ShadowChoreographer.setPaused(false)
        val persistedIdentity = MutableStateFlow(true)
        every { identityConfig.observeIdentityPresence() } returns persistedIdentity
        val connection = DAppConnection("saved", "Saved app", "", ConnectionStatus.ACTIVE, 1)
        var available = false
        every { repository.observeConnections() } returns kotlinx.coroutines.flow.flow {
            check(available) { "Records unavailable" }
            emit(listOf(connection))
        }
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.UNAVAILABLE, model.uiState.value.recordsStatus)
        val controller = Robolectric.buildActivity(ApprovalTestActivity::class.java).setup()
        val activity = controller.get()
        val host = ApprovalTestParent()
        ApprovalTestParent.model = model
        activity.supportFragmentManager.beginTransaction().add(host, "host").commitNow()
        val fragment = ConnectionsFragment()
        // Render the real Fragment content without replacing its screen or state wiring.
        ReflectionHelpers.setField(fragment, "mHost", ReflectionHelpers.getField<Any>(host, "mHost"))
        ReflectionHelpers.setField(fragment, "componentContext", android.content.ContextWrapper(activity))
        ReflectionHelpers.setField(fragment, "viewModel\$delegate", lazyOf(model))
        val view = fragment.onCreateView(activity.layoutInflater, null, null) as androidx.compose.ui.platform.ComposeView
        activity.setContentView(view)
        controller.visible()
        fun render() {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 1920)
        }
        fun nodes(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
            listOf(node) + node.children.flatMap { nodes(it) }
        try {
            render()
            val owner = ReflectionHelpers.callInstanceMethod<androidx.compose.ui.semantics.SemanticsOwner>(
                view.getChildAt(0), "getSemanticsOwner")
            val retryText = activity.getString(de.schildbach.wallet_test.R.string.dash_connect_records_retry)
            val retry = nodes(owner.rootSemanticsNode).first { node ->
                node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) &&
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { it.text == retryText }
            }
            val button = generateSequence(retry) { it.parent }.first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            }
            available = true
            assertTrue(button.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke())
            dispatcher.scheduler.runCurrent()
            assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
            assertEquals(listOf(connection), model.uiState.value.connections)
            render()
            assertTrue(nodes(owner.rootSemanticsNode).any { node ->
                node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) &&
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { it.text == connection.name }
            })
            coEvery { repository.connectionDetails(connection.id, connection.scope) } returns "Same owner"
            assertEquals("Same owner", kotlinx.coroutines.runBlocking { model.connectionDetails(connection) })
            persistedIdentity.value = false
            dispatcher.scheduler.runCurrent()
            assertEquals(ConnectionRecordsStatus.NO_IDENTITY, model.uiState.value.recordsStatus)
            render()
            val shown = nodes(owner.rootSemanticsNode).filter {
                it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)
            }.flatMap { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { text -> text.text } }
            assertTrue(shown.contains(activity.getString(de.schildbach.wallet_test.R.string.dash_connect_identity_required)))
            assertFalse(shown.contains(retryText))
            assertFalse(shown.contains(activity.getString(de.schildbach.wallet_test.R.string.dash_connect_scan_qr)))
        } finally {
            view.disposeComposition()
            controller.pause().stop().destroy()
        }
    }

    @Test fun `approval sheet is restored during operation and retains a later failure`() {
        // Do not auto-advance an infinite loading animation while restoring the activity.
        ShadowChoreographer.setPaused(true)
        val result = CompletableDeferred<DAppConnection>()
        coEvery { repository.approvePreparedLogin(any(), any()) } coAnswers { result.await() }
        scan()
        ApprovalTestParent.model = model
        var controller = Robolectric.buildActivity(ApprovalTestActivity::class.java)
        val activity = controller.setup().get()
        val parent = ApprovalTestParent()
        activity.supportFragmentManager.beginTransaction().add(parent, "parent").commitNow()
        ApproveConnectionDialog().showNow(parent.childFragmentManager, "approval")
        model.approvePendingRequest()
        dispatcher.scheduler.runCurrent()
        try {
            val saved = android.os.Bundle()
            ReflectionHelpers.setField(activity, "mChangingConfigurations", true)
            controller.pause().stop().saveInstanceState(saved).destroy()
            controller = Robolectric.buildActivity(ApprovalTestActivity::class.java).setup(saved)
            val restoredParent = controller.get().supportFragmentManager.findFragmentByTag("parent")!!
            val restored = restoredParent.childFragmentManager.findFragmentByTag("approval") as ApproveConnectionDialog
            assertTrue(restored.requireDialog().isShowing)
            assertFalse(restored.isCancelable)
            assertNotNull(model.uiState.value.pendingRequest)
            assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Loading)
            result.completeExceptionally(IllegalStateException("Confirmation uncertain"))
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(restored.requireDialog().isShowing)
            assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Error)
            coVerify(exactly = 0) { repository.revokePreparedLogin(any(), any()) }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private val sharedKeys = SharedProfileKeys("scope", listOf(20))

    private fun loadSharedKeys() {
        coEvery { repository.sharedProfileKeys() } returns sharedKeys
        model.refreshConnections()
        dispatcher.scheduler.runCurrent()
        assertEquals(sharedKeys, model.uiState.value.sharedProfileKeys)
    }

    @Test fun `revoking shared profile keys is authorized and clears the warning`() {
        loadSharedKeys()
        var authorized = false
        coEvery { repository.revokeSharedProfileKeys(sharedKeys) } coAnswers {
            kotlin.coroutines.coroutineContext.connectionAuthorization()()
            authorized = true
        }
        model.revokeSharedProfileKeys()
        assertTrue(model.uiState.value.isRevokingSharedKeys)
        dispatcher.scheduler.runCurrent()
        assertTrue(authorized)
        assertNull(model.uiState.value.sharedProfileKeys)
        assertFalse(model.uiState.value.isRevokingSharedKeys)
    }

    @Test fun `failed shared key revocation keeps the warning and reports the error`() {
        loadSharedKeys()
        coEvery { repository.revokeSharedProfileKeys(sharedKeys) } throws IllegalStateException("Identity update batch not confirmed")
        model.revokeSharedProfileKeys()
        dispatcher.scheduler.runCurrent()
        assertEquals(sharedKeys, model.uiState.value.sharedProfileKeys)
        assertFalse(model.uiState.value.isRevokingSharedKeys)
        assertEquals("Identity update batch not confirmed",
            (model.scanOutcome.value as ConnectionsViewModel.ScanOutcome.Error).message)
    }

    @Test fun `locked wallet cannot revoke shared profile keys`() {
        loadSharedKeys()
        model.bindWalletUnlockCheck { false }
        model.revokeSharedProfileKeys()
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { repository.revokeSharedProfileKeys(any()) }
        assertFalse(model.uiState.value.isRevokingSharedKeys)
    }

    @Test fun `failed dismissal save shows the shared key warning again`() {
        loadSharedKeys()
        coEvery { repository.dismissSharedProfileKeys(sharedKeys) } throws java.io.IOException("No space left")
        model.dismissSharedProfileKeys()
        assertNull(model.uiState.value.sharedProfileKeys)
        dispatcher.scheduler.runCurrent()
        assertEquals(sharedKeys, model.uiState.value.sharedProfileKeys)
    }
}

class ApprovalTestParent : Fragment() {
    companion object { lateinit var model: ConnectionsViewModel }
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
        }
}

class ApprovalTestActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        setTheme(org.dash.wallet.common.R.style.My_Theme)
        super.onCreate(savedInstanceState)
        setContentView(android.widget.FrameLayout(this))
    }
}
