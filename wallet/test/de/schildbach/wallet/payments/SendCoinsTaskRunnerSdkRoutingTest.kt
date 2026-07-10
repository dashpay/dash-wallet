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

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5b routing tests for [SendCoinsTaskRunner]'s NEUTRAL
 * `sendCoins(String, Dash)` overload — the only send routed through the
 * Kotlin SDK this phase.
 *
 * Invariants under test (the no-double-broadcast contract):
 * - `Broadcast(txid)` → the txid is returned and the dashj send NEVER runs;
 * - `NotBroadcast` → exactly one dashj send runs (unchanged fall-through);
 * - `Ambiguous` → the failure surfaces as a thrown error and the dashj
 *   send NEVER runs (no dashj retry after a maybe-sent SDK tx);
 * - the SDK route's `beforeBroadcast` hook enforces the same
 *   leftover-balance conditions as the dashj path.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [29], manifest = Config.NONE)
class SendCoinsTaskRunnerSdkRoutingTest {

    private val networkParams: NetworkParameters = TestNet3Params.get()
    private val sdkTxid = "ab".repeat(32)
    private val amount = Dash(1_000_000)

    private lateinit var walletDataProvider: WalletDataProvider
    private lateinit var sdkL1SendService: SdkL1SendService
    private lateinit var runner: SendCoinsTaskRunner
    private lateinit var wallet: Wallet
    private lateinit var recipientAddress: String

    @Before
    fun setUp() {
        org.bitcoinj.core.Context.propagate(org.bitcoinj.core.Context.getOrCreate(networkParams))
        javaClass.getResourceAsStream("coinjoin.wallet").use {
            wallet = WalletProtobufSerializer().readWallet(it)
        }
        // A valid testnet address that is NOT in the fixture wallet.
        recipientAddress = Address.fromKey(networkParams, ECKey()).toBase58()

        walletDataProvider = mockk(relaxed = true)
        every { walletDataProvider.wallet } returns wallet
        every { walletDataProvider.networkParameters } returns networkParams

        sdkL1SendService = mockk()

        runner = spyk(
            SendCoinsTaskRunner(
                walletDataProvider,
                mockk<WalletApplication>(relaxed = true),
                mockk<SecurityFunctions>(relaxed = true),
                mockk<PackageInfoProvider>(relaxed = true),
                mockk<AnalyticsService>(relaxed = true),
                mockk<BlockchainIdentityConfig>(relaxed = true),
                mockk<IdentityRepository>(relaxed = true),
                mockk<PlatformRepo>(relaxed = true),
                mockk<TransactionMetadataProvider>(relaxed = true),
                sdkL1SendService
            )
        )
    }

    /** Stubs the dashj-typed overload so the fall-through is observable without a live send. */
    private fun stubDashjSend(): Transaction {
        val tx = mockk<Transaction>()
        every { tx.txId } returns org.bitcoinj.core.Sha256Hash.wrap("cd".repeat(32))
        coEvery {
            runner.sendCoins(any<Address>(), any<Coin>(), any(), any(), any(), any(), any())
        } returns tx
        return tx
    }

    @Test
    fun sdkBroadcast_returnsSdkTxid_andNeverRunsTheDashjSend() = runTest {
        stubDashjSend()
        coEvery {
            sdkL1SendService.sendToAddress(recipientAddress, amount, false, any())
        } returns SdkWriteResult.Broadcast(sdkTxid)

        val txid = runner.sendCoins(recipientAddress, amount)

        assertEquals(sdkTxid, txid)
        coVerify(exactly = 0) {
            runner.sendCoins(any<Address>(), any<Coin>(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun sdkNotBroadcast_fallsThroughToExactlyOneDashjSend() = runTest {
        stubDashjSend()
        coEvery {
            sdkL1SendService.sendToAddress(recipientAddress, amount, false, any())
        } returns SdkWriteResult.NotBroadcast("flag off")

        val txid = runner.sendCoins(recipientAddress, amount)

        assertEquals("cd".repeat(32), txid)
        coVerify(exactly = 1) {
            runner.sendCoins(any<Address>(), any<Coin>(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun sdkAmbiguous_surfacesTheError_andNeverRetriesViaDashj() = runTest {
        stubDashjSend()
        val cause = RuntimeException("broadcast outcome unknown")
        coEvery {
            sdkL1SendService.sendToAddress(recipientAddress, amount, false, any())
        } returns SdkWriteResult.Ambiguous(cause)

        try {
            runner.sendCoins(recipientAddress, amount)
            fail("an ambiguous SDK outcome must surface as an error")
        } catch (e: RuntimeException) {
            assertSame(cause, e)
        }
        coVerify(exactly = 0) {
            runner.sendCoins(any<Address>(), any<Coin>(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun dashjParameters_matchTheNeutralArguments_onFallThrough() = runTest {
        val addressSlot = slot<Address>()
        val amountSlot = slot<Coin>()
        val tx = mockk<Transaction>()
        every { tx.txId } returns org.bitcoinj.core.Sha256Hash.wrap("ef".repeat(32))
        coEvery {
            runner.sendCoins(capture(addressSlot), capture(amountSlot), any(), any(), any(), any(), any())
        } returns tx
        coEvery {
            sdkL1SendService.sendToAddress(any(), any(), any(), any())
        } returns SdkWriteResult.NotBroadcast("gate closed")

        runner.sendCoins(recipientAddress, amount, emptyWallet = false, checkBalanceConditions = false)

        assertEquals(recipientAddress, addressSlot.captured.toBase58())
        assertEquals(amount.duffs, amountSlot.captured.value)
    }

    @Test
    fun beforeBroadcastHook_runsTheLeftoverBalanceCheck_whenConditionsRequested() = runTest {
        stubDashjSend()
        // Make the SDK service invoke the hook, as the real service does
        // after its preflights pass.
        coEvery {
            sdkL1SendService.sendToAddress(recipientAddress, amount, false, any())
        } coAnswers {
            arg<suspend () -> Unit>(3).invoke()
            SdkWriteResult.Broadcast(sdkTxid)
        }

        runner.sendCoins(recipientAddress, amount, checkBalanceConditions = true)

        coVerify(exactly = 1) {
            walletDataProvider.checkSendingConditions(
                match { it.toBase58() == recipientAddress },
                Coin.valueOf(amount.duffs)
            )
        }
    }

    @Test
    fun beforeBroadcastHook_skipsTheCheck_whenConditionsNotRequested() = runTest {
        stubDashjSend()
        coEvery {
            sdkL1SendService.sendToAddress(recipientAddress, amount, false, any())
        } coAnswers {
            arg<suspend () -> Unit>(3).invoke()
            SdkWriteResult.Broadcast(sdkTxid)
        }

        runner.sendCoins(recipientAddress, amount, checkBalanceConditions = false)

        coVerify(exactly = 0) { walletDataProvider.checkSendingConditions(any(), any()) }
    }

    @Test
    fun leftoverBalanceException_fromTheHook_propagatesLikeTheDashjPath() = runTest {
        stubDashjSend()
        val leftover = LeftoverBalanceException(Coin.COIN, "leftover")
        coEvery { walletDataProvider.checkSendingConditions(any(), any()) } throws leftover
        coEvery {
            sdkL1SendService.sendToAddress(recipientAddress, amount, false, any())
        } coAnswers {
            arg<suspend () -> Unit>(3).invoke()
            fail("must not reach the broadcast after a failed pre-send condition")
            SdkWriteResult.Broadcast(sdkTxid)
        }

        try {
            runner.sendCoins(recipientAddress, amount, checkBalanceConditions = true)
            fail("expected LeftoverBalanceException")
        } catch (e: LeftoverBalanceException) {
            assertSame(leftover, e)
        }
        coVerify(exactly = 0) {
            runner.sendCoins(any<Address>(), any<Coin>(), any(), any(), any(), any(), any())
        }
    }
}
