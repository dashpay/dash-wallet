/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.util.services

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.ZeroConfCoinSelector
import org.dash.wallet.common.transactions.ByAddressCoinSelector
import org.junit.Test

@ExperimentalCoroutinesApi
class SendCoinsTaskRunnerTest {
    @Test
    fun sendCoins_coinSelectorSet_correctCoinSelector() {
        val wallet = mockk<Wallet>()
        every { wallet.context } returns Context(MainNetParams.get())
        val application = mockk<WalletApplication>()

        val sendCoinsTaskRunner = SendCoinsTaskRunner(application, mockk(), mockk(), mockk())
        val request = sendCoinsTaskRunner.createSendRequest(
            Address.fromBase58(MainNetParams.get(), "XjBya4EnibUyxubEA8D2Y8KSrBMW1oHq5U"),
            Coin.COIN,
            ByAddressCoinSelector(Address.fromBase58(MainNetParams.get(), "XdYM3BWPrTEXGSFtRcR8QJSfXfefmcNaTr")),
            false
        )

        assertTrue(request.coinSelector is ByAddressCoinSelector)
    }

    @Test
    fun sendCoins_nullCoinSelector_zeroConfSelectorByDefault() {
        val wallet = mockk<Wallet>()
        every { wallet.context } returns Context(MainNetParams.get())
        val application = mockk<WalletApplication>()

        val sendCoinsTaskRunner = SendCoinsTaskRunner(application, mockk(), mockk(), mockk())
        val request = sendCoinsTaskRunner.createSendRequest(
            Address.fromBase58(MainNetParams.get(), "XjBya4EnibUyxubEA8D2Y8KSrBMW1oHq5U"),
            Coin.COIN,
            null,
            false
        )

        assertTrue(request.coinSelector is ZeroConfCoinSelector)
    }
}