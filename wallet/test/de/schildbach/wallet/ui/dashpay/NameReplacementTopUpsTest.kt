/*
 * Copyright 2026 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence.ConfidenceType
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A saved top-up is found, re-committed or declared dead, but never rebuilt. */
class NameReplacementTopUpsTest {
    private val params = TestNet3Params.get()
    private val context = Context.getOrCreate(params)
    private val known = mutableMapOf<Sha256Hash, Transaction>()
    private val authentication = mockk<AuthenticationGroupExtension>()
    private val wallet = mockk<Wallet> {
        every { params } returns this@NameReplacementTopUpsTest.params
        every { context } returns this@NameReplacementTopUpsTest.context
        every { getTransaction(any()) } answers { known[firstArg()] }
        every { commitTx(any()) } just Runs
        every { getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) } returns authentication
    }
    private val topUps = NameReplacementTopUps()
    private val lockKey = ECKey()
    private val parent = Transaction(params).apply {
        addOutput(Coin.COIN, ScriptBuilder.createP2PKHOutputScript(ECKey()))
    }
    private val topUp = AssetLockTransaction(params, lockKey, Coin.parseCoin("0.03")).apply {
        addInput(parent.getOutput(0))
    }
    private val saved = topUp.unsafeBitcoinSerialize()

    @Test
    fun topUpInTheWallet_isUsedAsIs() {
        known[topUp.txId] = topUp
        topUp.getConfidence(context).confidenceType = ConfidenceType.PENDING
        every { authentication.getAssetLockTransaction(topUp) } returns topUp

        val resolution = topUps.resolve(wallet, saved)

        assertSame(topUp, (resolution as NameReplacementTopUps.Resolution.Ready).tx)
        verify(exactly = 0) { wallet.commitTx(any()) }
    }

    @Test
    fun deadTopUpInTheWallet_isDead() {
        known[topUp.txId] = topUp
        topUp.getConfidence(context).confidenceType = ConfidenceType.DEAD

        assertSame(NameReplacementTopUps.Resolution.Dead, topUps.resolve(wallet, saved))
    }

    @Test
    fun topUpLostFromTheWallet_withItsInputsUnspent_isCommittedAgain() {
        known[parent.txId] = parent

        val resolution = topUps.resolve(wallet, saved)

        assertEquals(topUp.txId, (resolution as NameReplacementTopUps.Resolution.Ready).tx.txId)
        verify(exactly = 1) { wallet.commitTx(match { it.txId == topUp.txId }) }
    }

    @Test
    fun topUpLostFromTheWallet_withAnInputSpentInABlock_isDead() {
        known[parent.txId] = parent
        spendParentIn(ConfidenceType.BUILDING)

        assertSame(NameReplacementTopUps.Resolution.Dead, topUps.resolve(wallet, saved))
        verify(exactly = 0) { wallet.commitTx(any()) }
    }

    @Test
    fun topUpLostFromTheWallet_withAnInputSpentByAnUnconfirmedTransaction_waits() {
        known[parent.txId] = parent
        spendParentIn(ConfidenceType.PENDING)

        assertTrue(topUps.resolve(wallet, saved) is NameReplacementTopUps.Resolution.Pending)
        verify(exactly = 0) { wallet.commitTx(any()) }
    }

    @Test
    fun topUpLostFromTheWallet_withAnUnknownInput_waits() {
        assertTrue(topUps.resolve(wallet, saved) is NameReplacementTopUps.Resolution.Pending)
        verify(exactly = 0) { wallet.commitTx(any()) }
    }

    @Test
    fun dispatchedTopUp_isFoundByItsKey() {
        val other = AssetLockTransaction(params, ECKey(), Coin.parseCoin("0.03"))
        every { authentication.topupFundingTransactions } returns listOf(other, topUp)

        assertSame(topUp, topUps.findByKey(wallet, lockKey.pubKeyHash))
        assertEquals(null, topUps.findByKey(wallet, ByteArray(20)))
    }

    private fun spendParentIn(confidence: ConfidenceType) {
        val spender = Transaction(params).apply {
            addInput(parent.getOutput(0))
            addOutput(Coin.CENT, ScriptBuilder.createP2PKHOutputScript(ECKey()))
        }
        parent.getOutput(0).markAsSpent(spender.getInput(0))
        spender.getConfidence(context).confidenceType = confidence
    }
}
