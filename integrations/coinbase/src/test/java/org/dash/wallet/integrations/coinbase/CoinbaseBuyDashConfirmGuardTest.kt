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
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.ReceiveAddressUnavailableException
import org.dash.wallet.integrations.coinbase.model.Balance
import org.dash.wallet.integrations.coinbase.model.CoinbaseAccount
import org.dash.wallet.integrations.coinbase.model.CoinbaseErrorType
import org.dash.wallet.integrations.coinbase.model.PaymentMethodsData
import org.dash.wallet.integrations.coinbase.model.PlaceOrderResponse
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseBuyDashViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The confirm single-flight on the buy-Dash order review.
 *
 * The confirm button launches a coroutine whose FIRST step — reading the deposit
 * destination — blocks: post-cutover it waits for the SDK engine to bind and
 * then takes the engine's wallet-manager lock, in a sleep that does not answer
 * cancellation. Nothing is on screen for that wait, so repeated taps each
 * started their own coroutine and each one that got an address placed its OWN
 * order under a fresh idempotency UUID (and, on the bank path, repeated the
 * fiat deposit). Coinbase cannot collapse those — different UUIDs are different
 * orders by definition — so the user is charged twice. The flight is taken
 * synchronously on the tap, which is the only moment at which two taps are
 * ordered against each other.
 *
 * It is taken in the RETAINED view model, which outlives the fragment, so the
 * second half matters as much: an attempt that was cancelled by a fragment
 * recreation keeps running until that uncancellable read returns, and when it
 * finally unwinds it must not give away a flight that by then belongs to the
 * attempt the recreated screen started.
 *
 * These exercise the view model's ownership directly plus a faithful model of
 * the fragment's confirm listener, [ReviewScreen]. A real fragment would need
 * Robolectric, which cannot start in this build (no conscrypt_jni), so the
 * fragment's own three call sites — the tap, `onResume` and `onDestroy` — are
 * modelled rather than driven.
 */
class CoinbaseBuyDashConfirmGuardTest {

    private val repository = mockk<CoinBaseRepositoryInt>(relaxed = true)
    private val walletDataProvider = mockk<WalletDataProvider>(relaxed = true)
    private lateinit var viewModel: CoinbaseBuyDashViewModel

    /** Counted INSIDE the mocks: the assertions must not race the attempts. */
    private val addressReads = AtomicInteger(0)
    private val deposits = AtomicInteger(0)
    private val orders = AtomicInteger(0)

    /** What the modelled screen showed instead of navigating to 2FA. */
    private val purchaseErrors = CopyOnWriteArrayList<Throwable>()
    private val unavailableAddressRetries = AtomicInteger(0)

    /** Set to park the NEXT destination read, modelling the blocking engine seam. */
    private val parkNextRead = AtomicReference<Handshake?>(null)

    /** Set to fail the NEXT destination read the way a pre-cutover engine does. */
    private val failNextRead = AtomicBoolean(false)

    /** A real two-way rendezvous: one side proves it arrived, the other lets it go. */
    private class Handshake {
        val reached = CountDownLatch(1)
        val proceed = CountDownLatch(1)

        fun park(what: String) {
            reached.countDown()
            check(proceed.await(AWAIT_SECONDS, TimeUnit.SECONDS)) { "$what was never released" }
        }
    }

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
            parkNextRead.getAndSet(null)?.park("the destination read")

            if (failNextRead.compareAndSet(true, false)) {
                throw ReceiveAddressUnavailableException()
            }

            addressReads.incrementAndGet()
            "yENGINEnextUnusedAddress"
        }
        coEvery { repository.getExchangeRates(any()) } returns mapOf("DASH" to "0.02")
        coEvery { repository.getFiatAccount() } returns fiatAccount
        coEvery { repository.getActivePaymentMethods() } returns listOf(bankAccount)
        coEvery { repository.depositToFiatAccount(any(), any()) } answers { deposits.incrementAndGet() }
        coEvery { repository.placeBuyOrder(any()) } answers {
            orders.incrementAndGet()
            PlaceOrderResponse(
                success = true,
                failureReason = "",
                orderId = "order-1",
                errorResponse = null,
                successResponse = null,
                orderConfiguration = null
            )
        }

        viewModel = CoinbaseBuyDashViewModel(
            repository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            walletDataProvider
        )
    }

    /**
     * One instance of `CoinbaseOrderReviewFragment`, with the three places that
     * touch the flight: the confirm tap, `onResume` and `onDestroy`. The attempt
     * id is instance state; the flight it names is not.
     */
    private inner class ReviewScreen {
        private var attemptId: Long? = null
        private var confirmJob: Job? = null

        /** Counted down once this instance's confirm has fully unwound. */
        val confirmFinished = CountDownLatch(1)

        fun tap(scope: CoroutineScope, beforeBuying: Handshake? = null): Long? {
            // Synchronously, on the tap, before anything is launched.
            val id = viewModel.tryBeginConfirm() ?: return null
            attemptId = id
            confirmJob = scope.launch {
                try {
                    try {
                        viewModel.getTransferDashParams()
                    } catch (_: ReceiveAddressUnavailableException) {
                        // The toast-and-retry arm: nothing was bought.
                        unavailableAddressRetries.incrementAndGet()
                        return@launch
                    }

                    beforeBuying?.park("the confirm holding its prepared destination")

                    try {
                        viewModel.buyDash(id)
                    } catch (ex: Exception) {
                        // tryBuyDash(): the purchase-error dialog, no navigation.
                        purchaseErrors.add(ex)
                        return@launch
                    }

                    viewModel.latchConfirmForNavigation(id)
                } finally {
                    viewModel.endConfirm(id)
                    confirmFinished.countDown()
                }
            }
            return id
        }

        fun onResume() {
            if (confirmJob?.isActive != true) {
                viewModel.releaseConfirmNavigationLatch()
            }
        }

        fun onDestroy() {
            attemptId?.let { viewModel.endConfirm(it) }
        }

        suspend fun join() {
            confirmJob?.join()
        }
    }

    private suspend fun priceTheOrder() {
        assertEquals(
            CoinbaseErrorType.NONE,
            viewModel.validateBuyDash(Dash.parse("0.1"), retryWithDeposit = true)
        )
    }

    private fun awaitLatch(latch: CountDownLatch, what: String) {
        assertTrue(what, latch.await(AWAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun aSecondTapWhileTheDestinationReadBlocksPlacesNoSecondOrder() = runBlocking {
        priceTheOrder()
        val parked = Handshake().also { parkNextRead.set(it) }
        val screen = ReviewScreen()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        assertNotNull("the first tap must begin a confirm", screen.tap(scope))
        awaitLatch(parked.reached, "the first tap must be parked in the destination read")


        // The second tap lands in exactly the window the guard exists for: the
        // first coroutine is running but has shown nothing and placed nothing.
        val secondTap = withContext(Dispatchers.Default) { screen.tap(scope) }

        assertNull("a second tap must not begin a second confirm", secondTap)
        parked.proceed.countDown()
        screen.join()

        assertEquals("one destination read", 1, addressReads.get())
        assertEquals("one deposit", 1, deposits.get())
        assertEquals("one order", 1, orders.get())
        assertTrue("the confirm must not have failed: $purchaseErrors", purchaseErrors.isEmpty())
        scope.cancel()
    }

    @Test
    fun aStaleAttemptUnwindingDoesNotInvalidateTheConfirmThatReplacedIt() = runBlocking {
        priceTheOrder()

        // Attempt A, parked in the destination read.
        val parked = Handshake().also { parkNextRead.set(it) }
        val rotatedAway = ReviewScreen()
        val scopeA = CoroutineScope(Dispatchers.Default + Job())
        assertNotNull("attempt A must begin", rotatedAway.tap(scopeA))
        awaitLatch(parked.reached, "attempt A must be parked in the destination read")

        // Rotation. The fragment is destroyed and its lifecycleScope cancelled,
        // but A is inside a read that does not answer cancellation: it cannot
        // finish — and so cannot run its finally — until that read returns.
        scopeA.cancel()
        rotatedAway.onDestroy()
        assertEquals(
            "attempt A must still be parked, not unwound",
            1L,
            rotatedAway.confirmFinished.count
        )

        // The recreated fragment: no confirm of its own, so it drops the
        // navigation latch, and the user confirms again.
        val recreated = ReviewScreen()
        recreated.onResume()
        val holdingItsDestination = Handshake()
        val scopeB = CoroutineScope(Dispatchers.Default + Job())
        assertNotNull(
            "the recreated screen must be able to confirm while the stale attempt unwinds",
            recreated.tap(scopeB, beforeBuying = holdingItsDestination)
        )
        awaitLatch(
            holdingItsDestination.reached,
            "attempt B must have prepared its destination"
        )

        // A unwinds now, inside B's window: its finally runs while B is live and
        // has already read its destination but has not yet placed its order.
        parked.proceed.countDown()
        awaitLatch(rotatedAway.confirmFinished, "attempt A must finish unwinding")

        holdingItsDestination.proceed.countDown()
        recreated.join()

        assertTrue(
            "the live confirm must not be failed by the cancelled one: $purchaseErrors",
            purchaseErrors.isEmpty()
        )
        assertEquals("attempt B must have placed its order", 1, orders.get())
        assertEquals("and only its own", 1, deposits.get())
        scopeA.cancel()
        scopeB.cancel()
    }

    @Test
    fun anAttemptThatNoLongerOwnsTheFlightCannotPlaceAnOrder() = runBlocking {
        priceTheOrder()
        // The attempt whose fragment was destroyed: it gave the flight back in
        // onDestroy and is now unwinding, holding a destination it prepared.
        val stale = requireNotNull(viewModel.tryBeginConfirm())
        viewModel.endConfirm(stale)

        val live = requireNotNull(viewModel.tryBeginConfirm())

        // The tripwire is ownership, not "somebody holds it": the stale attempt
        // would otherwise ride the live attempt's flight into a second order.
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { viewModel.buyDash(stale) }
        }
        assertTrue(thrown.message?.contains("single-flight") == true)
        assertEquals("nothing may be ordered by the stale attempt", 0, orders.get())

        viewModel.buyDash(live)
        assertEquals(1, orders.get())
    }

    @Test
    fun aRetryableFailureGivesTheFlightBackAndTheNextTapBuys() = runBlocking {
        priceTheOrder()
        failNextRead.set(true)
        val screen = ReviewScreen()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        assertNotNull("the first tap must begin a confirm", screen.tap(scope))
        screen.join()

        assertEquals("the unavailable destination must be surfaced", 1, unavailableAddressRetries.get())
        assertEquals("nothing may be ordered", 0, orders.get())

        // Nothing was bought, so the user must be able to try again on the same
        // screen: a flight left held would leave the button dead for the rest of
        // that screen's life.
        assertNotNull("a retryable failure must re-arm the confirm", screen.tap(scope))
        screen.join()

        assertTrue("the retry must succeed: $purchaseErrors", purchaseErrors.isEmpty())
        assertEquals("the retry buys", 1, orders.get())
        scope.cancel()
    }

    @Test
    fun theFlightIsGivenBackOnlyByTheAttemptThatHoldsIt() {
        val held = requireNotNull(viewModel.tryBeginConfirm())
        assertNull("a second tap must be refused", viewModel.tryBeginConfirm())

        // A sibling that no longer owns the flight — the cancelled attempt still
        // unwinding its blocking read — must not be able to give it away.
        viewModel.endConfirm(held - 1)
        assertNull("a stale release must not free the flight", viewModel.tryBeginConfirm())

        viewModel.endConfirm(held)
        assertNotNull("the owner's release must free it", viewModel.tryBeginConfirm())
    }

    @Test
    fun aPurchasedConfirmStaysGuardedUntilTheScreenComesBack() = runBlocking {
        priceTheOrder()
        val screen = ReviewScreen()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        assertNotNull(screen.tap(scope))
        screen.join()
        assertEquals("the confirm must have bought", 1, orders.get())

        // The coroutine has ended but the navigation transaction has not executed:
        // the screen is still attached and still clickable, and a queued tap would
        // place a second order. The attempt itself is over — the latch is what
        // holds the flight now.
        assertNull("a tap after the purchase must be refused", viewModel.tryBeginConfirm())

        // Back from 2FA, cancelled or back-pressed: the next tap is a new intent.
        screen.onResume()
        assertNotNull("coming back to the screen must re-arm the confirm", viewModel.tryBeginConfirm())
        scope.cancel()
    }

    @Test
    fun droppingTheNavigationLatchLeavesARunningAttemptOwningTheFlight() = runBlocking {
        priceTheOrder()
        val running = requireNotNull(viewModel.tryBeginConfirm())

        // What a recreated screen does in onResume. It must touch the completed
        // navigation latch only: the attempt still unwinding owns its own flight.
        viewModel.releaseConfirmNavigationLatch()

        assertNull("a running attempt must keep the flight", viewModel.tryBeginConfirm())
        viewModel.buyDash(running)
        assertEquals(1, orders.get())
    }

    @Test
    fun placingAnOrderOutsideTheSingleFlightIsRefused() {
        // The tripwire at the dangerous site: reaching placeBuyOrder without the
        // flight IS the double purchase, so it fails loudly rather than billing.
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { viewModel.buyDash(1L) }
        }
        assertTrue(thrown.message?.contains("single-flight") == true)
        assertEquals(0, orders.get())
    }

    companion object {
        private const val AWAIT_SECONDS = 10L
    }
}
