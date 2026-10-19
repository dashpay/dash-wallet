package de.schildbach.wallet.ui.dashpay

import android.app.Application
import de.schildbach.wallet.service.platform.ContestedUsernameFees
import de.schildbach.wallet.service.platform.PlatformService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PlatformRepoProtocolVersionTest {
    private val platform = mockk<PlatformService>(relaxed = true)
    private lateinit var repository: PlatformRepo

    @Before
    fun setUp() {
        ContestedUsernameFees.reset()
        repository = PlatformRepo(mockk(relaxed = true), mockk(relaxed = true), platform,
            mockk(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        ContestedUsernameFees.reset()
    }

    @Test
    fun successfulRefresh_updatesAndReturnsVersion() = runBlocking {
        every { platform.client.refreshProtocolVersion() } returns 14
        assertEquals(14, repository.refreshProtocolVersion())
        assertEquals(14, ContestedUsernameFees.protocolVersions.value)
        assertEquals(Coin.parseCoin("0.15"), ContestedUsernameFees.fee)
    }

    @Test
    fun failedInitialRefresh_retainsHigherFees() = runBlocking {
        every { platform.client.refreshProtocolVersion() } throws IllegalStateException("offline")
        assertEquals(0, repository.refreshProtocolVersion())
        assertEquals(Coin.parseCoin("0.25"), ContestedUsernameFees.fee)
    }

    @Test
    fun failedRefresh_preservesPreviouslyLearnedVersion() = runBlocking {
        every { platform.client.refreshProtocolVersion() } returns 14
        repository.refreshProtocolVersion()
        every { platform.client.refreshProtocolVersion() } throws IllegalStateException("offline")
        assertEquals(14, repository.refreshProtocolVersion())
        assertEquals(Coin.parseCoin("0.15"), ContestedUsernameFees.fee)
    }

    @Test(expected = CancellationException::class)
    fun cancellation_isNotTreatedAsNetworkFailure() = runBlocking {
        every { platform.client.refreshProtocolVersion() } throws CancellationException("cancelled")
        repository.refreshProtocolVersion()
        Unit
    }
}
