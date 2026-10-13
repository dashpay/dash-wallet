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
package de.schildbach.wallet.ui.send

import android.app.Application
import android.os.Looper
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.ContactAddressAlreadyPaidException
import de.schildbach.wallet.service.platform.sdk.ContactPaymentAddressProvider
import de.schildbach.wallet.service.platform.sdk.PaidContactAddressStore
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.FriendChainAccess
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.PaymentIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.concurrent.thread

/**
 * PR #1608 review: once a contact payment's broadcast signal fires, the
 * resolved contact intent must not be submitted again. The scenario: the SDK
 * reports Broadcast, the display bridge then fails, SendCoinsFragment.handleGo
 * shows the error and calls resetState(), and the user confirms again — that
 * second confirmation must pay a NEW address (fresh contact resolution), never
 * the one just paid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SendCoinsViewModelContactResubmitTest {

    private val params = Constants.NETWORK_PARAMETERS
    private val ourUserId = "ourUserIdBase58"
    private val contactUserId = "contactUserIdBase58"
    private val accountReference = 0x77

    private val keys = List(3) { Address.fromKey(params, ECKey()) }

    /** dashj's SENDING chain for the contact: a mark of the current key moves it on. */
    private class FakeDashjChain(val keys: List<Address>, val advances: Boolean = true) {
        @Volatile var current = 0
        val marks = mutableListOf<Address>()

        fun next(): Address? = keys.getOrNull(current)

        @Synchronized
        fun mark(address: Address): FriendChainAccess.MarkResult {
            marks += address
            if (advances && keys.indexOf(address) == current) current++
            return FriendChainAccess.MarkResult.MARKED
        }
    }

    private class Sends {
        val recipients = mutableListOf<Address?>()
    }

    private fun contactRequest(from: String, to: String) =
        DashPayContactRequest(from, to, accountReference, ByteArray(0), 0, 0, 1_000L, null, null)

    /** The real provider, pre-cutover: dashj serves the contact's address. */
    private fun provider(chain: FakeDashjChain) = ContactPaymentAddressProvider(
        cutoverCommitted = { false },
        sdkAccountReader = { _, _ -> null },
        dashjNextAddress = { _, _ -> chain.next() },
        dashjMarkUsed = { _, _, address -> chain.mark(address) },
        params = params,
        paidAddresses = PaidContactAddressStore(file = null)
    )

    private fun buildViewModel(
        chain: FakeDashjChain,
        runner: SendCoinsTaskRunner,
        provider: ContactPaymentAddressProvider = provider(chain)
    ): SendCoinsViewModel {
        val wallet = mockk<Wallet>(relaxed = true) {
            every { currentReceiveAddress() } returns Address.fromKey(params, ECKey())
        }
        val walletData = mockk<WalletData>(relaxed = true) {
            every { this@mockk.wallet } returns wallet
            every { observeMaxOutputBalance() } returns emptyFlow()
        }
        val blockchainStateDao = mockk<BlockchainStateDao> {
            every { observeState() } returns emptyFlow()
        }
        val identityRepository = mockk<IdentityRepository>(relaxed = true) {
            coEvery { hasIdentity() } returns true
            coEvery { hasUsername() } returns true
            coEvery { getUsername() } returns "me"
        }
        val fromContact = contactRequest(contactUserId, ourUserId)
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            coEvery { getLocalUserDataByUserId(contactUserId) } returns UsernameSearchResult(
                "contact",
                DashPayProfile(contactUserId, "contact"),
                toContactRequest = contactRequest(ourUserId, contactUserId),
                fromContactRequest = fromContact
            )
        }
        val contactRequestDao = mockk<DashPayContactRequestDao> {
            coEvery { loadToOthers(contactUserId) } returns listOf(fromContact)
        }
        return SendCoinsViewModel(
            walletData,
            mockk<WalletApplication>(relaxed = true),
            blockchainStateDao,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            runner,
            mockk(relaxed = true),
            identityRepository,
            platformRepo,
            contactRequestDao,
            provider
        )
    }

    /**
     * A send runner whose first send is held inside the send — before any
     * broadcast signal — until [releaseFirst], then broadcast; later sends
     * succeed at once.
     */
    private fun pausingRunner(sends: Sends, firstInSend: CountDownLatch, releaseFirst: CountDownLatch): SendCoinsTaskRunner {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.isCutoverCommitted() } returns false
        every { runner.createSendRequest(any<Boolean>(), any<PaymentIntent>(), any<Boolean>(), any<Boolean>()) } answers { mockk<SendRequest>(relaxed = true) }
        coEvery {
            runner.sendCoins(
                any<SendRequest>(),
                any<Boolean>(),
                any<Boolean>(),
                any<Consumer<Transaction>>(),
                any<String>(),
                any<Address>(),
                any<(String?) -> Unit>(),
                any<() -> Unit>()
            )
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onBroadcast = args[6] as ((String?) -> Unit)?
            val first = synchronized(sends) {
                sends.recipients += args[5] as Address?
                sends.recipients.size == 1
            }
            if (first) {
                firstInSend.countDown()
                check(releaseFirst.await(10, TimeUnit.SECONDS)) { "first send never released" }
            }
            onBroadcast?.invoke("bb".repeat(32))
            mockk<Transaction>(relaxed = true)
        }
        return runner
    }

    /**
     * A send runner whose first send is broadcast and then fails in the display
     * bridge (the broadcast signal fires, then it throws); later sends succeed.
     */
    private fun runner(sends: Sends): SendCoinsTaskRunner {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.isCutoverCommitted() } returns false
        every { runner.createSendRequest(any<Boolean>(), any<PaymentIntent>(), any<Boolean>(), any<Boolean>()) } answers { mockk<SendRequest>(relaxed = true) }
        coEvery {
            runner.sendCoins(
                any<SendRequest>(),
                any<Boolean>(),
                any<Boolean>(),
                any<Consumer<Transaction>>(),
                any<String>(),
                any<Address>(),
                any<(String?) -> Unit>(),
                any<() -> Unit>()
            )
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onBroadcast = args[6] as ((String?) -> Unit)?
            sends.recipients += args[5] as Address?
            if (sends.recipients.size == 1) {
                onBroadcast?.invoke("aa".repeat(32))
                throw RuntimeException("broadcast, but bridging into the dashj wallet failed")
            }
            mockk<Transaction>(relaxed = true)
        }
        return runner
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        idle()
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
            idle()
        }
    }

    private fun SendCoinsViewModel.resolvedAddress(): String? =
        basePaymentIntent.getAddress(Constants.ADDRESS_NETWORK)

    /** Opens the send screen for the contact with an amount entered. */
    private fun open(viewModel: SendCoinsViewModel) {
        viewModel.setAmount(Coin.COIN)
        awaitCondition("amount applied") { viewModel.currentAmount == Coin.COIN }
        runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromUserId(contactUserId)) }
        awaitCondition("contact payment ready to confirm") {
            viewModel.everythingPlausible() && viewModel.dryrunSendRequest != null
        }
    }

    @Test
    fun broadcastThenBridgeFailure_errorDismissed_nextConfirmationPaysANewAddress() {
        val chain = FakeDashjChain(keys)
        val sends = Sends()
        val viewModel = buildViewModel(chain, runner(sends))
        open(viewModel)
        assertEquals(keys[0].toBase58(), viewModel.resolvedAddress())

        // 1st confirmation: broadcast, then the bridge fails.
        assertThrows(RuntimeException::class.java) {
            runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertEquals(listOf<Address?>(keys[0]), sends.recipients)
        idle()
        assertFalse("a spent contact intent cannot be confirmed", viewModel.everythingPlausible())

        // The fragment shows the error; dismissing it resets the state.
        viewModel.resetState()
        awaitCondition("contact address resolved again") {
            viewModel.resolvedAddress() == keys[1].toBase58() &&
                viewModel.everythingPlausible() &&
                viewModel.dryrunSendRequest != null
        }

        // 2nd confirmation pays the new address.
        runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        assertEquals(listOf<Address?>(keys[0], keys[1]), sends.recipients)
    }

    @Test
    fun spentContactIntent_isNeverResent_evenWhenNoNewAddressCanBeResolved() {
        // dashj cannot be advanced past the paid key, so re-resolution finds
        // no unpaid address: sending stays blocked rather than reusing it.
        val chain = FakeDashjChain(keys, advances = false)
        val sends = Sends()
        val viewModel = buildViewModel(chain, runner(sends))
        open(viewModel)

        assertThrows(RuntimeException::class.java) {
            runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        }
        viewModel.resetState()
        awaitCondition("re-resolution attempted") { chain.marks.size >= 2 }
        idle()
        assertFalse(viewModel.everythingPlausible())

        // A confirmation that gets through anyway is refused before sending.
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertEquals(listOf<Address?>(keys[0]), sends.recipients)
        assertTrue(viewModel.resolvedAddress() == keys[0].toBase58())
    }

    @Test
    fun twoSendScreens_sameContact_theSecondIsRefusedWhileTheFirstSends_thenPaysANewAddress() {
        // PR #1608 review, round 5: SendCoinsActivity allows several instances,
        // each with its own ViewModel; both resolved the same contact address.
        val chain = FakeDashjChain(keys)
        val provider = provider(chain)
        val sends = Sends()
        val firstInSend = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val runner = pausingRunner(sends, firstInSend, releaseFirst)
        val screen1 = buildViewModel(chain, runner, provider)
        val screen2 = buildViewModel(chain, runner, provider)
        open(screen1)
        open(screen2)
        assertEquals(keys[0].toBase58(), screen1.resolvedAddress())
        assertEquals(keys[0].toBase58(), screen2.resolvedAddress())

        // Screen 1 confirms; its send is held before any broadcast signal.
        val firstSend = thread { runBlocking { screen1.signAndSendPayment(Coin.COIN, null, true) } }
        assertTrue(firstInSend.await(10, TimeUnit.SECONDS))

        // Screen 2 confirms the same address: refused, nothing sent.
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { screen2.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertEquals(listOf<Address?>(keys[0]), sends.recipients)
        idle()
        assertFalse(screen2.everythingPlausible())

        // Dismissing the error resolves screen 2 again: a new address.
        screen2.resetState()
        awaitCondition("screen 2 resolved a new address") {
            screen2.resolvedAddress() == keys[1].toBase58() &&
                screen2.everythingPlausible() &&
                screen2.dryrunSendRequest != null
        }

        releaseFirst.countDown()
        firstSend.join(10_000)
        runBlocking { screen2.signAndSendPayment(Coin.COIN, null, true) }
        assertEquals(listOf<Address?>(keys[0], keys[1]), sends.recipients)
    }
}
