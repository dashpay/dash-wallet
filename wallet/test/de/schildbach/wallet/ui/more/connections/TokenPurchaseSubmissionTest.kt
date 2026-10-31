package de.schildbach.wallet.ui.more.connections

import android.app.Application
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.dash.wallet.common.WalletDataProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class TokenPurchaseSubmissionTest {
    private fun config() = DashConnectConfig(RuntimeEnvironment.getApplication(), mockk<WalletDataProvider>(relaxed = true))
    private fun receipt() = JSONObject().put("id", "scope:purchase").put("status", "outcomeUnknown")

    @Test fun `failed preflight never fences or submits`() = runBlocking {
        val config = config()
        config.clearAll()
        val handoff = TokenPurchaseSubmission()
        try {
            withContext(handoff) {
                submitWithPurchaseReceipt(config, receipt(), { error("Scope changed") }) { fail("Must not submit") }
            }
            fail("Must fail preflight")
        } catch (_: IllegalStateException) { }
        assertFalse(handoff.started)
        assertFalse(config.purchaseWasSubmitted("scope:purchase"))
    }

    @Test fun `lock during disk write removes unsubmitted fence and permits explicit retry`() = runBlocking {
        val config = config()
        config.clearAll()
        val handoff = TokenPurchaseSubmission()
        var checks = 0
        try {
            withContext(handoff) {
                submitWithPurchaseReceipt(config, receipt(), {
                    if (++checks == 2) throw CancellationException("Wallet locked")
                }) { fail("Must not submit") }
            }
            fail("Must cancel")
        } catch (_: CancellationException) { }
        assertFalse(handoff.started)
        assertFalse(config.purchaseWasSubmitted("scope:purchase"))
        val retry = TokenPurchaseSubmission()
        withContext(retry) { submitWithPurchaseReceipt(config, receipt(), {}) {} }
        assertTrue(retry.started)
        assertTrue(config.purchaseWasSubmitted("scope:purchase"))
    }

    @Test fun `native error or cancellation retains durable fence`() = runBlocking {
        val config = config()
        for (cancelled in listOf(false, true)) {
            config.clearAll()
            val handoff = TokenPurchaseSubmission()
            try {
                withContext(handoff) {
                    submitWithPurchaseReceipt(config, receipt(), {}) {
                        if (cancelled) throw CancellationException("Interrupted") else error("Native timeout")
                    }
                }
                fail("Must fail submission")
            } catch (error: Exception) {
                assertTrue(if (cancelled) error is CancellationException else error is TokenPurchaseOutcomeUnknown)
            }
            assertTrue(handoff.started)
            assertTrue(config.purchaseWasSubmitted("scope:purchase"))
        }
    }

    @Test fun `real job cancellation during receipt persistence cleans up before returning`() = runBlocking {
        val config = mockk<DashConnectConfig>()
        val writing = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        var fenced = false
        coEvery { config.savePurchase(any()) } coAnswers {
            fenced = true
            writing.complete(Unit)
            finishWrite.await()
        }
        coEvery { config.removeUnsubmittedPurchase(any()) } coAnswers { fenced = false }
        val handoff = TokenPurchaseSubmission()
        val job = launch(handoff + ConnectionAuthorization { true }) {
            val authorize = coroutineContext.connectionAuthorization()
            submitWithPurchaseReceipt(config, receipt(), authorize) { fail("Must not submit") }
        }
        writing.await()
        job.cancel()
        finishWrite.complete(Unit)
        job.join()
        assertFalse(fenced)
        assertFalse(handoff.started)
        coVerify(exactly = 1) { config.removeUnsubmittedPurchase("scope:purchase") }
    }
}
