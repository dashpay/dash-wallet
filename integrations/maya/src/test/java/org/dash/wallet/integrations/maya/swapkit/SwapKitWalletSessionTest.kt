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

package org.dash.wallet.integrations.maya.swapkit

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.WalletSession
import org.dash.wallet.integrations.maya.api.MayaBlockchainApi
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.junit.Test

/**
 * The swap is paid from the wallet it was confirmed on, on every route the commit-time refresh can
 * return (review 5560810479): the session is taken before the refresh waits, and handed to the
 * Maya vault builder as well as to the plain deposit.
 */
class SwapKitWalletSessionTest {

    private val walletA = WalletSession("wallet A")
    private val walletB = WalletSession("wallet B")
    private var installed: WalletSession? = walletA

    private val sendPaymentService = mockk<SendPaymentService>(relaxed = true) {
        every { currentWalletSession() } answers { installed }
    }
    private val blockchainApi = mockk<MayaBlockchainApi>(relaxed = true)

    private fun aggregator(): SwapKitApiAggregator = spyk(
        SwapKitApiAggregator(
            mockk(relaxed = true),
            blockchainApi,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            sendPaymentService
        )
    )

    private fun model(memo: String?) = SwapTradeUIModel(
        amount = Amount(),
        maximum = false,
        vaultAddress = "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL",
        destinationAddress = "destination",
        outputAsset = "BTC.BTC",
        memo = memo,
        routeName = "MAYACHAIN"
    )

    @Test
    fun `a wallet replaced during the route refresh never funds a Maya route`() = runBlocking {
        val aggregator = aggregator()
        val refreshed = model(memo = "=:BTC.BTC:destination")
        coEvery { aggregator.getSwapInfo(any()) } coAnswers {
            installed = walletB // a wipe installs wallet B while the route is refreshed
            ResponseResource.Success(refreshed)
        }

        aggregator.commitSwapTransaction("trade", model(memo = null))

        // the session of the wallet the user confirmed on, never the replacement's
        coVerify(exactly = 1) { blockchainApi.buildAndSendSwapTx(refreshed, walletA) }
        coVerify(exactly = 0) { blockchainApi.buildAndSendSwapTx(any(), walletB) }
    }
}
