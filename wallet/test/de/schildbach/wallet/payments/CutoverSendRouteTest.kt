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
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.ZeroConfCoinSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Phase 5d: the pure routing decision for dashj-typed sends under the
 * cutover state ([cutoverSendRoute]). The load-bearing invariant: once the
 * cutover is committed there is NO dashj route — a held peergroup would
 * queue-not-send, so anything the SDK can't take must FAIL CLOSED.
 */
class CutoverSendRouteTest {

    @Test
    fun preCutover_everySendIsTheUnchangedDashjPath() {
        assertEquals(CutoverSendRoute.DASHJ, cutoverSendRoute(false, hasCustomSelector = false, emptyWallet = false))
        assertEquals(CutoverSendRoute.DASHJ, cutoverSendRoute(false, hasCustomSelector = true, emptyWallet = false))
        assertEquals(CutoverSendRoute.DASHJ, cutoverSendRoute(false, hasCustomSelector = false, emptyWallet = true))
        assertEquals(CutoverSendRoute.DASHJ, cutoverSendRoute(false, hasCustomSelector = true, emptyWallet = true))
    }

    @Test
    fun committed_simplePayToAddressRoutesThroughTheSdkBridge() {
        assertEquals(
            CutoverSendRoute.SDK_BRIDGED,
            cutoverSendRoute(true, hasCustomSelector = false, emptyWallet = false)
        )
    }

    @Test
    fun committed_sendAllRoutesThroughTheSdkBridge() {
        // Step B: the SDK drain (SelectionStrategy::All) owns send-all
        // post-cutover — no more FAIL_CLOSED for emptyWallet.
        assertEquals(
            CutoverSendRoute.SDK_BRIDGED,
            cutoverSendRoute(true, hasCustomSelector = false, emptyWallet = true)
        )
    }

    @Test
    fun committed_customSelector_failsClosed_neverDashj() {
        assertEquals(CutoverSendRoute.FAIL_CLOSED, cutoverSendRoute(true, hasCustomSelector = true, emptyWallet = false))
        assertEquals(CutoverSendRoute.FAIL_CLOSED, cutoverSendRoute(true, hasCustomSelector = true, emptyWallet = true))
    }
}

/**
 * Phase 5d: [extractSdkRoutablePayment] — identifying the ONE payment a
 * completed SendRequest carries, or refusing (null → fail closed). The
 * main send UI submits its own signed SendRequest to the funnel, so this
 * extraction is what lets those sends route via the SDK post-cutover.
 */
class ExtractSdkRoutablePaymentTest {

    private val params = TestNet3Params.get()
    private val payee: Address = Address.fromKey(params, ECKey())
    private val change: Address = Address.fromKey(params, ECKey())
    private val isMine: (Address) -> Boolean = { it == change }

    @Before
    fun setup() {
        Context.propagate(Context(params))
    }

    private fun request(build: Transaction.() -> Unit): SendRequest =
        SendRequest.forTx(Transaction(params).apply(build))

    @Test
    fun simpleSend_paymentPlusChange_extractsTheForeignOutput() {
        val request = request {
            addOutput(Coin.valueOf(5_000_000), payee)
            addOutput(Coin.valueOf(94_900_000), change)
        }
        assertEquals(
            SdkRoutablePayment(payee, Coin.valueOf(5_000_000), sendAll = false),
            extractSdkRoutablePayment(request, params, isMine)
        )
    }

    @Test
    fun noChangeOutput_stillExtractsTheSingleForeignOutput() {
        val request = request { addOutput(Coin.valueOf(5_000_000), payee) }
        assertEquals(
            SdkRoutablePayment(payee, Coin.valueOf(5_000_000), sendAll = false),
            extractSdkRoutablePayment(request, params, isMine)
        )
    }

    @Test
    fun sendAll_isRoutable_andFlagged() {
        // Step B: an emptyWallet request with one identifiable foreign
        // output routes to the SDK drain — sendAll carries dashj's
        // emptyWallet semantics into the SDK route.
        assertEquals(
            SdkRoutablePayment(payee, Coin.valueOf(5_000_000), sendAll = true),
            extractSdkRoutablePayment(
                request { addOutput(Coin.valueOf(5_000_000), payee) }.apply { emptyWallet = true },
                params,
                isMine
            )
        )
    }

    @Test
    fun sendAll_conservativeRefusalsStillApply() {
        // The sendAll flag never relaxes the other refusals: a send-all
        // with a custom selector stays unroutable.
        assertNull(
            extractSdkRoutablePayment(
                request { addOutput(Coin.valueOf(5_000_000), payee) }.apply {
                    emptyWallet = true
                    coinSelector = org.bitcoinj.wallet.CoinSelector { _, _ -> throw UnsupportedOperationException() }
                },
                params,
                isMine
            )
        )
        // …and a multi-recipient send-all cannot identify THE payment.
        assertNull(
            extractSdkRoutablePayment(
                request {
                    addOutput(Coin.valueOf(5_000_000), payee)
                    addOutput(Coin.valueOf(1_000_000), Address.fromKey(params, ECKey()))
                }.apply { emptyWallet = true },
                params,
                isMine
            )
        )
    }

    @Test
    fun notRoutable_returnsNull_forEveryConservativeRefusal() {
        // Custom (non-zero-conf) coin selector.
        assertNull(
            extractSdkRoutablePayment(
                request { addOutput(Coin.valueOf(5_000_000), payee) }
                    .apply { coinSelector = org.bitcoinj.wallet.CoinSelector { _, _ -> throw UnsupportedOperationException() } },
                params,
                isMine
            )
        )
        // The default zero-conf selector IS routable — sanity-check the inverse.
        assertEquals(
            SdkRoutablePayment(payee, Coin.valueOf(5_000_000), sendAll = false),
            extractSdkRoutablePayment(
                request { addOutput(Coin.valueOf(5_000_000), payee) }
                    .apply { coinSelector = ZeroConfCoinSelector.get() },
                params,
                isMine
            )
        )
        // Multiple foreign recipients (BIP70 multi-output).
        assertNull(
            extractSdkRoutablePayment(
                request {
                    addOutput(Coin.valueOf(5_000_000), payee)
                    addOutput(Coin.valueOf(1_000_000), Address.fromKey(params, ECKey()))
                },
                params,
                isMine
            )
        )
        // Send-to-self only (no identifiable payment).
        assertNull(
            extractSdkRoutablePayment(
                request { addOutput(Coin.valueOf(5_000_000), change) },
                params,
                isMine
            )
        )
        // Non-standard output script (OP_RETURN payload).
        assertNull(
            extractSdkRoutablePayment(
                request {
                    addOutput(Coin.valueOf(5_000_000), payee)
                    addOutput(Coin.ZERO, ScriptBuilder.createOpReturnScript(byteArrayOf(1, 2, 3)))
                },
                params,
                isMine
            )
        )
    }
}
