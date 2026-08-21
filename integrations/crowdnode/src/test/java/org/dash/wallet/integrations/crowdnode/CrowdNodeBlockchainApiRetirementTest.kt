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

package org.dash.wallet.integrations.crowdnode

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeBlockchainApi
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeException
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeServiceUnavailableException
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

/**
 * The six on-chain senders of [CrowdNodeBlockchainApi] are fenced off behind
 * [CrowdNodeConstants.SIGNUP_AND_DEPOSITS_ENABLED]: CrowdNode disabled
 * account creation and deposits service-side and every remaining user is on
 * the API path.
 *
 * The original dashj bodies are preserved as the template for a future SDK
 * port, so these tests pin the two properties that make that safe:
 *
 * 1. Each sender THROWS [CrowdNodeServiceUnavailableException] — it must not
 *    quietly no-op, because an operation that reports success while moving
 *    no funds is the failure mode being refused.
 * 2. Each throws BEFORE any side effect, proving the preserved body is
 *    unreached. No send, no output locking, no top-up self-send, no
 *    partially-executed flow — which is also what retires the
 *    partial-deposit stranding hazard.
 *
 * The passive observers are deliberately untouched and still serve balances,
 * history, withdrawals and restore.
 */
class CrowdNodeBlockchainApiRetirementTest {
    private val accountAddress = "yihMSMoesHX1JhbntTiV5Nptf5NLrmFMCu"

    // TxInfo is a final class and cannot be mocked; the module's TestTx
    // helper builds a real one. Its contents are irrelevant here - the point
    // is that resendConfirmationTx refuses before ever reading it.
    private val confirmationTxData = "01000000016fdb75611fd8892d8d19707f0d0958da5b930c635750f2c8c5bf5a48458a8ffd000000006b483045022100ff77055377b33afb8fc2f622fda59816394a567c50e98569fe8ab68d797948b802204b05504b3f837e80f4d0d8c3c6d98107e770572a8eaae66ddd14a660fe9674f101210275ab1f1c864e594c5e075ac45fbd01a45285701e429b842f8d0a1c872cf3a7baffffffff0149d00000000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac00000000" // ktlint-disable max-line-length

    /**
     * Only `networkId` is stubbed — it is read once at construction. Any
     * OTHER interaction with the wallet would be a side effect, which is
     * exactly what the assertions below rule out.
     */
    private val walletData = mock<WalletDataProvider> {
        on { networkId } doReturn AddressNetwork.ID_TESTNET
    }

    /**
     * The real send service. Left completely unstubbed on purpose: every
     * assertion below requires that it is never called at all.
     */
    private val paymentService = mock<SendPaymentService>()

    private val api = CrowdNodeBlockchainApi(paymentService, walletData)

    /** Runs [block], returning the typed exception or failing the test. */
    private fun assertRetired(operation: String, block: suspend () -> Unit) {
        val ex = try {
            runBlocking { block() }
            fail("$operation must throw CrowdNodeServiceUnavailableException, not succeed silently")
            return
        } catch (ex: CrowdNodeServiceUnavailableException) {
            ex
        }

        assertEquals("wrong operation tag", operation, ex.operation)
        // The existing handlers (CrowdNodeApi's catch(Exception) and
        // CrowdNodeConfirmationTxHandler's catch(CrowdNodeException)) must be
        // able to turn this into an error state rather than an uncaught crash.
        assertTrue("must remain a CrowdNodeException", ex is CrowdNodeException)
    }

    /**
     * No sender may do anything before refusing: no send, no output locking,
     * no observing. The guard is the first statement in each method, so the
     * original dashj body below it must be provably unreached.
     */
    private fun assertNoSideEffects() {
        runBlocking { verifyNoInteractions(paymentService) }
        verify(walletData, never()).lockOutputsPayingTo(any(), any())
        runBlocking { verify(walletData, never()).waitUntilLocked(any()) }
        verify(walletData, never()).observeTransactions(any(), any())
        verify(walletData, never()).getTransactions(any())
    }

    // ── Each sender throws, with no side effect ───────────────────────

    @Test
    fun topUpAddress_isRetired() {
        assertRetired("topUpAddress") { api.topUpAddress(accountAddress, Dash.COIN) }
        assertNoSideEffects()
    }

    @Test
    fun makeSignUpRequest_isRetired() {
        assertRetired("makeSignUpRequest") { api.makeSignUpRequest(accountAddress) }
        assertNoSideEffects()
    }

    @Test
    fun acceptTerms_isRetired() {
        assertRetired("acceptTerms") { api.acceptTerms(accountAddress) }
        assertNoSideEffects()
    }

    @Test
    fun deposit_isRetired() {
        assertRetired("deposit") {
            api.deposit(accountAddress, Dash.COIN, emptyWallet = false, checkBalanceConditions = true)
        }
        assertNoSideEffects()
    }

    @Test
    fun requestWithdrawal_isRetired() {
        assertRetired("requestWithdrawal") { api.requestWithdrawal(accountAddress, Dash.COIN) }
        assertNoSideEffects()
    }

    @Test
    fun resendConfirmationTx_isRetired_withoutLockingOutputsFirst() {
        // This one used to call lockOutputsPayingTo as its FIRST statement,
        // before sending — so it is the sharpest test of "throw before any
        // side effect".
        val confirmationTx = TestTx(confirmationTxData).toTxInfo()
        assertRetired("resendConfirmationTx") { api.resendConfirmationTx(confirmationTx, accountAddress) }
        assertNoSideEffects()
    }

    // ── The gate ──────────────────────────────────────────────────────

    @Test
    fun signupAndDepositsAreGatedOff() {
        // The UI gate is what users meet; the throws above are the backstop.
        // If this is ever flipped back on, the senders still refuse — see the
        // constant's doc.
        assertFalse(
            "signup/deposit UI must stay gated while the senders are retired",
            CrowdNodeConstants.SIGNUP_AND_DEPOSITS_ENABLED
        )
    }

    // ── Observers stay alive ──────────────────────────────────────────

    @Test
    fun passiveObserversStillReadTheWallet() {
        // Balance/history/restore must keep working: getDeposits is a pure
        // read and must still reach the wallet rather than throw.
        val deposits = api.getDeposits(accountAddress)
        assertTrue("observer should return the wallet's (empty) result", deposits.isEmpty())
        verify(walletData).getTransactions(any())
    }
}
