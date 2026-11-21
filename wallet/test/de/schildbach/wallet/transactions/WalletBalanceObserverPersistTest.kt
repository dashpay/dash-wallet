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
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.WalletUIConfig
import org.junit.Assert.assertEquals
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
    private val wallet = mockk<Wallet>(relaxed = true).also {
        every { it.getBalance(any<Wallet.BalanceType>()) } returns balance
    }
    private val walletUIConfig = mockk<WalletUIConfig>(relaxed = true) {
        coEvery { get(WalletUIConfig.LAST_TOTAL_BALANCE) } returns null
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
    fun `before the cutover the dashj balance is stored with its flag`() {
        emitWithState(CutoverState.DUAL_RUNNING.name)

        coVerify(exactly = 1) { walletUIConfig.set(WalletUIConfig.LAST_TOTAL_BALANCE, balance.value) }
        coVerify(exactly = 1) { walletUIConfig.set(WalletUIConfig.LAST_TOTAL_BALANCE_OWNED, true) }
    }

    @Test
    fun `after the cutover a dashj diagnostic estimate is not stored`() {
        val observer = emitWithState(CutoverState.CUT_OVER.name)

        assertEquals("still shown live", balance, observer.totalBalance.value)
        coVerify(exactly = 0) { walletUIConfig.set(WalletUIConfig.LAST_TOTAL_BALANCE, any<Long>()) }
        coVerify(exactly = 0) { walletUIConfig.set(WalletUIConfig.LAST_TOTAL_BALANCE_OWNED, any<Boolean>()) }
    }

    @Test
    fun `an unreadable cutover state stores nothing`() {
        coEvery { dashPayConfig.getPreservingErrors(DashPayConfig.CUTOVER_STATE) } throws IOException("unreadable")
        val observer = WalletBalanceObserver(wallet, walletUIConfig, dashPayConfig)
        observer.emitBalances()
        runBlocking { withTimeout(5_000) { observer.totalBalance.first { it == balance } } }

        coVerify(exactly = 0) { walletUIConfig.set(WalletUIConfig.LAST_TOTAL_BALANCE, any<Long>()) }
    }
}
