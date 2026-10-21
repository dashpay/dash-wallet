package de.schildbach.wallet.ui.more.connections

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import de.schildbach.wallet.service.platform.IdentityRepository
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
    private val vector = JsonParser.parseReader(javaClass.getResourceAsStream("/app-connect-vectors.json")!!.reader()).asJsonObject
    private val decoded = DashConnectUri.parseKeyRequest(vector.getAsJsonObject("request")["uri"].asString)
    private val request = decoded.copy(appConnect = decoded.appConnect!!.copy(callback = java.net.URI("https://example.org/return")))
    private val plan = AppConnectPlan("hash", "scope", "identity", request.appConnect!!.auth, "Consent")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeConnections() } returns MutableStateFlow(emptyList())
        every { identities.blockchainIdentity } returns null
        coEvery { repository.parseQr(any()) } returns DashConnectQr(request)
        coEvery { repository.prepareLogin(any()) } returns plan
        coEvery { repository.resolveWalletUsername() } returns "Alice"
        coEvery { repository.resolveAppDisplay(any(), any()) } returns AppDisplay("App", "example.org")
        coEvery { repository.approvePreparedLogin(any(), any()) } throws IllegalStateException("Confirmation uncertain")
        coEvery { repository.revokePreparedLogin(any(), any()) } returns Unit
        model = ConnectionsViewModel(repository, identities)
    }

    @After fun teardown() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
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

    @Test fun `uncertain approval can close without revoking or reporting declined`() {
        failApproval(openedFromUrl = true)
        model.closePendingRequest()
        assertNull(model.uiState.value.pendingRequest)
        assertNull(model.callbackOffer.value)
        assertTrue(model.scanOutcome.value is ConnectionsViewModel.ScanOutcome.Error)
        coVerify(exactly = 0) { repository.revokePreparedLogin(any(), any()) }
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
