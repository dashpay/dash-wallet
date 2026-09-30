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

package de.schildbach.wallet.service

import org.bitcoinj.coinjoin.CoinJoin
import org.bitcoinj.core.Context
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.WalletEx
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.math.abs

/**
 * Verifies [LargeWalletGenerator] against a smaller target than the 60 MB it is configured for,
 * so that the test stays inside the unit test heap. The per-transaction cost is constant, so a
 * 4 MB run validates the [LargeWalletGenerator.BYTES_PER_TRANSACTION] estimate used for 60 MB.
 */
class LargeWalletGeneratorTest {
    companion object {
        private val PARAMS = TestNet3Params.get()
        private const val TARGET_BYTES = 4L * 1024 * 1024

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            Context.propagate(Context(PARAMS))
        }
    }

    private fun newWallet(): WalletEx {
        val seed = DeterministicSeed(SecureRandom(), DeterministicSeed.DEFAULT_SEED_ENTROPY_BITS, "")
        val wallet = WalletEx.fromSeed(PARAMS, seed, Script.ScriptType.P2PKH)
        wallet.initializeCoinJoin(0)
        return wallet
    }

    @Test
    fun fillWallet_reachesTargetSizeAndStaysConsistent() {
        val wallet = newWallet()
        LargeWalletGenerator.fillWallet(wallet, TARGET_BYTES)

        val expectedCount = LargeWalletGenerator.transactionCountFor(TARGET_BYTES)
        assertEquals(expectedCount, wallet.getTransactions(true).size)
        assertTrue("the wallet should remain consistent", wallet.isConsistent)

        val bytes = ByteArrayOutputStream()
        WalletProtobufSerializer().writeWallet(wallet, bytes)
        val error = abs(bytes.size() - TARGET_BYTES).toDouble() / TARGET_BYTES
        assertTrue(
            "serialized ${bytes.size()} bytes, expected within 5% of $TARGET_BYTES",
            error < 0.05
        )
    }

    @Test
    fun fillWallet_createsCoinJoinShapedTransactions() {
        val wallet = newWallet()
        LargeWalletGenerator.fillWallet(wallet, TARGET_BYTES)

        val coinJoin = wallet.coinJoin
        val denominations = CoinJoin.getStandardDenominations()

        wallet.getTransactions(true).forEach { tx ->
            assertEquals(LargeWalletGenerator.INPUTS_PER_TX, tx.inputs.size)
            assertEquals(LargeWalletGenerator.OUTPUTS_PER_TX, tx.outputs.size)

            val denomination = tx.outputs.first().value
            assertTrue("$denomination is not a standard denomination", denominations.contains(denomination))

            tx.outputs.forEach { output ->
                assertEquals(denomination, output.value)
                val script = output.scriptPubKey
                assertTrue("outputs should be P2PKH", ScriptPattern.isP2PKH(script))
                val hash = ScriptPattern.extractHashFromP2PKH(script)
                assertTrue(
                    "every output should pay a CoinJoin key of this wallet",
                    coinJoin.findKeyFromPubKeyHash(hash, Script.ScriptType.P2PKH) != null
                )
            }
        }
    }

    @Test
    fun fillWallet_datesTransactionsWithinTheLastTwoWeeks() {
        val wallet = newWallet()
        val twoWeeksAgo = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
        LargeWalletGenerator.fillWallet(wallet, TARGET_BYTES)
        val now = System.currentTimeMillis()
        wallet.getTransactions(true).forEach { tx ->
            val time = tx.updateTime.time
            assertTrue("$time is older than two weeks", time >= twoWeeksAgo)
            assertTrue("$time is in the future", time <= now)
        }
    }
}
