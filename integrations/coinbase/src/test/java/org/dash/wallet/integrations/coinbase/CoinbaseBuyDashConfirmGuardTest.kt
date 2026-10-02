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

package org.dash.wallet.integrations.coinbase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.dash.wallet.integrations.coinbase.model.Balance
import org.dash.wallet.integrations.coinbase.model.CoinbaseAccount
import org.dash.wallet.integrations.coinbase.model.CoinbaseErrorType
import org.dash.wallet.integrations.coinbase.model.PaymentMethodsData
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseBuyDashViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The confirm single-flight on the buy-Dash order review.
 *
 * The confirm button launches a coroutine whose FIRST step — reading the deposit
 * destination — blocks: post-cutover it waits for the SDK engine to bind and
 * then takes the engine's wallet-manager lock. Nothing is on screen for that
 * wait, so repeated taps each started their own coroutine and each one that got
 * an address placed its OWN order under a fresh idempotency UUID (and, on the
 * bank path, repeated the fiat deposit). Coinbase cannot collapse those —
 * different UUIDs are different orders by definition — so the user is charged
 * twice. The guard is taken synchronously on the tap, which is the only moment
 * at which two taps are ordered against each other.
 */
class CoinbaseBuyDashConfirmGuardTest {

    private val repository = mockk<CoinBaseRepositoryInt>(relaxed = true)
    private val walletDataProvider = mockk<WalletDataProvider>(relaxed = true)
    private lateinit var viewModel: CoinbaseBuyDashViewModel

    /** Parks the FIRST destination read, modelling the blocking engine seam. */
    private val addressReads = AtomicInteger(0)
    private val readParked = CountDownLatch(1)
    private val releaseRead = CountDownLatch(1)

    private val fiatAccount = CoinbaseAccount(
        uuid = UUID.randomUUID(),
        name = "USD Wallet",
        currency = "USD",
        // Deliberately LESS than the order, so the bank-deposit arm runs and the
        // deposit is covered by the same guard as the order.
        availableBalance = Balance("1.00", "USD"),
        default = true,
        active = true,
        type = "COINBASE_FIAT_ACCOUNT",
        ready = true
    )

    private val bankAccount = PaymentMethodsData(
        id = "bank-1",
        type = "ACH",
        name = "Test Bank ********1234",
        currency = "USD"
    )

    @Before
    fun setUp() {
        every { walletDataProvider.freshReceiveAddressStringLive() } answers {
            if (addressReads.getAndIncrement() == 0) {
                readParked.countDown()
                check(releaseRead.await(10, TimeUnit.SECONDS)) { "the destination read was never released" }
            }
            "yENGINEnextUnusedAddress"
        }
        coEvery { repository.getExchangeRates(any()) } returns mapOf("DASH" to "0.02")
        coEvery { repository.getFiatAccount() } returns fiatAccount
        coEvery { repository.getActivePaymentMethods() } returns listOf(bankAccount)

        viewModel = CoinbaseBuyDashViewModel(
            repository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            walletDataProvider
        )
    }

    /** Exactly what `CoinbaseOrderReviewFragment`'s confirm listener does. */
    private suspend fun confirmTap(): Boolean {
        if (!viewModel.tryBeginConfirm()) return false
        viewModel.getTransferDashParams()
        viewModel.buyDash()
        return true
    }

    @Test
    fun aSecondTapWhileTheDestinationReadBlocksPlacesNoSecondOrder() = runBlocking {
        assertEquals(
            CoinbaseErrorType.NONE,
            viewModel.validateBuyDash(Dash.parse("0.1"), retryWithDeposit = true)
        )

        val firstTap = launch(Dispatchers.Default) { confirmTap() }
        assertTrue(
            "the first tap must be parked in the destination read",
            readParked.await(10, TimeUnit.SECONDS)
        )

        // The second tap lands in exactly the window the guard exists for: the
        // first coroutine is running but has shown nothing and placed nothing.
        val secondTapAccepted = withContext(Dispatchers.Default) { confirmTap() }

        assertFalse("a second tap must not begin a second confirm", secondTapAccepted)
        releaseRead.countDown()
        firstTap.join()

        coVerify(exactly = 1) { repository.depositToFiatAccount(any(), any()) }
        coVerify(exactly = 1) { repository.placeBuyOrder(any()) }
    }

    @Test
    fun theGuardIsGivenBackAfterARetryableFailure() {
        // Nothing was bought — an unavailable destination, a declined order — so
        // the user must be able to try again. A latched guard would leave the
        // confirm button dead for the rest of the screen's life.
        assertTrue(viewModel.tryBeginConfirm())
        assertFalse(viewModel.tryBeginConfirm())

        viewModel.endConfirm()

        assertTrue("a retryable failure must re-arm the confirm", viewModel.tryBeginConfirm())
    }

    @Test
    fun placingAnOrderOutsideTheSingleFlightIsRefused() {
        // The tripwire at the dangerous site: reaching placeBuyOrder without the
        // guard IS the double purchase, so it fails loudly rather than billing.
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { viewModel.buyDash() }
        }
        assertTrue(thrown.message?.contains("single-flight") == true)
    }
}
