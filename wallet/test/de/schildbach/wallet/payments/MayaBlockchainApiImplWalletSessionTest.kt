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

import de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.WalletSession
import org.dash.wallet.integrations.maya.api.MayaWebApi
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import javax.inject.Provider

/**
 * A Maya vault deposit is paid from the wallet the swap was confirmed on, or not at all (review
 * 5560810479): the session is checked before the build, after it (releasing the reservation), and
 * before the broadcast.
 */
class MayaBlockchainApiImplWalletSessionTest {

    private val walletA = Any()
    private val walletB = Any()
    private var installed: Any = walletA

    /** The payment service's contract: a session names its wallet only while it is installed. */
    private val walletPayments = mockk<WalletSendPaymentService> {
        every { currentWalletSession() } answers { WalletSession(installed) }
        every { walletOf(any()) } answers {
            val session = firstArg<WalletSession?>()
            if (session != null && session.handle !== installed) {
                throw IllegalStateException("the wallet this payment was confirmed on has been wiped")
            }
            null
        }
    }
    private val sdkL1SendService = mockk<SdkL1SendService>(relaxed = true)
    private val mayaWebApi = mockk<MayaWebApi>(relaxed = true)

    private val api = MayaBlockchainApiImpl(
        sdkL1SendService,
        mayaWebApi,
        mockk(relaxed = true),
        mockk(relaxed = true),
        Provider { walletPayments },
        fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock()
    )

    private fun model() = SwapTradeUIModel(
        amount = Amount().apply { dash = BigDecimal("0.01") },
        maximum = false,
        vaultAddress = "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL",
        destinationAddress = "destination",
        outputAsset = "BTC.BTC",
        memo = "=:BTC.BTC:destination"
    )

    @Test
    fun `a wallet replaced during the route refresh never builds the deposit`() = runBlocking {
        coEvery { mayaWebApi.getSwapInfo(any()) } coAnswers {
            installed = walletB // a wipe installs wallet B while the route is refreshed
            ResponseResource.Success(model())
        }

        val result = api.commitSwapTransaction("trade", model())

        assertTrue("refused: $result", result is ResponseResource.Failure)
        coVerify(exactly = 0) { sdkL1SendService.buildDeferredMayaDeposit(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { sdkL1SendService.broadcastDeferredPayment(any(), any()) }
    }

    @Test
    fun `a wallet replaced during the build releases the reservation and sends nothing`() = runBlocking {
        val payment = mockk<SdkDeferredPayment>(relaxed = true)
        coEvery { sdkL1SendService.buildDeferredMayaDeposit(any(), any(), any(), any(), any()) } coAnswers {
            installed = walletB
            payment
        }

        val result = api.buildAndSendSwapTx(model(), WalletSession(walletA))

        assertTrue("refused: $result", result is ResponseResource.Failure)
        val refusal = (result as ResponseResource.Failure).throwable
        assertTrue("refused for its wallet, not anything else: $refusal", refusal is IllegalStateException)
        coVerify(exactly = 1) { sdkL1SendService.releaseDeferredPayment(payment) }
        coVerify(exactly = 0) { sdkL1SendService.broadcastDeferredPayment(any(), any()) }
    }
}
