package de.schildbach.wallet.service

import android.app.Application
import android.os.HandlerThread
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class RefusedWalletInitializationTest {
    @Test
    fun `refusal releases command and cleanup waiters before stopping each instance`() = runBlocking {
        repeat(2) {
            val service = spyk(BlockchainServiceImpl())
            val latch = ReflectionHelpers.getField<CompletableDeferred<Unit>>(service, "onCreateCompleted")
            try {
                assertFalse(latch.isCompleted)
                val command = launch(start = CoroutineStart.UNDISPATCHED) { latch.await() }
                val cleanup = launch(start = CoroutineStart.UNDISPATCHED) { latch.await() }
                every { service.stopSelf() } answers {
                    assertTrue(latch.isCompleted)
                    assertFalse(ReflectionHelpers.getField<Boolean>(service, "initCompleted"))
                }

                service.refuseWalletInitialization()

                withTimeout(1_000) {
                    command.join()
                    cleanup.join()
                }
                verify(exactly = 1) { service.stopSelf() }
                assertFalse(ReflectionHelpers.getField<Boolean>(service, "initCompleted"))
            } finally {
                latch.complete(Unit)
                ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
                ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
            }
        }
    }
}
