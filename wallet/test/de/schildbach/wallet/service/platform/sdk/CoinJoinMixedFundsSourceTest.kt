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

package de.schildbach.wallet.service.platform.sdk

import android.app.Application
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Coin
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.Utils
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.persistence.entities.AccountEntity
import org.dashfoundation.dashsdk.persistence.entities.TxoEntity
import org.bitcoinj.wallet.CoinJoinExtension
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dashfoundation.dashsdk.wallet.PlatformWalletManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TreeMap

/**
 * Which engine [CoinJoinFundsMigrationService.mixedFundsToMigrate] (and so
 * the prompt gate, the sheet amount and the Tools entry) trusts, before and
 * after the cutover. Field bug: an upgraded wallet whose mixed coins were
 * shielded from another device kept being prompted from the frozen dashj
 * snapshot while the SDK correctly read 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class CoinJoinMixedFundsSourceTest {
    private val walletId = "01".repeat(32)
    private val staleDashjDuffs = 5_734_663_282L

    private inner class Fixture(
        scope: TestScope,
        cutover: Boolean,
        sdkJson: String?,
        dashjDuffs: Long = staleDashjDuffs,
        /** The SDK store's settled CoinJoin coin, so the routes can read a snapshot matching [sdkJson]. */
        snapshotDuffs: Long = 150_000_000L
    ) {
        val config = mockk<DashPayConfig>(relaxed = true)
        val send = mockk<SdkL1SendService>(relaxed = true)
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        val sdk = mockk<DashSdkService>(relaxed = true)
        val manager = mockk<PlatformWalletManager>(relaxed = true)
        val blockchain = mockk<BlockchainStateProvider>(relaxed = true)
        val chainState = mockk<BlockchainState>(relaxed = true)
        val walletData = mockk<WalletData>(relaxed = true)
        val service: CoinJoinFundsMigrationService

        init {
            coEvery { send.cutoverCommitted() } returns cutover
            coEvery { send.coinJoinConsolidationGateOpen() } returns true
            coEvery { shield.isWalletShieldingAvailable() } returns true
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } returns ""
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } returns ""
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) } returns false
            coEvery { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_DEFERRED_DUFFS) } returns null
            coEvery { config.getDashjSyncDiagnostic() } returns false
            every { sdk.walletManagerOrNull() } returns manager
            every { manager.wallets } returns MutableStateFlow(mapOf(walletId to mockk()))
            coEvery { manager.accountBalances(any()) } returns sdkJson
            val db = mockk<DashDatabase>(relaxed = true)
            every { sdk.databaseOrNull() } returns db
            coEvery { db.accountDao().getByKey(any(), 1, 0) } returns listOf(
                AccountEntity(id = 7, walletId = Utils.HEX.decode(walletId), accountType = 1, accountIndex = 0,
                    accountTypeName = "CoinJoin")
            )
            every { db.coreAddressDao().observeByAccount(7) } returns MutableStateFlow(emptyList())
            every { db.txoDao().observeUnspentByWallet(any()) } returns MutableStateFlow(listOf(
                TxoEntity(ByteArray(36), 0, snapshotDuffs, "", isConfirmed = true,
                    walletId = Utils.HEX.decode(walletId), accountId = 7L)
            ))
            coEvery { blockchain.getState() } returns chainState
            every { chainState.isSynced() } returns true

            // The held dashj wallet still reports its pre-cutover CoinJoin keychain.
            val output = mockk<TransactionOutput>()
            every { output.value } returns Coin.valueOf(dashjDuffs)
            val coinJoin = mockk<CoinJoinExtension>()
            every { coinJoin.outputs } returns TreeMap(mapOf(0 to listOf(output)))
            val dashjWallet = mockk<WalletEx>()
            every { dashjWallet.coinJoin } returns coinJoin
            @Suppress("DEPRECATION")
            every { walletData.wallet } returns dashjWallet

            service = CoinJoinFundsMigrationService(
                walletData, shield, send, blockchain, config, sdk, scope.backgroundScope, mockk(relaxed = true)
            )
        }
    }

    private fun coinJoinRow(confirmed: Long) = """[{"typeTag":1,"index":0,"confirmed":$confirmed}]"""

    @Test
    fun postCutover_sdkZero_ignoresStaleDashjAndDoesNotPrompt() = runTest {
        val f = Fixture(this, cutover = true, sdkJson = coinJoinRow(0L))
        assertNull(f.service.mixedFundsToMigrate())
        assertFalse(f.service.shouldPrompt())
        @Suppress("DEPRECATION")
        verify(exactly = 0) { f.walletData.wallet }
    }

    @Test
    fun postCutover_sdkPositive_offersTheSdkAmount() = runTest {
        val f = Fixture(this, cutover = true, sdkJson = coinJoinRow(150_000_000L))
        assertEquals(Coin.valueOf(150_000_000L), f.service.mixedFundsToMigrate())
        assertTrue(f.service.shouldPrompt())
    }

    @Test
    fun postCutover_closedConsolidationGateWithoutShielding_doesNotPrompt() = runTest {
        val f = Fixture(this, cutover = true, sdkJson = coinJoinRow(150_000_000L))
        coEvery { f.shield.isWalletShieldingAvailable() } returns false
        coEvery { f.send.coinJoinConsolidationGateOpen() } returns false
        assertFalse(f.service.shouldPrompt())
        coEvery { f.send.coinJoinConsolidationGateOpen() } returns true
        assertTrue(f.service.shouldPrompt())
    }

    @Test
    fun postCutover_sdkUnreadable_isNullNotDashj() = runTest {
        val f = Fixture(this, cutover = true, sdkJson = null)
        assertNull(f.service.mixedFundsToMigrate())
        assertFalse(f.service.shouldPrompt())
    }

    @Test
    fun preCutover_dashjRemainsASource() = runTest {
        val f = Fixture(this, cutover = false, sdkJson = coinJoinRow(0L))
        assertEquals(Coin.valueOf(staleDashjDuffs), f.service.mixedFundsToMigrate())
        // ... but a FRESH prompt is still refused pre-cutover.
        assertFalse(f.service.shouldPrompt())
    }
}
