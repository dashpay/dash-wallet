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
package de.schildbach.wallet.transactions

import android.app.Application
import de.schildbach.wallet.service.platform.sdk.CutoverState
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.core.Coin
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.WalletUIConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The dashj observer stores the balance only while dashj owns L1. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class WalletBalanceObserverPersistTest {
    private val balance = Coin.valueOf(42_000L)
    private val watchingKey = HDKeyDerivation.createMasterPrivateKey(ByteArray(32) { 1 }).dropPrivateBytes()
    private val walletId = watchingKey.pubKeyHash.joinToString("") { "%02x".format(it) }
    private val wallet = mockk<Wallet>(relaxed = true).also {
        every { it.getBalance(any<Wallet.BalanceType>()) } returns balance
        every { it.watchingKey } returns watchingKey
    }
    private val walletUIConfig = mockk<WalletUIConfig>(relaxed = true) {
        coEvery { getLastTotalBalance(any()) } returns null
    }
    private val dashPayConfig = mockk<DashPayConfig>(relaxed = true)

    private fun emitWithState(state: String?): WalletBalanceObserver {
        coEvery { dashPayConfig.getPreservingErrors(DashPayConfig.CUTOVER_STATE) } returns state
        return WalletBalanceObserver(wallet, walletUIConfig, dashPayConfig).also { observer ->
            observer.emitBalances()
            // The emitted balance is published after the persist decision.
            runBlocking { withTimeout(5_000) { observer.totalBalance.first { it == balance } } }
        }
    }

    @Test
    fun `before the cutover the dashj balance is stored as this wallet's`() {
        emitWithState(CutoverState.DUAL_RUNNING.name)

        coVerify(exactly = 1) { walletUIConfig.setLastTotalBalance(balance.value, walletId) }
    }

    @Test
    fun `the launch seed is read as this wallet's`() {
        val seeded = Coin.valueOf(7_000L)
        coEvery { walletUIConfig.getLastTotalBalance(walletId) } returns seeded.value
        val observer = WalletBalanceObserver(wallet, walletUIConfig, dashPayConfig)

        runBlocking { withTimeout(5_000) { observer.totalBalance.first { it == seeded } } }
    }

    /**
     * An untagged value an earlier version stored (or none at all) is unknown, not zero: the
     * observer stays unseeded so synchronous readers read the wallet itself.
     */
    @Test
    fun `no value stored as this wallet's leaves the observer unseeded`() {
        val observer = WalletBalanceObserver(wallet, walletUIConfig, dashPayConfig)

        coVerify(timeout = 5_000) { walletUIConfig.getLastTotalBalance(walletId) }
        Thread.sleep(100)
        assertFalse(observer.isSeeded)
        assertEquals(Coin.ZERO, observer.totalBalance.value)
    }

    @Test
    fun `after the cutover a dashj diagnostic estimate is not stored`() {
        val observer = emitWithState(CutoverState.CUT_OVER.name)

        assertEquals("still shown live", balance, observer.totalBalance.value)
        coVerify(exactly = 0) { walletUIConfig.setLastTotalBalance(any(), any()) }
    }

    @Test
    fun `an unreadable cutover state stores nothing`() {
        coEvery { dashPayConfig.getPreservingErrors(DashPayConfig.CUTOVER_STATE) } throws IOException("unreadable")
        val observer = WalletBalanceObserver(wallet, walletUIConfig, dashPayConfig)
        observer.emitBalances()
        runBlocking { withTimeout(5_000) { observer.totalBalance.first { it == balance } } }

        coVerify(exactly = 0) { walletUIConfig.setLastTotalBalance(any(), any()) }
    }
}
