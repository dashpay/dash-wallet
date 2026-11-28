package de.schildbach.wallet.payments

import de.schildbach.wallet.data.WalletData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.services.WalletSession
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The approval of the amount can suspend, and the wallet installed after it may be another. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [29], manifest = Config.NONE)
class FakeDashSpendServiceTest {
    private val confirmedOn = mockk<Wallet>()
    private val sessionAtTheTap = WalletSession(confirmedOn)
    private var installed: WalletSession? = sessionAtTheTap
    private val real = mockk<WalletSendPaymentService> {
        every { currentWalletSession() } answers { installed }
        every { walletOf(null) } returns null
        every { walletOf(sessionAtTheTap) } returns confirmedOn
        coEvery {
            sendCoins(any<Address>(), any<Coin>(), any(), any(), any(), any(), any())
        } returns mockk<Transaction> { every { txId } returns Sha256Hash.ZERO_HASH }
    }
    private val service = FakeDashSpendService(
        real,
        mockk<WalletData> { every { networkParameters } returns TestNet3Params.get() }
    )

    private fun pay(approveAmount: suspend () -> Unit) = runBlocking {
        service.payWithDashUrlTx("dashspend://pay?amount=1000", null, null, null, { approveAmount() }, null)
    }

    @Test
    fun walletReplacedDuringTheApproval_doesNotPay() {
        pay { installed = WalletSession(mockk<Wallet>()) }

        coVerify { real.sendCoins(any<Address>(), Coin.valueOf(1000), any(), any(), any(), any(), confirmedOn) }
    }

    @Test
    fun noWallet_isRefusedBeforeTheApproval() {
        installed = null
        var isAsked = false

        assertThrows(IllegalStateException::class.java) { pay { isAsked = true } }

        assert(!isAsked)
        coVerify(exactly = 0) { real.sendCoins(any<Address>(), any<Coin>(), any(), any(), any(), any(), any()) }
    }
}
