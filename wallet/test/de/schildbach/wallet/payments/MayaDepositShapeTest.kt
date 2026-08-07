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

import org.dashfoundation.dashsdk.keywallet.DecodedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host coverage for [verifyMayaDepositShape] — the pre-broadcast gate that
 * keeps a mis-shaped deposit (which MAYAChain would strand or mis-refund)
 * from ever reaching the network. Fixtures are hand-built
 * [DecodedTransaction]s — the decoder itself is pinned against the Rust
 * fixture in `TransactionDecoderTest`, so this suite only owns the shape
 * rules, with no wallet, native library, or dashj involved.
 */
class MayaDepositShapeTest {

    private val vaultAddress = "yMqShkrgjTRuReBGFpQr7FozEF1QcNBBYA"
    private val senderAddress = "yNDj28QBMm5sY6bLjFcNdWRNef24KLQNuQ"
    private val memo = "=:ETH.ETH:0x1c7b17362c84287bd1184447e6dfeaf920c31bbe".toByteArray()
    private val vaultDuffs = 1_000_000L

    private fun p2pkhScript(seed: Byte): ByteArray =
        byteArrayOf(0x76, 0xa9.toByte(), 0x14) + ByteArray(20) { seed } + byteArrayOf(0x88.toByte(), 0xac.toByte())

    private fun addressOutput(address: String, duffs: Long, scriptSeed: Byte) =
        DecodedTransaction.Output(address, duffs, p2pkhScript(scriptSeed))

    private fun memoOutput(memoBytes: ByteArray = memo, duffs: Long = 0L) =
        DecodedTransaction.Output(null, duffs, expectedOpReturnScript(memoBytes))

    private fun input(senderAddr: String? = senderAddress) =
        DecodedTransaction.Input(ByteArray(32), 0, senderAddr)

    private fun deposit(
        vaultValue: Long = vaultDuffs,
        withChange: Boolean = true,
        changeAddress: String = senderAddress,
        vin0Address: String? = senderAddress,
        memoBytes: ByteArray = memo,
        memoValue: Long = 0L
    ): DecodedTransaction {
        val outputs = mutableListOf(
            addressOutput(vaultAddress, vaultValue, scriptSeed = 1),
            memoOutput(memoBytes, memoValue)
        )
        if (withChange) {
            outputs += addressOutput(changeAddress, 50_000, scriptSeed = 2)
        }
        return DecodedTransaction(ByteArray(32), listOf(input(vin0Address)), outputs)
    }

    private fun verify(tx: DecodedTransaction): String? =
        verifyMayaDepositShape(tx, vaultAddress, vaultDuffs, memo)

    @Test
    fun wellFormedDepositPasses() {
        assertNull(verify(deposit()))
    }

    @Test
    fun wellFormedDepositWithoutChangePasses() {
        assertNull(verify(deposit(withChange = false)))
    }

    @Test
    fun longMemoUsesPushdata1AndPasses() {
        // 76..80 bytes crosses the OP_PUSHDATA1 boundary in the expected script.
        val longMemo = ByteArray(80) { 0x41 }
        val tx = deposit(memoBytes = longMemo)
        assertNull(verifyMayaDepositShape(tx, vaultAddress, vaultDuffs, longMemo))
        assertEquals(0x4c.toByte(), tx.outputs[1].scriptPubkey[1])
    }

    @Test
    fun wrongVaultAmountFails() {
        val error = verify(deposit(vaultValue = vaultDuffs + 1))
        assertNotNull(error)
        assertTrue(error!!.contains("VOUT0"))
    }

    @Test
    fun wrongVaultAddressFails() {
        val tx = deposit()
        val error = verifyMayaDepositShape(tx, senderAddress, vaultDuffs, memo)
        assertNotNull(error)
        assertTrue(error!!.contains("expected the Asgard vault"))
    }

    @Test
    fun wrongMemoFails() {
        val error = verify(deposit(memoBytes = "=:ETH.ETH:0xWRONG".toByteArray()))
        assertNotNull(error)
        assertTrue(error!!.contains("VOUT1"))
    }

    @Test
    fun valueCarryingOpReturnFails() {
        val error = verify(deposit(memoValue = 546L))
        assertNotNull(error)
        assertTrue(error!!.contains("zero-value"))
    }

    @Test
    fun memoNotAtVout1Fails() {
        // vault, change, memo — memo displaced to VOUT2 must fail.
        val tx = DecodedTransaction(
            ByteArray(32),
            listOf(input()),
            listOf(
                addressOutput(vaultAddress, vaultDuffs, scriptSeed = 1),
                addressOutput(senderAddress, 50_000, scriptSeed = 2),
                memoOutput()
            )
        )
        val error = verify(tx)
        assertNotNull(error)
        assertTrue(error!!.contains("VOUT1"))
    }

    @Test
    fun changeToForeignAddressFails() {
        val error = verify(deposit(changeAddress = "yTForeignAddressXXXXXXXXXXXXXXXXXX"))
        assertNotNull(error)
        assertEquals("VOUT2 change does not pay VIN0's address", error)
    }

    @Test
    fun unknownVin0AddressSkipsChangeOwnershipCheck() {
        // A non-P2PKH scriptSig gives the decoder no sender address; the
        // engine's change_to_first_input contract is the remaining guarantee.
        assertNull(verify(deposit(vin0Address = null, changeAddress = "yTForeignAddressXXXXXXXXXXXXXXXXXX")))
    }

    @Test
    fun nonP2pkhChangeFails() {
        val tx = DecodedTransaction(
            ByteArray(32),
            listOf(input()),
            listOf(
                addressOutput(vaultAddress, vaultDuffs, scriptSeed = 1),
                memoOutput(),
                // P2SH-shaped change (a9 14 <20B> 87) must be rejected.
                DecodedTransaction.Output(
                    senderAddress,
                    50_000,
                    byteArrayOf(0xa9.toByte(), 0x14) + ByteArray(20) { 3 } + byteArrayOf(0x87.toByte())
                )
            )
        )
        val error = verify(tx)
        assertNotNull(error)
        assertTrue(error!!.contains("not P2PKH"))
    }

    @Test
    fun extraOutputFails() {
        val tx = DecodedTransaction(
            ByteArray(32),
            listOf(input()),
            listOf(
                addressOutput(vaultAddress, vaultDuffs, scriptSeed = 1),
                memoOutput(),
                addressOutput(senderAddress, 50_000, scriptSeed = 2),
                addressOutput(senderAddress, 1_000, scriptSeed = 4)
            )
        )
        val error = verify(tx)
        assertNotNull(error)
        assertTrue(error!!.contains("expected 2 or 3 outputs"))
    }

    @Test
    fun noInputsFails() {
        val tx = DecodedTransaction(
            ByteArray(32),
            emptyList(),
            listOf(addressOutput(vaultAddress, vaultDuffs, scriptSeed = 1), memoOutput())
        )
        val error = verify(tx)
        assertNotNull(error)
        assertTrue(error!!.contains("no inputs"))
    }
    // --- expectedVaultDuffs -------------------------------------------------
    //
    // Regression: a MAX sell aborted pre-broadcast with "VOUT0 carries 7442734
    // duffs, expected 7442725". Both guards around the build treat the quote as
    // a FLOOR (each aborts only on `<`), but the shape check demanded exact
    // equality with it, so a drain that delivered 9 duffs MORE than quoted --
    // the ordinary result of the balance moving between quote and build -- was
    // rejected as mis-shaped.

    @Test
    fun anOrdinarySellIsVerifiedAgainstTheQuote() {
        // The app chose this amount, so the quote is the expectation and the
        // engine's drain figure is irrelevant (it is 0 for a non-drain build).
        assertEquals(
            1_000_000L,
            expectedVaultDuffs(isMaxSell = false, quotedDuffs = 1_000_000L, drainDeliverableDuffs = 0L)
        )
    }

    @Test
    fun aMaxSellIsVerifiedAgainstTheEngineNotTheQuote() {
        // The exact numbers from the failing deposit.
        assertEquals(
            7_442_734L,
            expectedVaultDuffs(isMaxSell = true, quotedDuffs = 7_442_725L, drainDeliverableDuffs = 7_442_734L)
        )
    }

    @Test
    fun aMaxSellDeliveringMoreThanQuotedNowPassesTheShapeCheck() {
        // End to end over the real gate: the transaction that was rejected.
        val quoted = 7_442_725L
        val delivered = 7_442_734L
        val tx = deposit(vaultValue = delivered)

        assertNotNull(
            "the quote alone must still reject it -- that is the bug being fixed",
            verifyMayaDepositShape(tx, vaultAddress, quoted, memo)
        )
        assertNull(
            "verified against the engine's amount it is well-formed",
            verifyMayaDepositShape(
                tx,
                vaultAddress,
                expectedVaultDuffs(isMaxSell = true, quotedDuffs = quoted, drainDeliverableDuffs = delivered),
                memo
            )
        )
    }

    @Test
    fun aMaxSellStillFailsWhenTheBytesDisagreeWithTheEngine() {
        // The check stays exact, so it keeps its real job: the decoded host
        // bytes must agree with what Rust computed from the REGISTERED
        // transaction. A drain paying one duff less than the engine reported
        // is still a failure.
        val tx = deposit(vaultValue = 7_442_733L)
        assertNotNull(
            verifyMayaDepositShape(
                tx,
                vaultAddress,
                expectedVaultDuffs(isMaxSell = true, quotedDuffs = 7_442_725L, drainDeliverableDuffs = 7_442_734L),
                memo
            )
        )
    }

}
