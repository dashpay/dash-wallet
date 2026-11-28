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

import org.bitcoinj.core.Address
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.money.Coin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentIntentOrderIdentityTest {
    private val params = TestNet3Params.get()
    private val a = ScriptBuilder.createOutputScript(Address.fromString(params, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")).program
    private val b = ScriptBuilder.createOutputScript(Address.fromString(params, "yjSvwyLB5X4dqQqVMPMu6UdrFpYZ3u9v5U")).program

    private fun intent(
        outputs: List<Pair<ByteArray, Long>>,
        url: String? = "https://merchant.example/pay/order-1",
        requestHash: ByteArray = ByteArray(32) { 1 },
        memo: String = "invoice",
        payee: String? = null,
        verifiedBy: String? = null,
        merchantData: ByteArray? = null
    ) = PaymentIntent(
        PaymentIntent.Standard.BIP70, payee, verifiedBy,
        outputs.map { (script, amount) -> PaymentIntent.Output(Coin.valueOf(amount), script) }.toTypedArray(),
        memo, url, merchantData, null, requestHash, null, null
    )

    @Test
    fun `a refreshed request for the same order has the same identity`() {
        val first = intent(listOf(a to 1_000L), requestHash = ByteArray(32) { 1 }, memo = "v1")
        val refreshed = intent(listOf(a to 1_000L), requestHash = ByteArray(32) { 2 }, memo = "v2")
        assertNotEquals(first.paymentRequestId, refreshed.paymentRequestId)
        assertEquals(first.orderIdentity(), refreshed.orderIdentity())
    }

    @Test
    fun `the outputs are compared in a canonical order`() {
        assertEquals(
            intent(listOf(a to 1_000L, b to 2_000L)).orderIdentity(),
            intent(listOf(b to 2_000L, a to 1_000L)).orderIdentity()
        )
    }

    @Test
    fun `a different amount, output, payment url or verified merchant is a different order`() {
        val base = intent(listOf(a to 1_000L)).orderIdentity()
        assertNotEquals(base, intent(listOf(a to 1_001L)).orderIdentity())
        assertNotEquals(base, intent(listOf(b to 1_000L)).orderIdentity())
        assertNotEquals(base, intent(listOf(a to 1_000L), url = "https://merchant.example/pay/order-2").orderIdentity())
        assertNotEquals(base, intent(listOf(a to 1_000L), payee = "Merchant", verifiedBy = "CA").orderIdentity())
    }

    @Test
    fun `nothing stable to key on gives no identity`() {
        assertNull(intent(listOf(a to 1_000L), url = null).orderIdentity())
        assertNull(intent(emptyList()).orderIdentity())
    }

    // --- BIP70 merchant_data: the merchant's own order identifier (#1628) ---

    @Test
    fun `merchant data tells apart two orders with the same url and outputs`() {
        // one endpoint, two gift cards of the same price: the same url and outputs, two orders
        val first = intent(listOf(a to 1_000L), merchantData = "order-1001".toByteArray())
        val second = intent(listOf(a to 1_000L), merchantData = "order-1002".toByteArray())
        assertNotEquals(first.orderIdentity(), second.orderIdentity())
    }

    @Test
    fun `a refreshed request carrying the same merchant data is the same order`() {
        val first = intent(listOf(a to 1_000L), requestHash = ByteArray(32) { 1 }, merchantData = "order-1001".toByteArray())
        val refreshed = intent(listOf(a to 1_000L), requestHash = ByteArray(32) { 2 }, merchantData = "order-1001".toByteArray())
        assertEquals(first.orderIdentity(), refreshed.orderIdentity())
    }

    @Test
    fun `merchant data is keyed together with the url, the outputs and the verified merchant`() {
        val md = "order-1001".toByteArray()
        val base = intent(listOf(a to 1_000L), merchantData = md).orderIdentity()
        assertNotEquals(base, intent(listOf(a to 1_001L), merchantData = md).orderIdentity())
        assertNotEquals(base, intent(listOf(a to 1_000L), url = "https://other.example/pay", merchantData = md).orderIdentity())
        assertNotEquals(base, intent(listOf(a to 1_000L), payee = "Merchant", verifiedBy = "CA", merchantData = md).orderIdentity())
    }

    @Test
    fun `without merchant data the identity is the url and outputs heuristic`() {
        val plain = intent(listOf(a to 1_000L))
        assertEquals(plain.legacyOrderIdentity(), plain.orderIdentity())
        // with it, the heuristic stays available to match records stamped before merchant data
        val withData = intent(listOf(a to 1_000L), merchantData = "order-1001".toByteArray())
        assertEquals(plain.orderIdentity(), withData.legacyOrderIdentity())
        assertNotEquals(withData.orderIdentity(), withData.legacyOrderIdentity())
        // empty merchant data is no merchant data
        assertEquals(plain.orderIdentity(), intent(listOf(a to 1_000L), merchantData = ByteArray(0)).orderIdentity())
    }
}
