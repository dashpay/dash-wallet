/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.ui.send

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.util.viewModels.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An asset lock must not be built while the input locks of an unresolved payment are missing.
 *
 * The barrier inside SendCoinsTaskRunner.sendCoins guards the commit, which is too late here: the
 * empty-wallet path completes and signs the request itself, and an outpoint locked after that is
 * not taken back out of a transaction that already chose it. The sibling ordinary send has waited
 * for restoration since it was written; this path was missed.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
// a plain Application: nothing here needs the real one, and booting it would drag in Hilt
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class SendCoinsViewModelAssetLockTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private lateinit var viewModel: SendCoinsViewModel
    private lateinit var sendCoinsTaskRunner: SendCoinsTaskRunner
    private lateinit var wallet: Wallet

    private val address: Address = Address.fromString(
        Constants.NETWORK_PARAMETERS,
        "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL"
    )
    private val amount: Coin = Coin.parseCoin("0.5")
    private val topUpKey = ECKey()

    /** What the dry run produced, and what the real send would have produced. Kept apart so a
     * later dry run cannot be mistaken for the transaction this test says must never be built. */
    private lateinit var dryRunRequest: SendRequest
    private lateinit var assetLockRequest: SendRequest

    @Before
    fun setUp() {
        Context.propagate(Context.getOrCreate(Constants.NETWORK_PARAMETERS))
        dryRunRequest = SendRequest.to(address, amount)
        assetLockRequest = SendRequest.to(address, amount)

        wallet = mockk(relaxed = true)
        every { wallet.context } returns Context.get()
        every { wallet.currentReceiveAddress() } returns address

        val walletDataProvider = mockk<WalletDataProvider>(relaxed = true)
        every { walletDataProvider.wallet } returns wallet
        every { walletDataProvider.observeBalance(any(), any()) } returns flowOf(Coin.COIN)

        val blockchainStateDao = mockk<BlockchainStateDao>(relaxed = true)
        every { blockchainStateDao.observeState() } returns emptyFlow()

        val coinJoinService = mockk<CoinJoinService>(relaxed = true)
        every { coinJoinService.observeMixing() } returns flowOf(false)

        sendCoinsTaskRunner = mockk(relaxed = true)
        // the dry run uses the overload without a greedy flag; the real send uses the one with it
        every {
            sendCoinsTaskRunner.createAssetLockSendRequest(any(), any(), any(), any(), any())
        } returns dryRunRequest
        every {
            sendCoinsTaskRunner.createAssetLockSendRequest(any(), any(), any(), any(), any(), any())
        } returns assetLockRequest

        viewModel = SendCoinsViewModel(
            walletDataProvider,
            mockk<WalletApplication>(relaxed = true),
            blockchainStateDao,
            mockk<BiometricHelper>(relaxed = true),
            mockk<AnalyticsService>(relaxed = true),
            mockk<Configuration>(relaxed = true),
            sendCoinsTaskRunner,
            mockk<NotificationService>(relaxed = true),
            mockk<IdentityRepository>(relaxed = true),
            mockk<PlatformRepo>(relaxed = true),
            mockk<DashPayContactRequestDao>(relaxed = true),
            mockk<CoinJoinConfig>(relaxed = true),
            coinJoinService
        )

        viewModel.isAssetLock = true
        viewModel.setAmount(amount)
        runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromAddress(address, null)) }
        // the dry run has to have run, or signAndSendAssetLock would fail on its result long
        // before it could select any coins and this test would prove nothing
        assertNotNull("the dry run did not produce a send request", viewModel.dryrunSendRequest)
    }

    @Test
    fun `an empty-wallet asset lock selects no coins while pending locks are unrestored`() = runBlocking {
        coEvery { sendCoinsTaskRunner.awaitPaymentReadiness() } throws
            IllegalStateException("pending payments could not be restored")

        var caught: Exception? = null
        try {
            viewModel.signAndSendAssetLock(amount, null, false, topUpKey, emptyWallet = true)
            fail("an asset lock was built although the pending payment locks were missing")
        } catch (e: Exception) {
            caught = e
        }

        verify(exactly = 0) {
            sendCoinsTaskRunner.createAssetLockSendRequest(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { wallet.completeTx(assetLockRequest) }
        assertTrue(
            "failed with ${caught?.javaClass?.simpleName}, not the refusal to proceed",
            caught is IllegalStateException
        )
    }

    @Test
    fun `an asset lock waits for restoration before it is built`() = runBlocking {
        coEvery { sendCoinsTaskRunner.awaitPaymentReadiness() } returns Unit
        coEvery {
            sendCoinsTaskRunner.sendCoins(any<SendRequest>(), any(), any(), any(), any())
        } returns Transaction(Constants.NETWORK_PARAMETERS)

        viewModel.signAndSendAssetLock(amount, null, false, topUpKey, emptyWallet = false)

        coVerifyOrder {
            sendCoinsTaskRunner.awaitPaymentReadiness()
            sendCoinsTaskRunner.createAssetLockSendRequest(any(), any(), any(), any(), any(), any())
        }
    }
}
