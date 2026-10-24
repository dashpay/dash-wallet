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
    private val repository = mockk<DashConnectRepository>(relaxed = true)
    private val identities = mockk<IdentityRepository>(relaxed = true)
    private lateinit var model: ConnectionsViewModel
    private var originalSupportsPlatform = false
    private val vector = JsonParser.parseReader(javaClass.getResourceAsStream("/app-connect-vectors.json")!!.reader()).asJsonObject
    private val decoded = DashConnectUri.parseKeyRequest(vector.getAsJsonObject("request")["uri"].asString)
    private val request = decoded.copy(appConnect = decoded.appConnect!!.copy(callback = java.net.URI("https://example.org/return")))
    private val plan = AppConnectPlan("hash", "scope", "identity", request.appConnect!!.auth, "Consent")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        originalSupportsPlatform = Constants.SUPPORTS_PLATFORM
        org.junit.Assume.assumeFalse(Constants.IS_PROD_BUILD ||
            Constants.NETWORK_PARAMETERS.id == org.bitcoinj.core.NetworkParameters.ID_MAINNET)
        Constants.SUPPORTS_PLATFORM = true
        every { repository.observeConnections() } returns MutableStateFlow(emptyList())
        every { identities.blockchainIdentity } returns null
        every { identities.initializationResult } returns MutableStateFlow(Result.success(Unit))
        coEvery { repository.parseQr(any()) } returns DashConnectQr(request)
        coEvery { repository.prepareLogin(any()) } returns plan
        coEvery { repository.resolveWalletUsername() } returns "Alice"
        coEvery { repository.resolveAppDisplay(any(), any()) } returns AppDisplay("App", "example.org")
        coEvery { repository.approvePreparedLogin(any(), any()) } throws IllegalStateException("Confirmation uncertain")
        coEvery { repository.revokePreparedLogin(any(), any()) } returns Unit
        model = ConnectionsViewModel(repository, identities)
        model.bindWalletUnlockCheck { true }
    }

    @After fun teardown() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        Constants.SUPPORTS_PLATFORM = originalSupportsPlatform
        Dispatchers.resetMain()
    }

    private fun assertUnsupportedRequestRejected(openedFromUrl: Boolean, purchase: Boolean) {
        model.viewModelScope.cancel()
        Constants.SUPPORTS_PLATFORM = false
        every { identities.initializationResult } returns MutableStateFlow<Result<Unit>?>(null)
        model = ConnectionsViewModel(repository, identities)
        model.bindWalletUnlockCheck { true }
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
        model = ConnectionsViewModel(repository, identities)
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

    private fun failApproval(openedFromUrl: Boolean = false) {
        scan(openedFromUrl)
        model.approvePendingRequest()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.approveResult.value is ConnectionsViewModel.ApproveResult.Error)
        assertTrue(model.uiState.value.requiresRevocation)
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
        every { identities.initializationResult } returns MutableStateFlow<Result<Unit>?>(null)
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
        verify(exactly = 0) { identities.initializationResult }
        coVerify(exactly = 0) { repository.parseQr(any()) }
        coVerify(exactly = 0) { repository.prepareTokenPurchase(any()) }
        every { repository.observeConnections() } returns MutableStateFlow(emptyList())
        model.refreshConnections()
        assertEquals(ConnectionRecordsStatus.LOADING, model.uiState.value.recordsStatus)
        dispatcher.scheduler.runCurrent()
        assertEquals(ConnectionRecordsStatus.READY, model.uiState.value.recordsStatus)
        every { identities.initializationResult } returns MutableStateFlow(Result.success(Unit))
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
        model = ConnectionsViewModel(repository, identities)
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
        model = ConnectionsViewModel(repository, identities)
        var unlocked = !restoredWhileLocked
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

    @Test fun `failed approval must revoke before reporting declined`() {
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
        assertTrue(model.callbackOffer.value!!.contains("ac=declined"))
        coVerify(exactly = 1) { repository.revokePreparedLogin(request, plan) }
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
