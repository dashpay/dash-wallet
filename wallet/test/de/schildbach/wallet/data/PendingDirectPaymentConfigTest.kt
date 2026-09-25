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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.WalletDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real DataStore rather than a mocked config.
 *
 * The verifier's own tests mock [PendingDirectPaymentConfig.getAllOrThrow] and make it throw, so
 * they prove the verifier reacts to a failed read but say nothing about which stored states
 * actually produce one. That is the half that matters: a stored value the reader mistakes for an
 * empty store lets readiness succeed with pending input locks unrestored, and the next write then
 * replaces the file with only the new payment.
 */
@RunWith(RobolectricTestRunner::class)
// a plain Application: this test needs only a Context for DataStore, and booting the real
// WalletApplication would drag in Hilt and Firebase
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PendingDirectPaymentConfigTest {
    private lateinit var config: PendingDirectPaymentConfig

    private val key = PendingDirectPaymentConfig.PENDING_PAYMENTS

    private val payment = PendingDirectPayment(
        txId = Sha256Hash.wrap("00000000000000000000000000000000000000000000000000000000000000a1"),
        txBytes = byteArrayOf(1, 2, 3, 4),
        paymentUrl = "https://merchant.example/pay/1",
        serviceName = "CTX",
        createdAt = 1_700_000_000_000L
    )

    private val otherPayment = payment.copy(
        txId = Sha256Hash.wrap("00000000000000000000000000000000000000000000000000000000000000b2"),
        paymentUrl = "https://merchant.example/pay/2"
    )

    /** Shaped like a record but undecodable: the hex body is not hex. */
    private val undecodableEntry =
        """{"txId":"00000000000000000000000000000000000000000000000000000000000000c3",""" +
            """"tx":"zzzz","paymentUrl":"https://merchant.example/pay/3",""" +
            """"serviceName":"CTX","createdAt":1700000000000}"""

    @Before
    fun setUp() {
        config = PendingDirectPaymentConfig(
            ApplicationProvider.getApplicationContext<Application>(),
            mockk<WalletDataProvider>(relaxed = true)
        )
        runBlocking { config.clearAll() }
    }

    private fun raw(): String? = runBlocking { config.get(key) }

    private fun seed(value: String) = runBlocking { config.set(key, value) }

    private inline fun assertRefusesToRead(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what was accepted; an unreadable store must not read as an empty one")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    // --- states that must be trusted as "nothing pending" ------------------------------------

    @Test
    fun absentPreferenceIsTheOnlyTrustedSilence() = runBlocking {
        assertEquals(null, raw())
        assertEquals(emptyList<PendingDirectPayment>(), config.getAllOrThrow())
    }

    @Test
    fun emptyArrayReadsAsNoPayments() = runBlocking {
        seed("[]")
        assertEquals(emptyList<PendingDirectPayment>(), config.getAllOrThrow())
    }

    @Test
    fun writtenPaymentsRoundTrip() = runBlocking {
        config.add(payment)
        config.add(otherPayment)
        assertEquals(listOf(payment.txId, otherPayment.txId), config.getAllOrThrow().map { it.txId })

        config.remove(payment.txId)
        assertEquals(listOf(otherPayment.txId), config.getAllOrThrow().map { it.txId })
    }

    // --- states that must refuse to read ------------------------------------------------------

    @Test
    fun malformedJsonRefusesToRead() = runBlocking {
        seed("""[{"txId":"00000000""")
        assertRefusesToRead("truncated JSON") { runBlocking { config.getAllOrThrow() } }
    }

    @Test
    fun nonArrayJsonRefusesToRead() = runBlocking {
        seed("""{"txId":"whatever"}""")
        assertRefusesToRead("a JSON object") { runBlocking { config.getAllOrThrow() } }
    }

    @Test
    fun emptyStringRefusesToRead() = runBlocking {
        // encode() cannot produce this, so it is a truncated or foreign write, not an empty store
        seed("")
        assertRefusesToRead("an empty string") { runBlocking { config.getAllOrThrow() } }
    }

    @Test
    fun nullArrayElementRefusesToRead() = runBlocking {
        seed("[null]")
        assertRefusesToRead("a null element") { runBlocking { config.getAllOrThrow() } }
    }

    @Test
    fun nonObjectArrayElementRefusesToRead() = runBlocking {
        seed("""["not-a-record", 7]""")
        assertRefusesToRead("non-object elements") { runBlocking { config.getAllOrThrow() } }
    }

    @Test
    fun undecodableEntryRefusesToRead() = runBlocking {
        seed("[$undecodableEntry]")
        assertRefusesToRead("an undecodable entry") { runBlocking { config.getAllOrThrow() } }
    }

    // --- writes must never erase what they could not read -------------------------------------

    @Test
    fun addLeavesMalformedJsonUntouched() = runBlocking {
        val original = """[{"txId":"00000000"""
        seed(original)

        try {
            config.add(payment)
            fail("add() wrote over a store it could not read")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(original, raw())
    }

    @Test
    fun removeLeavesMalformedJsonUntouched() = runBlocking {
        val original = """[{"txId":"00000000"""
        seed(original)

        try {
            config.remove(payment.txId)
            fail("remove() wrote over a store it could not read")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(original, raw())
    }

    @Test
    fun addCarriesNonObjectElementsThrough() = runBlocking {
        seed("""["not-a-record"]""")

        config.add(payment)

        // the new payment is stored, the element we could not read is still there, and the store
        // therefore still refuses to read rather than reporting one clean payment
        assertTrue(raw()!!.contains("not-a-record"))
        assertTrue(raw()!!.contains(payment.txId.toString()))
        assertRefusesToRead("a carried-through element") { runBlocking { config.getAllOrThrow() } }
    }

    @Test
    fun addCarriesUndecodableEntriesThrough() = runBlocking {
        seed("[$undecodableEntry]")

        config.add(payment)

        assertTrue(raw()!!.contains("zzzz"))
        assertTrue(raw()!!.contains(payment.txId.toString()))
    }

    // --- the tolerant reader stays tolerant ---------------------------------------------------

    @Test
    fun getAllStillReturnsWhatIsLegible() = runBlocking {
        seed("[$undecodableEntry, ${payment.toJson()}]")

        // getAll() is for callers that only want whatever can be shown; it must not start
        // throwing, and one bad record must not hide the good one
        assertEquals(listOf(payment.txId), config.getAll().map { it.txId })
    }

    @Test
    fun getAllReportsNothingWhenTheStoreIsUnparseable() = runBlocking {
        seed("""[{"txId":"00000000""")
        assertEquals(emptyList<PendingDirectPayment>(), config.getAll())
    }

    // --- the durable answer the gift card screens block on ------------------------------------

    private val giftCardPayment = payment.copy(isGiftCardPurchase = true)

    @Test
    fun anUnresolvedGiftCardPurchaseBlocksAnother() = runBlocking {
        config.add(giftCardPayment)

        assertTrue(config.hasUnresolvedGiftCardPurchase())
        assertTrue(config.observeUnresolvedGiftCardPurchase().first())
    }

    @Test
    fun anOrdinaryPaymentDoesNotBlockGiftCards() = runBlocking {
        config.add(payment)

        assertFalse(config.hasUnresolvedGiftCardPurchase())
        assertFalse(config.observeUnresolvedGiftCardPurchase().first())
    }

    @Test
    fun resolvingThePaymentLiftsTheBlock() = runBlocking {
        config.add(giftCardPayment)
        assertTrue(config.hasUnresolvedGiftCardPurchase())

        // what commit() does once the network shows the transaction
        config.remove(giftCardPayment.txId)

        assertFalse(config.hasUnresolvedGiftCardPurchase())
        assertFalse(config.observeUnresolvedGiftCardPurchase().first())
    }

    @Test
    fun aReleasedPurchaseNoLongerBlocks() = runBlocking {
        // its inputs are free again, and the order that outlives it is kept only to explain a late
        // broadcast, so waiting out an unlucky purchase must not cost the user gift cards for the
        // whole retention period
        config.add(giftCardPayment.copy(abandoned = true))

        assertFalse(config.hasUnresolvedGiftCardPurchase())
        assertFalse(config.observeUnresolvedGiftCardPurchase().first())
    }

    @Test
    fun anUnreadableStoreCountsAsAnOutstandingPurchase() = runBlocking {
        seed("""[{"txId":"00000000""")

        // the screen asking this is deciding whether to let the user pay again, and a store we
        // cannot read is not an empty one
        assertTrue(config.hasUnresolvedGiftCardPurchase())
    }

    @Test
    fun anUnreadableStoreFailsTheObservedAnswer() = runBlocking {
        seed("[$undecodableEntry]")

        assertRefusesToRead("an undecodable entry") {
            runBlocking { config.observeUnresolvedGiftCardPurchase().first() }
        }
    }

    @Test
    fun theBlockFollowsTheStoreRatherThanBeingReadOnce() = runBlocking {
        val seen = mutableListOf<Boolean>()
        seen.add(config.observeUnresolvedGiftCardPurchase().first())
        config.add(giftCardPayment)
        seen.add(config.observeUnresolvedGiftCardPurchase().first())
        config.remove(giftCardPayment.txId)
        seen.add(config.observeUnresolvedGiftCardPurchase().first())

        assertEquals(listOf(false, true, false), seen)
    }

    // --- the invoice identity, and records written before it existed ---------------------------

    @Test
    fun paymentRequestIdRoundTrips() = runBlocking {
        val identified = payment.copy(paymentRequestId = "ab12cd")
        config.add(identified)

        assertEquals(listOf("ab12cd"), config.getAllOrThrow().map { it.paymentRequestId })
    }

    @Test
    fun aRecordWrittenBeforeTheInvoiceIdentityStillDecodes() = runBlocking {
        // exactly what an older build wrote: no paymentRequestId key at all. getAllOrThrow refuses
        // the whole store over a single entry it cannot read, and a store it refuses blocks every
        // send, so a field added here must never be one an old record is missing.
        seed(
            """[{"txId":"${payment.txId}","tx":"01020304",""" +
                """"paymentUrl":"https://merchant.example/pay/1",""" +
                """"serviceName":"CTX","createdAt":1700000000000}]"""
        )

        val stored = config.getAllOrThrow()

        assertEquals(1, stored.size)
        assertEquals(null, stored.single().paymentRequestId)
    }

    @Test
    fun anExplicitNullInvoiceIdentityDecodes() = runBlocking {
        config.add(payment)

        assertEquals(null, config.getAllOrThrow().single().paymentRequestId)
    }
}
