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
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host coverage for [verifyMayaDepositShape] — the pre-broadcast gate that
 * keeps a mis-shaped deposit (which MAYAChain would strand or mis-refund)
 * from ever reaching the network. Fixtures are hand-built with dashj, the
 * same library the verifier parses with.
 */
class MayaDepositShapeTest {

    private val params = TestNet3Params.get()
    private val vaultKey = ECKey()
    private val vaultAddress = Address.fromKey(params, vaultKey)
    private val senderKey = ECKey()
    private val memo = "=:ETH.ETH:0x1c7b17362c84287bd1184447e6dfeaf920c31bbe".toByteArray()
    private val vaultDuffs = 1_000_000L

    /**
     * A Maya-shaped deposit: vault at VOUT0, OP_RETURN memo at VOUT1,
     * optional change at VOUT2, one input carrying a P2PKH-style scriptSig
     * (`<sig> <pubkey>`) so the change-to-VIN0 check has a pubkey to hash.
     */
    private fun buildDeposit(
        vaultValue: Long = vaultDuffs,
        memoBytes: ByteArray = memo,
        changeKey: ECKey? = senderKey,
        memoValue: Long = 0L
    ): Transaction {
        val tx = Transaction(params)
        tx.addOutput(Coin.valueOf(vaultValue), vaultAddress)
        tx.addOutput(
            TransactionOutput(
                params,
                tx,
                Coin.valueOf(memoValue),
                ScriptBuilder.createOpReturnScript(memoBytes).program
            )
        )
        if (changeKey != null) {
            tx.addOutput(Coin.valueOf(50_000), Address.fromKey(params, changeKey))
        }
        // A fake signed P2PKH input: 71 zero bytes stand in for the DER
        // signature; the pubkey chunk is real so HASH160 comparisons work.
        val scriptSig = ScriptBuilder()
            .data(ByteArray(71))
            .data(senderKey.pubKey)
            .build()
        tx.addInput(
            org.bitcoinj.core.TransactionInput(
                params,
                tx,
                scriptSig.program,
                TransactionOutPoint(params, 0, Sha256Hash.ZERO_HASH)
            )
        )
        return tx
    }

    private fun verify(tx: Transaction, expectedVaultDuffs: Long = vaultDuffs, memoBytes: ByteArray = memo): String? =
        verifyMayaDepositShape(tx.bitcoinSerialize(), params, vaultAddress.toBase58(), expectedVaultDuffs, memoBytes)

    @Test
    fun wellFormedDepositPasses() {
        assertNull(verify(buildDeposit()))
    }

    @Test
    fun wellFormedDepositWithoutChangePasses() {
        assertNull(verify(buildDeposit(changeKey = null)))
    }

    @Test
    fun wrongVaultAmountFails() {
        val error = verify(buildDeposit(vaultValue = vaultDuffs + 1))
        assertNotNull(error)
        assertTrue(error!!.contains("VOUT0"))
    }

    @Test
    fun wrongVaultAddressFails() {
        val otherVault = Address.fromKey(params, ECKey())
        val tx = buildDeposit()
        val error = verifyMayaDepositShape(
            tx.bitcoinSerialize(), params, otherVault.toBase58(), vaultDuffs, memo
        )
        assertNotNull(error)
        assertTrue(error!!.contains("expected the Asgard vault"))
    }

    @Test
    fun wrongMemoFails() {
        val error = verify(buildDeposit(memoBytes = "=:ETH.ETH:0xWRONG".toByteArray()))
        assertNotNull(error)
        assertTrue(error!!.contains("memo"))
    }

    @Test
    fun valueCarryingOpReturnFails() {
        val error = verify(buildDeposit(memoValue = 546L))
        assertNotNull(error)
        assertTrue(error!!.contains("zero-value"))
    }

    @Test
    fun memoNotAtVout1Fails() {
        // vault, change, memo — memo displaced to VOUT2 (what BIP-69
        // sorting would do to a zero-value OP_RETURN is the opposite, but
        // any displacement must fail).
        val tx = Transaction(params)
        tx.addOutput(Coin.valueOf(vaultDuffs), vaultAddress)
        tx.addOutput(Coin.valueOf(50_000), Address.fromKey(params, senderKey))
        tx.addOutput(
            TransactionOutput(params, tx, Coin.ZERO, ScriptBuilder.createOpReturnScript(memo).program)
        )
        val scriptSig = ScriptBuilder().data(ByteArray(71)).data(senderKey.pubKey).build()
        tx.addInput(
            org.bitcoinj.core.TransactionInput(
                params, tx, scriptSig.program, TransactionOutPoint(params, 0, Sha256Hash.ZERO_HASH)
            )
        )
        val error = verify(tx)
        assertNotNull(error)
        assertTrue(error!!.contains("VOUT1"))
    }

    @Test
    fun changeToForeignAddressFails() {
        val error = verify(buildDeposit(changeKey = ECKey()))
        assertNotNull(error)
        assertEquals("VOUT2 change does not pay VIN0's address", error)
    }

    @Test
    fun extraOutputFails() {
        val tx = buildDeposit()
        tx.addOutput(Coin.valueOf(1_000), Address.fromKey(params, ECKey()))
        val error = verify(tx)
        assertNotNull(error)
        assertTrue(error!!.contains("expected 2 or 3 outputs"))
    }

    @Test
    fun garbageBytesFail() {
        val error = verifyMayaDepositShape(
            ByteArray(32) { 0x42 }, params, vaultAddress.toBase58(), vaultDuffs, memo
        )
        assertNotNull(error)
        assertTrue(error!!.contains("unparseable"))
    }
}
