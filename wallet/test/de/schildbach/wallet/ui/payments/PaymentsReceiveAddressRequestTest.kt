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

package de.schildbach.wallet.ui.payments

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bitcoinj.core.Address
import org.bitcoinj.core.Context
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.services.ReceiveAddressUnavailableException
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The retry/single-flight contract of the Receive page's address request
 * ([PaymentsViewModel.requestReceiveAddress]).
 *
 * Receive is a ViewPager2 page, so its first read runs while the page may be
 * OFFSCREEN — in the two-tab layout the page is built next to Send — and that
 * read can fail simply because the SDK engine has not bound yet. ViewPager2
 * then RESUMES the retained page instead of recreating its view, so the page
 * must ask again when it is selected or it stays blank forever. Asking again
 * is only safe if it cannot stack: the live read BLOCKS for up to
 * `CutoverUiDataService.BINDING_WAIT_MS` (5s) waiting for the binding, so a
 * resume very plausibly lands inside a read that is still running, and a
 * second read would both churn the engine and risk advertising a different
 * address. These tests pin all three halves of that: retry after failure,
 * at most one read in flight, and no re-read once an address is in hand.
 *
 * What this cannot see: that the FRAGMENT calls
 * [PaymentsViewModel.requestReceiveAddress] again from
 * `repeatOnLifecycle(RESUMED)`, and that the failure toast is still gated on
 * [PaymentsReceiveFragment.shouldSurfaceAddressFailure]. Both are
 * Robolectric/instrumented concerns (every Robolectric test in this module
 * currently fails on a missing conscrypt_jni native library), so they are not
 * covered here — only the gate they drive is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentsReceiveAddressRequestTest {

    /** [PaymentsViewModel] builds a LiveData from a flow in its constructor. */
    @get:Rule
    val instantTaskExecutorRule: TestRule = InstantTaskExecutorRule()

    private val params = TestNet3Params.get()
    private val address: Address = Address.fromBase58(params, "ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U")
    private val walletContext = Context(params)

    private val dashjWallet = mockk<Wallet> {
        every { context } returns walletContext
    }
    private val walletData = mockk<WalletData> {
        every { wallet } returns dashjWallet
    }
    private val identityConfig = mockk<BlockchainIdentityConfig> {
        every { observe() } returns emptyFlow()
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = PaymentsViewModel(
        mockk<PlatformRepo>(relaxed = true),
        identityConfig,
        walletData,
        mockk<AnalyticsService>(relaxed = true)
    )

    /** The read runs on [Dispatchers.IO]; wait for its result to land. */
    private fun awaitState(
        viewModel: PaymentsViewModel,
        what: String,
        predicate: (ReceiveAddressState) -> Boolean
    ): ReceiveAddressState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val state = viewModel.receiveAddress.value
            if (predicate(state)) {
                return state
            }
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what, stuck at ${viewModel.receiveAddress.value}")
    }

    @Test
    fun `a request while a slow read is in flight does not start a second read`() {
        // The engine read blocks for up to 5s waiting for the binding; a resume
        // (or three) lands inside that window and must not stack onto it.
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        every { walletData.currentReceiveAddressLive() } answers {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            address
        }
        val viewModel = buildViewModel()

        viewModel.requestReceiveAddress()
        assertTrue("the read never started", entered.await(10, TimeUnit.SECONDS))

        viewModel.requestReceiveAddress()
        viewModel.requestReceiveAddress()

        assertEquals(
            "a read is still in flight, so the page must still be loading",
            ReceiveAddressState.Loading,
            viewModel.receiveAddress.value
        )
        release.countDown()
        assertEquals(
            ReceiveAddressState.Available(address.toBase58()),
            awaitState(viewModel, "the address") { it is ReceiveAddressState.Available }
        )
        verify(exactly = 1) { walletData.currentReceiveAddressLive() }
    }

    @Test
    fun `an address already in hand is not read again`() {
        every { walletData.currentReceiveAddressLive() } returns address
        val viewModel = buildViewModel()

        viewModel.requestReceiveAddress()
        awaitState(viewModel, "the address") { it is ReceiveAddressState.Available }

        // Every tab switch back to Receive asks again; re-reading here would
        // churn the engine and could advertise a different address each time.
        viewModel.requestReceiveAddress()
        viewModel.requestReceiveAddress()

        assertEquals(
            ReceiveAddressState.Available(address.toBase58()),
            viewModel.receiveAddress.value
        )
        verify(exactly = 1) { walletData.currentReceiveAddressLive() }
    }

    @Test
    fun `a failed read is retried by the next request`() {
        var reads = 0
        every { walletData.currentReceiveAddressLive() } answers {
            reads++
            if (reads == 1) throw ReceiveAddressUnavailableException() else address
        }
        val viewModel = buildViewModel()

        // The offscreen attempt: the engine had not bound yet.
        viewModel.requestReceiveAddress()
        assertEquals(
            ReceiveAddressState.Unavailable(1),
            awaitState(viewModel, "the failure") { it is ReceiveAddressState.Unavailable }
        )

        // The user selects Receive; the engine has recovered since.
        viewModel.requestReceiveAddress()

        assertEquals(
            ReceiveAddressState.Available(address.toBase58()),
            awaitState(viewModel, "the retried address") { it is ReceiveAddressState.Available }
        )
        verify(exactly = 2) { walletData.currentReceiveAddressLive() }
    }

    @Test
    fun `consecutive failures are distinct states`() {
        // StateFlow conflates equal values, so a second failure that compared
        // equal to the first would never reach a collector that was not
        // scheduled in between — and the page would never report it.
        every { walletData.currentReceiveAddressLive() } throws ReceiveAddressUnavailableException()
        val viewModel = buildViewModel()

        viewModel.requestReceiveAddress()
        val first = awaitState(viewModel, "the first failure") { it is ReceiveAddressState.Unavailable }
        viewModel.requestReceiveAddress()
        val second = awaitState(viewModel, "the second failure") {
            it is ReceiveAddressState.Unavailable && it != first
        }

        assertEquals(ReceiveAddressState.Unavailable(1), first)
        assertEquals(ReceiveAddressState.Unavailable(2), second)
        verify(exactly = 2) { walletData.currentReceiveAddressLive() }
    }

    @Test
    fun `only a never-asked or failed state starts a read`() {
        val startable = listOf(
            ReceiveAddressState.Idle,
            ReceiveAddressState.Unavailable(1),
            ReceiveAddressState.Unavailable(7)
        )
        for (state in startable) {
            assertTrue(
                "$state must start a read, or the page can never recover",
                PaymentsViewModel.canStartReceiveAddressRequest(state)
            )
        }

        val refused = listOf(
            ReceiveAddressState.Loading,
            ReceiveAddressState.Available(address.toBase58())
        )
        for (state in refused) {
            assertFalse(
                "$state must not start a read",
                PaymentsViewModel.canStartReceiveAddressRequest(state)
            )
        }
    }
}
