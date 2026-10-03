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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The revalidation/single-flight contract of the Receive page's address
 * request ([PaymentsViewModel.requestReceiveAddress]).
 *
 * Receive is a ViewPager2 page, so its first read runs while the page may be
 * OFFSCREEN — in the two-tab layout the page is built next to Send — and that
 * read can fail simply because the SDK engine has not bound yet. ViewPager2
 * then RESUMES the retained page instead of recreating its view, so the page
 * must ask again when it is selected or it stays blank forever.
 *
 * It must ask again after a SUCCESS too. The ViewModel outlives both a tab
 * switch and a view recreation, so an address it published can be paid — and
 * the engine's next-unused pointer moved past it — before a rotation renders
 * that retained snapshot again. Republishing it would be the address reuse
 * this screen was fixed for (SR-03), so a request from
 * [ReceiveAddressState.Available] is a revalidation, not a no-op.
 *
 * Asking again is only safe if it cannot stack: the live read BLOCKS for up to
 * `CutoverUiDataService.BINDING_WAIT_MS` (5s) waiting for the binding, so a
 * resume very plausibly lands inside a read that is still running, and a
 * second read would both churn the engine and race to publish its answer.
 * These tests pin that whole contract: retry after failure, revalidation after
 * success, the same answer while the address is unpaid, at most one read in
 * flight, and fail-closed when a revalidation cannot be answered.
 *
 * What this cannot see: that the FRAGMENT calls
 * [PaymentsViewModel.requestReceiveAddress] again from `onViewCreated` and
 * from `repeatOnLifecycle(RESUMED)`, that it renders
 * [ReceiveAddressState.address] (so a revalidation does not blank the QR and a
 * failed one takes it down), and that the failure toast is still gated on
 * [PaymentsReceiveFragment.shouldSurfaceAddressFailure]. All are
 * Robolectric/instrumented concerns (every Robolectric test in this module
 * currently fails on a missing conscrypt_jni native library), so they are not
 * covered here — only the state they read is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentsReceiveAddressRequestTest {

    /** [PaymentsViewModel] builds a LiveData from a flow in its constructor. */
    @get:Rule
    val instantTaskExecutorRule: TestRule = InstantTaskExecutorRule()

    private val params = TestNet3Params.get()
    private val address: Address = Address.fromBase58(params, "ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U")

    /** Where the engine's next-unused pointer lands once [address] is paid. */
    private val advancedAddress: Address = Address.fromBase58(params, "ydW78zVxRgNhANX2qtG4saSCC5euYpFbH6")
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
        //
        // The duplicate is detected from INSIDE the stub rather than by a
        // trailing verify(): a duplicate read launched here runs on its own IO
        // thread, so a count taken once the first read has finished can miss
        // it. Waiting on a latch the duplicate would trip is what makes this
        // test actually fail when the single-flight gate is removed.
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val duplicate = CountDownLatch(1)
        val reads = AtomicInteger()
        every { walletData.currentReceiveAddressLive() } answers {
            if (reads.incrementAndGet() == 1) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            } else {
                duplicate.countDown()
            }
            address
        }
        val viewModel = buildViewModel()

        viewModel.requestReceiveAddress()
        assertTrue("the read never started", entered.await(10, TimeUnit.SECONDS))

        viewModel.requestReceiveAddress()
        viewModel.requestReceiveAddress()

        assertFalse(
            "a second engine read started while one was already in flight",
            duplicate.await(1, TimeUnit.SECONDS)
        )
        assertEquals(
            "a read is still in flight, so the page must still be loading",
            ReceiveAddressState.Loading(null),
            viewModel.receiveAddress.value
        )
        release.countDown()
        assertEquals(
            ReceiveAddressState.Available(address.toBase58()),
            awaitState(viewModel, "the address") { it is ReceiveAddressState.Available }
        )
        assertEquals("only one engine read may ever have run", 1, reads.get())
        verify(exactly = 1) { walletData.currentReceiveAddressLive() }
    }

    @Test
    fun `an address that the engine has advanced past is re-read, not republished`() {
        // The regression: the first read answers A, the user is paid on A, the
        // engine's next-unused pointer moves to B, and the view is recreated (a
        // rotation) against the RETAINED ViewModel. Republishing A here is the
        // address reuse this screen was fixed for.
        val advanced = advancedAddress
        var reads = 0
        every { walletData.currentReceiveAddressLive() } answers {
            reads++
            if (reads == 1) address else advanced
        }
        val viewModel = buildViewModel()

        viewModel.requestReceiveAddress()
        assertEquals(
            ReceiveAddressState.Available(address.toBase58()),
            awaitState(viewModel, "the first address") { it is ReceiveAddressState.Available }
        )

        // The recreated view asks again.
        viewModel.requestReceiveAddress()

        assertEquals(
            ReceiveAddressState.Available(advanced.toBase58()),
            awaitState(viewModel, "the advanced address") {
                it is ReceiveAddressState.Available && it.address == advanced.toBase58()
            }
        )
        verify(exactly = 2) { walletData.currentReceiveAddressLive() }
    }

    @Test
    fun `an unpaid address survives revalidation unchanged`() {
        // The other half: while the address is unpaid the engine's next-unused
        // read is idempotent, so revalidating must keep the QR on the same
        // address no matter how often the user flips back to Receive.
        every { walletData.currentReceiveAddressLive() } returns address
        val viewModel = buildViewModel()

        repeat(3) {
            viewModel.requestReceiveAddress()
            assertEquals(
                ReceiveAddressState.Available(address.toBase58()),
                awaitState(viewModel, "the address") { state -> state is ReceiveAddressState.Available }
            )
        }
        verify(exactly = 3) { walletData.currentReceiveAddressLive() }
    }

    @Test
    fun `a revalidation keeps the previous address on screen while it runs`() {
        // Rotating mid-revalidation must not blank an already-rendered QR: the
        // page renders ReceiveAddressState.address, and the in-flight state has
        // to keep carrying the address it is checking. The read blocks for up
        // to 5s, so this window is long enough to see.
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        var reads = 0
        every { walletData.currentReceiveAddressLive() } answers {
            reads++
            if (reads > 1) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            address
        }
        val viewModel = buildViewModel()

        viewModel.requestReceiveAddress()
        awaitState(viewModel, "the first address") { it is ReceiveAddressState.Available }

        viewModel.requestReceiveAddress()
        assertTrue("the revalidation never started", entered.await(10, TimeUnit.SECONDS))

        assertEquals(
            "the revalidation must carry the address it is checking",
            ReceiveAddressState.Loading(address.toBase58()),
            viewModel.receiveAddress.value
        )
        assertEquals(
            "so the page keeps rendering it instead of blanking",
            address.toBase58(),
            viewModel.receiveAddress.value.address
        )
        release.countDown()
        awaitState(viewModel, "the revalidated address") { it is ReceiveAddressState.Available }
    }

    @Test
    fun `a failed revalidation drops the address it could not vouch for`() {
        // Fail closed. The read exists to find out whether A is still the next
        // unused address; an engine that cannot answer cannot vouch for it, and
        // showing it anyway re-advertises a possibly-used address. The page
        // recovers on the next request, which is what makes this safe.
        val advanced = advancedAddress
        var reads = 0
        every { walletData.currentReceiveAddressLive() } answers {
            reads++
            when (reads) {
                1 -> address
                2 -> throw ReceiveAddressUnavailableException()
                else -> advanced
            }
        }
        val viewModel = buildViewModel()

        viewModel.requestReceiveAddress()
        awaitState(viewModel, "the first address") { it is ReceiveAddressState.Available }

        viewModel.requestReceiveAddress()
        val failure = awaitState(viewModel, "the failed revalidation") {
            it !is ReceiveAddressState.Available && it !is ReceiveAddressState.Loading
        }
        assertEquals(ReceiveAddressState.Unavailable(1), failure)
        assertEquals(
            "a failure must advertise nothing, not the address it could not check",
            null,
            failure.address
        )

        // ...and the next resume recovers.
        viewModel.requestReceiveAddress()
        assertEquals(
            ReceiveAddressState.Available(advanced.toBase58()),
            awaitState(viewModel, "the recovered address") { it is ReceiveAddressState.Available }
        )
        verify(exactly = 3) { walletData.currentReceiveAddressLive() }
    }

    @Test
    fun `only an in-flight read has an address to advertise besides a successful one`() {
        // What PaymentsReceiveFragment renders, as data: the QR survives a
        // revalidation and comes down on a failure.
        assertEquals(null, ReceiveAddressState.Idle.address)
        assertEquals(null, ReceiveAddressState.Loading(null).address)
        assertEquals(address.toBase58(), ReceiveAddressState.Loading(address.toBase58()).address)
        assertEquals(address.toBase58(), ReceiveAddressState.Available(address.toBase58()).address)
        assertEquals(null, ReceiveAddressState.Unavailable(1).address)
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
    fun `every state but an in-flight read starts one`() {
        val startable = listOf(
            ReceiveAddressState.Idle,
            ReceiveAddressState.Unavailable(1),
            ReceiveAddressState.Unavailable(7),
            // A revalidation: the retained snapshot may name an address the
            // wallet has since been paid on.
            ReceiveAddressState.Available(address.toBase58())
        )
        for (state in startable) {
            assertTrue(
                "$state must start a read, or the page can never recover or revalidate",
                PaymentsViewModel.canStartReceiveAddressRequest(state)
            )
        }

        val refused = listOf(
            ReceiveAddressState.Loading(null),
            ReceiveAddressState.Loading(address.toBase58())
        )
        for (state in refused) {
            assertFalse(
                "$state must not start a second, overlapping read",
                PaymentsViewModel.canStartReceiveAddressRequest(state)
            )
        }
    }
}
