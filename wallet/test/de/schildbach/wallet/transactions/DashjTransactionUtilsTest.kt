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

package de.schildbach.wallet.transactions

import de.schildbach.wallet.service.platform.sdk.L1TxUiDirection
import de.schildbach.wallet.service.platform.sdk.L1TxUiStatus
import de.schildbach.wallet.service.platform.sdk.SdkTxDetail
import io.mockk.every
import io.mockk.mockk
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.WalletTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MO-1108 regression: the transaction-details sheet for a dashj-held,
 * SDK-authored send was listing its own change output as a second "Sent to"
 * recipient (the held dashj wallet doesn't recognize an SDK-authored send's
 * change address as "mine") and showing no fee row (`tx.fee` is null when
 * the held wallet can't connect the spent inputs). [SdkTxDetail], when
 * supplied as an override, carries both already correctly.
 */
class DashjTransactionUtilsTest {
    private val params = TestNet3Params.get()
    private val bitcoinjContext = Context(params)

    private val payeeAddress = Address.fromBase58(params, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy")
    private val changeAddress = Address.fromBase58(params, "yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi")

    private fun bagMock(myAddresses: List<Address>): TransactionBag {
        val bag = mockk<TransactionBag>()
        every { bag.isPubKeyHashMine(any(), any()) } answers {
            val hash = firstArg<ByteArray>()
            myAddresses.any { it.hash.contentEquals(hash) }
        }
        every { bag.isPubKeyMine(any()) } returns false
        every { bag.isPayToScriptHashMine(any()) } returns false
        every { bag.isCoinJoinPubKeyHashMine(any(), any()) } returns false
        every { bag.isCoinJoinPubKeyMine(any()) } returns false
        every { bag.isCoinJoinPayToScriptHashMine(any()) } returns false
        every { bag.isWatchedScript(any()) } returns false
        every { bag.isFullyMixed(any()) } returns false
        every { bag.isLockedOutput(any()) } returns false
        every { bag.getTransactionPool(WalletTransaction.Pool.UNSPENT) } returns mapOf()
        every { bag.getTransactionPool(WalletTransaction.Pool.SPENT) } returns mapOf()
        every { bag.getTransactionPool(WalletTransaction.Pool.PENDING) } returns mapOf()
        return bag
    }

    private fun sentOverride(feeDuffs: Long?, outputAddresses: List<String>, decodeFailed: Boolean = false) =
        SdkTxDetail(
            txIdDisplayHex = "a".repeat(64),
            netAmountDuffs = -(500_000L + (feeDuffs ?: 0L)),
            feeDuffs = feeDuffs,
            timestampMs = 0L,
            status = L1TxUiStatus.CHAINLOCKED,
            direction = L1TxUiDirection.OUTGOING,
            inputAddresses = emptyList(),
            outputAddresses = outputAddresses,
            hasOpReturn = false,
            decodeFailed = decodeFailed
        )

    private fun sendWithChange(): Transaction {
        bitcoinjContext.let { Context.propagate(it) }
        val tx = Transaction(params)
        tx.addOutput(Coin.valueOf(500_000), payeeAddress)
        tx.addOutput(Coin.valueOf(499_774), changeAddress)
        return tx
    }

    // ── getSentToAddressesForDetail ──────────────────────────────────

    @Test
    fun `without an override falls back to the dashj isMine check`() {
        val tx = sendWithChange()
        val bag = bagMock(myAddresses = listOf(changeAddress))

        val addresses = TransactionUtils.getSentToAddressesForDetail(tx, bag, sdkOverride = null)

        assertEquals(listOf(payeeAddress), addresses)
    }

    @Test
    fun `dashj isMine not recognizing the change output lists it as a second recipient`() {
        // Reproduces the bug: the held dashj wallet doesn't know this
        // SDK-authored send's change address, so isMine(bag) is false for it too.
        val tx = sendWithChange()
        val bag = bagMock(myAddresses = emptyList())

        val addresses = TransactionUtils.getSentToAddressesForDetail(tx, bag, sdkOverride = null)

        assertEquals(listOf(payeeAddress, changeAddress), addresses)
    }

    @Test
    fun `an sdk override excludes its own change output even when dashj does not recognize it`() {
        val tx = sendWithChange()
        val bag = bagMock(myAddresses = emptyList()) // dashj recognizes nothing of this tx
        val override = sentOverride(feeDuffs = 226L, outputAddresses = listOf(payeeAddress.toBase58()))

        val addresses = TransactionUtils.getSentToAddressesForDetail(tx, bag, override)

        assertEquals(listOf(payeeAddress), addresses)
    }

    @Test
    fun `a failed decode has no addresses to trust so it falls back to the dashj list`() {
        val tx = sendWithChange()
        val bag = bagMock(myAddresses = listOf(changeAddress))
        val override = sentOverride(feeDuffs = 226L, outputAddresses = emptyList(), decodeFailed = true)

        val addresses = TransactionUtils.getSentToAddressesForDetail(tx, bag, override)

        assertEquals(listOf(payeeAddress), addresses)
    }

    // ── getFeeForDetail ───────────────────────────────────────────────

    @Test
    fun `without an override falls back to the dashj-computed fee`() {
        val tx = sendWithChange()

        assertNull("unconnected inputs leave dashj's fee null", TransactionUtils.getFeeForDetail(tx, sdkOverride = null))
    }

    @Test
    fun `an sdk override fee is preferred when the dashj fee is unavailable`() {
        val tx = sendWithChange()
        val override = sentOverride(feeDuffs = 226L, outputAddresses = listOf(payeeAddress.toBase58()))

        assertEquals(Coin.valueOf(226L), TransactionUtils.getFeeForDetail(tx, override))
    }

    @Test
    fun `a null override fee falls back to the dashj-computed fee`() {
        val tx = sendWithChange()
        val override = sentOverride(feeDuffs = null, outputAddresses = listOf(payeeAddress.toBase58()))

        assertNull(TransactionUtils.getFeeForDetail(tx, override))
    }
}
