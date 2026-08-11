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

package de.schildbach.wallet.data

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Context
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.freshReceiveAddressStringOffMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The off-main fresh-address helpers exist because dashj's
 * `freshReceiveAddress()` forces a SYNCHRONOUS full-wallet save (measured
 * 1.2s of `[main]` at 215 DashPay friend chains) — every main-thread call
 * site (LiveData observers, click listeners, dialog callbacks) goes through
 * them instead. These tests pin the contract: the underlying dashj call runs
 * on a DIFFERENT (IO) thread than the caller, the wallet's bitcoinj Context
 * is propagated onto that thread, and results pass through unchanged.
 */
class WalletDataExtTest {

    private val params = TestNet3Params.get()
    private val address = Address.fromBase58(params, "ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U")

    @Test
    fun freshReceiveAddressOffMain_runsTheDashjCallOffTheCallerThread() {
        val callerThread = Thread.currentThread()
        var executionThread: Thread? = null
        val walletData = mockk<WalletData> {
            every { wallet } returns null
            every { freshReceiveAddress() } answers {
                executionThread = Thread.currentThread()
                address
            }
        }

        val result = runBlocking { walletData.freshReceiveAddressOffMain() }

        assertSame(address, result)
        assertNotEquals(
            "the wallet-saving dashj call must not run on the calling thread",
            callerThread,
            executionThread
        )
        assertTrue(
            "expected an IO-dispatcher worker, got ${executionThread?.name}",
            executionThread?.name.orEmpty().contains("DefaultDispatcher")
        )
    }

    @Test
    fun freshReceiveAddressOffMain_propagatesTheWalletContextOntoTheIoThread() {
        // dashj APIs read the thread-local bitcoinj Context; a bare
        // dispatcher hop would leave the IO worker without it. The helper
        // must propagate the WALLET's context (the PaymentsViewModel
        // pattern) before invoking the dashj call.
        val walletContext = Context(params)
        val dashjWallet = mockk<Wallet> {
            every { context } returns walletContext
        }
        var observedContext: Context? = null
        val walletData = mockk<WalletData> {
            every { wallet } returns dashjWallet
            every { freshReceiveAddress() } answers {
                observedContext = Context.get()
                address
            }
        }

        runBlocking { walletData.freshReceiveAddressOffMain() }

        assertSame(walletContext, observedContext)
    }

    @Test
    fun freshReceiveAddressStringOffMain_returnsBase58OfTheSameAddress() {
        val walletData = mockk<WalletData> {
            every { wallet } returns null
            every { freshReceiveAddress() } returns address
        }

        val result = runBlocking { walletData.freshReceiveAddressStringOffMain() }

        assertEquals(address.toBase58(), result)
    }

    @Test
    fun walletDataProvider_freshReceiveAddressStringOffMain_runsOffTheCallerThread() {
        // The neutral (common-module) variant used by feature/integration
        // callers — same off-main contract, stubbable via the interface
        // method (it is an extension, not an interface member, precisely so
        // existing fakes keep working).
        val callerThread = Thread.currentThread()
        var executionThread: Thread? = null
        val provider = mockk<WalletDataProvider> {
            every { freshReceiveAddressString() } answers {
                executionThread = Thread.currentThread()
                "ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U"
            }
        }

        val result = runBlocking { provider.freshReceiveAddressStringOffMain() }

        assertEquals("ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U", result)
        assertNotEquals(callerThread, executionThread)
        assertTrue(
            "expected an IO-dispatcher worker, got ${executionThread?.name}",
            executionThread?.name.orEmpty().contains("DefaultDispatcher")
        )
    }
}
