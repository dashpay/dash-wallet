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
package de.schildbach.wallet.payments

import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every dashj-side spend of the shared UTXOs (main send UI, CrowdNode,
 * BIP70 — all funneling through [SendCoinsTaskRunner]'s internal
 * `sendCoins(SendRequest, …)`) inflates the SDK's shadow balance by the
 * fee until the next mined block, exactly like a Phase 5b SDK self-spend.
 * These tests pin the guard for that transient:
 * [L1ShadowSyncService.noteSelfSpendBroadcast] is armed on every
 * SUCCESSFUL commit+broadcast — on both the sign-and-commit and the
 * already-completed (BIP70) branches — and NEVER on a failed send.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [29], manifest = Config.NONE)
class SendCoinsTaskRunnerSelfSpendGraceTest {

    private val networkParams: NetworkParameters = TestNet3Params.get()

    private lateinit var wallet: Wallet
    private lateinit var l1ShadowSyncService: L1ShadowSyncService
    private lateinit var runner: SendCoinsTaskRunner

    @Before
    fun setUp() {
        val bitcoinjContext = org.bitcoinj.core.Context.getOrCreate(networkParams)
        org.bitcoinj.core.Context.propagate(bitcoinjContext)

        wallet = mockk(relaxed = true)
        every { wallet.context } returns bitcoinjContext

        val walletDataProvider = mockk<WalletDataProvider>(relaxed = true)
        every { walletDataProvider.wallet } returns wallet

        // Non-interactive signing, same stubbing as the BIP70 tests.
        val securityFunctions = mockk<SecurityFunctions>(relaxed = true)
        mockkStatic(SecurityGuard::class)
        val securityGuard = mockk<SecurityGuard>(relaxed = true)
        every { SecurityGuard.getInstance() } returns securityGuard
        every { securityGuard.retrievePassword() } returns "testPassword"
        every { securityFunctions.deriveKey(any(), any()) } returns mockk(relaxed = true)

        l1ShadowSyncService = mockk(relaxed = true)

        runner = spyk(
            SendCoinsTaskRunner(
                walletDataProvider,
                mockk<WalletApplication>(relaxed = true),
                securityFunctions,
                mockk<PackageInfoProvider>(relaxed = true),
                mockk<AnalyticsService>(relaxed = true),
                mockk<BlockchainIdentityConfig>(relaxed = true),
                mockk<IdentityRepository>(relaxed = true),
                mockk<PlatformRepo>(relaxed = true),
                mockk<TransactionMetadataProvider>(relaxed = true),
                mockk(relaxed = true),
                l1ShadowSyncService
            )
        )
        coEvery { runner.logSendTxEvent(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkStatic(SecurityGuard::class)
    }

    private fun sendRequest(): SendRequest =
        SendRequest.to(Address.fromKey(networkParams, ECKey()), Coin.CENT)

    @Test
    fun successfulDashjSend_armsTheSelfSpendGrace() = runTest {
        // sign-and-commit branch (main UI / CrowdNode): sendCoinsOffline succeeds.
        runner.sendCoins(sendRequest(), checkBalanceConditions = false)

        verify(exactly = 1) { l1ShadowSyncService.noteSelfSpendBroadcast() }
    }

    @Test
    fun successfulCompletedTxSend_armsTheSelfSpendGrace() = runTest {
        // already-completed branch (BIP70): maybeCommitTx succeeds.
        every { wallet.maybeCommitTx(any()) } returns true

        runner.sendCoins(sendRequest(), txCompleted = true, checkBalanceConditions = false)

        verify(exactly = 1) { l1ShadowSyncService.noteSelfSpendBroadcast() }
    }

    @Test
    fun failedDashjSend_doesNotArmTheSelfSpendGrace() = runTest {
        every { wallet.sendCoinsOffline(any()) } throws InsufficientMoneyException(Coin.COIN)

        try {
            runner.sendCoins(sendRequest(), checkBalanceConditions = false)
            fail("expected InsufficientMoneyException")
        } catch (expected: InsufficientMoneyException) {
            // the send never committed — the grace must stay unarmed
        }

        verify(exactly = 0) { l1ShadowSyncService.noteSelfSpendBroadcast() }
    }
}
