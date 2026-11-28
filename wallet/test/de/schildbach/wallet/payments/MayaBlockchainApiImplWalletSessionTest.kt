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
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.WalletSession
import org.dash.wallet.integrations.maya.api.MayaWebApi
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dashfoundation.dashsdk.keywallet.DecodedTransaction
import org.dashfoundation.dashsdk.keywallet.TransactionDecoder
import org.junit.Assert.assertEquals
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

    @Test
    fun `an ambiguous vault broadcast is outcome unknown with its txid, never a retryable error`() = runBlocking {
        val deposit = model()
        val memo = requireNotNull(deposit.memo).toByteArray()
        val txidHex = "ab".repeat(32)
        val payment = mockk<SdkDeferredPayment>(relaxed = true) {
            every { this@mockk.txidHex } returns txidHex
        }
        coEvery { sdkL1SendService.buildDeferredMayaDeposit(any(), any(), any(), any(), any()) } returns payment
        coEvery { sdkL1SendService.broadcastDeferredPayment(payment, any()) } returns
            SdkWriteResult.Ambiguous(IllegalStateException("broadcast timed out"))
        // A well-shaped deposit (vault, memo), so the broadcast is reached.
        mockkObject(TransactionDecoder)
        try {
            every { TransactionDecoder.decode(any(), any()) } returns DecodedTransaction(
                ByteArray(32),
                listOf(DecodedTransaction.Input(ByteArray(32), 0, null)),
                listOf(
                    DecodedTransaction.Output(
                        deposit.vaultAddress,
                        1_000_000L,
                        byteArrayOf(0x76, 0xa9.toByte(), 0x14) + ByteArray(20) +
                            byteArrayOf(0x88.toByte(), 0xac.toByte())
                    ),
                    DecodedTransaction.Output(null, 0L, expectedOpReturnScript(memo))
                )
            )

            val result = api.buildAndSendSwapTx(deposit, WalletSession(walletA))

            assertTrue("failed: $result", result is ResponseResource.Failure)
            val pending = (result as ResponseResource.Failure).throwable
            assertTrue("outcome unknown, not a generic error: $pending", pending is PaymentSubmissionPendingException)
            assertEquals(txidHex, (pending as PaymentSubmissionPendingException).txId)
            coVerify(exactly = 0) { sdkL1SendService.releaseDeferredPayment(any()) }
        } finally {
            unmockkObject(TransactionDecoder)
        }
    }
}
