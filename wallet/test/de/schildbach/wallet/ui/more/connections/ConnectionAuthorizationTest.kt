package de.schildbach.wallet.ui.more.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class ConnectionAuthorizationTest {
    @Test fun `mutation without explicit approval authorization is rejected`() {
        assertThrows(IllegalStateException::class.java) { EmptyCoroutineContext.connectionAuthorization() }
    }

    @Test fun `captured signer checks lock and cancellation on every invocation`() {
        var unlocked = true
        val job = Job()
        val authorize = (job + ConnectionAuthorization { unlocked }).connectionAuthorization()
        authorize()
        unlocked = false
        assertThrows(CancellationException::class.java) { authorize() }
        job.cancel()
        unlocked = true
        assertThrows(CancellationException::class.java) { authorize() }
    }
}
