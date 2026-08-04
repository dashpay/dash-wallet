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
import de.schildbach.wallet.service.platform.sdk.L1_FUNDING_GATE_CLOSED_REASON
import de.schildbach.wallet.service.platform.sdk.L1_SIGNER_LOCKED_REASON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 5d: the pure routing decision for dashj-typed sends under the
 * cutover state ([cutoverSendRoute]). The load-bearing invariant: once the
 * cutover is committed there is NO dashj route — a held peergroup would
 * queue-not-send, so anything the SDK can't take must FAIL CLOSED.
 */
class CutoverSendRouteTest {

    private fun route(
        cutoverCommitted: Boolean,
        hasCustomSelector: Boolean = false,
        hasLockedOutputPredicate: Boolean = false,
        emptyWallet: Boolean = false
    ) = cutoverSendRoute(cutoverCommitted, hasCustomSelector, hasLockedOutputPredicate, emptyWallet)

    @Test
    fun preCutover_everySendIsTheUnchangedDashjPath() {
        for (selector in listOf(false, true)) {
            for (lockedPredicate in listOf(false, true)) {
                for (emptyWallet in listOf(false, true)) {
                    assertEquals(
                        CutoverSendRoute.DASHJ,
                        route(
                            false,
                            hasCustomSelector = selector,
                            hasLockedOutputPredicate = lockedPredicate,
                            emptyWallet = emptyWallet
                        )
                    )
                }
            }
        }
    }

    @Test
    fun committed_simplePayToAddressRoutesThroughTheSdkBridge() {
        assertEquals(CutoverSendRoute.SDK_BRIDGED, route(true))
    }

    @Test
    fun committed_typedSendAll_failsClosed_neverTheDrain() {
        // FUNDS-CRITICAL (deposit-all atomicity): the typed overloads'
        // emptyWallet must FAIL CLOSED, never SDK_BRIDGED — CrowdNode's
        // deposit-all is topUp(no selector, emptyWallet) then
        // deposit(ByAddress selector → always FAIL_CLOSED); draining step 1
        // and failing step 2 would wedge the full balance at the account
        // address. Only the main-UI funnel (extractSdkRoutablePayment,
        // sendAll = true) may route a send-all to the SDK drain.
        assertEquals(CutoverSendRoute.FAIL_CLOSED, route(true, emptyWallet = true))
        assertEquals(CutoverSendRoute.FAIL_CLOSED, route(true, hasCustomSelector = true, emptyWallet = true))
        assertEquals(CutoverSendRoute.FAIL_CLOSED, route(true, hasLockedOutputPredicate = true, emptyWallet = true))
    }

    @Test
    fun committed_customSelector_failsClosed_neverDashj() {
        assertEquals(CutoverSendRoute.FAIL_CLOSED, route(true, hasCustomSelector = true))
        assertEquals(CutoverSendRoute.FAIL_CLOSED, route(true, hasCustomSelector = true, emptyWallet = true))
    }

    @Test
    fun committed_lockedOutputPredicate_failsClosed_evenWithNoSelector() {
        // The predicate is dashj-only machinery an SDK route would silently
        // DROP (spending outputs the caller thought were protected) — it
        // must fail closed on its own, selector or not.
        assertEquals(CutoverSendRoute.FAIL_CLOSED, route(true, hasLockedOutputPredicate = true))
        assertEquals(
            CutoverSendRoute.FAIL_CLOSED,
            route(true, hasCustomSelector = true, hasLockedOutputPredicate = true)
        )
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
    private val ownRecipient: Address = Address.fromKey(params, ECKey())
    private val isMine: (Address) -> Boolean = { it == change || it == ownRecipient }

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
            extractSdkRoutablePayment(request, params, isMine = isMine)
        )
    }

    @Test
    fun noChangeOutput_stillExtractsTheSingleForeignOutput() {
        val request = request { addOutput(Coin.valueOf(5_000_000), payee) }
        assertEquals(
            SdkRoutablePayment(payee, Coin.valueOf(5_000_000), sendAll = false),
            extractSdkRoutablePayment(request, params, isMine = isMine)
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
                isMine = isMine
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
                isMine = isMine
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
                isMine = isMine
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
                isMine = isMine
            )
        )
        // The default zero-conf selector IS routable — sanity-check the inverse.
        assertEquals(
            SdkRoutablePayment(payee, Coin.valueOf(5_000_000), sendAll = false),
            extractSdkRoutablePayment(
                request { addOutput(Coin.valueOf(5_000_000), payee) }
                    .apply { coinSelector = ZeroConfCoinSelector.get() },
                params,
                isMine = isMine
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
                isMine = isMine
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
                isMine = isMine
            )
        )
    }

    // ── Self-sends (on-device bug, build 11.10.44): a send to the wallet's
    // OWN receive address has ZERO foreign outputs — recipient and change
    // are both "mine" — so the outputs alone cannot name the payment. The
    // send UI's intent (the payment intent's address) resolves it. ──

    @Test
    fun selfSend_withChange_routesViaTheIntendedRecipient() {
        // The observed failure: 0.001 tDASH to the wallet's own receive
        // address. With the UI's intent threaded through, the output paying
        // the intended recipient IS the payment; the other own output is
        // change.
        val request = request {
            addOutput(Coin.valueOf(100_000), ownRecipient)
            addOutput(Coin.valueOf(94_900_000), change)
        }
        assertEquals(
            SdkRoutablePayment(ownRecipient, Coin.valueOf(100_000), sendAll = false),
            extractSdkRoutablePayment(request, params, intendedRecipient = ownRecipient, isMine = isMine)
        )
    }

    @Test
    fun selfSend_recipientAlsoTheChangeAddress_sumsTheOutputs() {
        // Degenerate self-send where change lands on the SAME address as the
        // payment: both outputs pay the intended recipient — sum them (the
        // address receives the total either way).
        val request = request {
            addOutput(Coin.valueOf(100_000), ownRecipient)
            addOutput(Coin.valueOf(500_000), ownRecipient)
        }
        assertEquals(
            SdkRoutablePayment(ownRecipient, Coin.valueOf(600_000), sendAll = false),
            extractSdkRoutablePayment(request, params, intendedRecipient = ownRecipient, isMine = isMine)
        )
    }

    @Test
    fun sendAllToSelf_singleOwnOutput_routesWithoutIntent() {
        // A one-output all-mine tx is unambiguous even with no intent — the
        // single output IS the payment. emptyWallet carries through as the
        // SDK drain's sendAll.
        val request = request { addOutput(Coin.valueOf(5_000_000), ownRecipient) }
            .apply { emptyWallet = true }
        assertEquals(
            SdkRoutablePayment(ownRecipient, Coin.valueOf(5_000_000), sendAll = true),
            extractSdkRoutablePayment(request, params, isMine = isMine)
        )
    }

    @Test
    fun plainSelfSend_singleOwnOutput_routesWithoutIntent() {
        // Same unambiguity for a plain (non-send-all) single own output —
        // the raw-SendRequest path with no intent still has exactly one
        // candidate payment. sendAll stays false: forcing drain semantics
        // here would spend the WHOLE wallet on a 0.05 self-send.
        val request = request { addOutput(Coin.valueOf(5_000_000), change) }
        assertEquals(
            SdkRoutablePayment(change, Coin.valueOf(5_000_000), sendAll = false),
            extractSdkRoutablePayment(request, params, isMine = isMine)
        )
    }

    @Test
    fun allMineMultiOutput_withoutIntent_failsClosed() {
        // Two same-owner outputs and no intent: recipient vs change cannot
        // be told apart — never guess, keep failing closed.
        assertNull(
            extractSdkRoutablePayment(
                request {
                    addOutput(Coin.valueOf(100_000), ownRecipient)
                    addOutput(Coin.valueOf(94_900_000), change)
                },
                params,
                isMine = isMine
            )
        )
    }

    @Test
    fun intent_noOutputPaysIt_failsClosed() {
        // An intent no all-mine output actually pays proves the request does
        // not match the UI's payment — refuse rather than guess.
        assertNull(
            extractSdkRoutablePayment(
                request {
                    addOutput(Coin.valueOf(100_000), ownRecipient)
                    addOutput(Coin.valueOf(94_900_000), change)
                },
                params,
                intendedRecipient = Address.fromKey(params, ECKey()),
                isMine = isMine
            )
        )
    }

    @Test
    fun foreignSend_withIntent_behavesExactlyAsWithout() {
        // A normal send is identified by its single foreign output with or
        // without the intent — threading the intent must not disturb it.
        val request = request {
            addOutput(Coin.valueOf(5_000_000), payee)
            addOutput(Coin.valueOf(94_900_000), change)
        }
        assertEquals(
            SdkRoutablePayment(payee, Coin.valueOf(5_000_000), sendAll = false),
            extractSdkRoutablePayment(request, params, intendedRecipient = payee, isMine = isMine)
        )
    }

    @Test
    fun conservativeRefusals_holdEvenWithIntent() {
        // The intent resolves self-send ambiguity ONLY — it never relaxes
        // the selector/predicate/script refusals.
        assertNull(
            extractSdkRoutablePayment(
                request { addOutput(Coin.valueOf(100_000), ownRecipient) }
                    .apply { coinSelector = org.bitcoinj.wallet.CoinSelector { _, _ -> throw UnsupportedOperationException() } },
                params,
                intendedRecipient = ownRecipient,
                isMine = isMine
            )
        )
        // OP_RETURN payload (the Maya-style shape) stays unroutable even
        // when every other output pays the intended recipient.
        assertNull(
            extractSdkRoutablePayment(
                request {
                    addOutput(Coin.valueOf(100_000), ownRecipient)
                    addOutput(Coin.ZERO, ScriptBuilder.createOpReturnScript(byteArrayOf(1, 2, 3)))
                },
                params,
                intendedRecipient = ownRecipient,
                isMine = isMine
            )
        )
    }
}

/**
 * The typed post-cutover NotBroadcast conversion ([sdkSendNotAttemptedException])
 * — what lets the send UI tell "the engine is still syncing" apart from every
 * other refusal WITHOUT matching on user-visible strings (the 11.10.44
 * misdiagnosis: a routability failure shown as "wallet is not fully synced").
 */
class SdkSendNotAttemptedExceptionTest {

    @Test
    fun fundingGateClosedReason_isTheNotSyncedType() {
        val ex = sdkSendNotAttemptedException(
            "$L1_FUNDING_GATE_CLOSED_REASON: filter scan 12345 behind tip 67890"
        )
        assertTrue(
            "a closed funding gate is the one honest not-synced case, got ${ex::class.simpleName}",
            ex is SendEngineNotSyncedException
        )
    }

    @Test
    fun signerLockedReason_isTheSignerLockedType() {
        // The typed TransactionSigning refusal (v41int14, code 33): the
        // request is valid and retryable after unlock — it must surface as
        // its own type, never as not-synced and never as a generic hard
        // failure.
        val ex = sdkSendNotAttemptedException(
            "$L1_SIGNER_LOCKED_REASON — retryable after unlock: keystore locked"
        )
        assertTrue(
            "a locked signer must get the unlock-and-retry type, got ${ex::class.simpleName}",
            ex is SendSignerLockedException
        )
        assertFalse(ex is SendEngineNotSyncedException)
    }

    @Test
    fun anyOtherReason_isNeitherNotSyncedNorNotSupportedNorSignerLocked() {
        for (reason in listOf(
            "flag off",
            "app wallet not bound to the SDK",
            "core send failed pre-broadcast (build/funding): transaction build failed",
            "core send failed pre-broadcast (transaction build): funding path matches no account"
        )) {
            val ex = sdkSendNotAttemptedException(reason)
            assertFalse("'$reason' must not read as not-synced", ex is SendEngineNotSyncedException)
            assertFalse("'$reason' must not read as not-routable", ex is SendNotSdkRoutableException)
            assertFalse("'$reason' must not read as signer-locked", ex is SendSignerLockedException)
        }
    }

    @Test
    fun messages_neverContainTheInternalRollbackAction() {
        // ROLLBACK_CUTOVER is an internal debug action; it must not appear in
        // any exception message (belt and braces: the UI never renders these
        // messages, but logs/crash reports get pasted into bug tickets).
        for (reason in listOf("$L1_FUNDING_GATE_CLOSED_REASON: engine idle", "flag off")) {
            val message = sdkSendNotAttemptedException(reason).message.orEmpty()
            assertFalse(message, message.contains("ROLLBACK_CUTOVER"))
        }
    }
}
