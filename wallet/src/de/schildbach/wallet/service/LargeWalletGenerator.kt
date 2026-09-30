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

import de.schildbach.wallet_test.BuildConfig
import org.bitcoinj.coinjoin.CoinJoin
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.WalletEx
import org.bitcoinj.wallet.WalletTransaction
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Date

/**
 * Test scaffolding that inflates a freshly created wallet with synthetic CoinJoin mixing
 * transactions so that the persisted wallet protobuf reaches a target size (60 MB by default).
 *
 * It is used to exercise the app against a wallet far larger than anything produced organically:
 * loading, saving, backing up, encrypting, syncing and rendering the transaction list.
 *
 * Every generated transaction has [INPUTS_PER_TX] inputs and [OUTPUTS_PER_TX] outputs of a single
 * standard CoinJoin denomination, with all outputs paying fresh keys of the wallet's CoinJoin key
 * chain - the same shape a real mixing session produces. Transactions spend each other, so the
 * wallet stays internally consistent and its balance is the amount created by the bootstrap
 * (received) transactions.
 *
 * DEBUG BUILDS ONLY. [isEnabled] is false in release builds regardless of [GENERATE_LARGE_WALLET].
 */
object LargeWalletGenerator {
    private val log = LoggerFactory.getLogger(LargeWalletGenerator::class.java)

    /** Master switch for the feature. Flip to false to create normal wallets again. */
    const val GENERATE_LARGE_WALLET = true

    /** Target size of the wallet protobuf on disk. */
    const val TARGET_WALLET_SIZE_BYTES = 60L * 1024 * 1024

    /**
     * Measured cost of one 8-in/8-out CoinJoin transaction in the wallet protobuf: the transaction
     * itself (~2,300 bytes) plus the eight CoinJoin keys its outputs pay to (~400 bytes). Measured
     * against dashj 22.0.5: 23,344 transactions serialize to 62,664,461 bytes, i.e. 2,684.4 bytes
     * each, so the 60 MB target needs 23,440 transactions and 187,520 CoinJoin keys.
     */
    const val BYTES_PER_TRANSACTION = 2684

    const val INPUTS_PER_TX = 8
    const val OUTPUTS_PER_TX = 8

    /** All transactions are dated within this window, ending now. */
    private const val HISTORY_DAYS = 14L
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /** Dash targets 2.5 minute blocks, so two weeks of history spans this many blocks. */
    private const val HISTORY_BLOCKS = (HISTORY_DAYS * 24 * 60 / 2.5).toInt()

    /**
     * Synthetic height the newest generated transaction is buried at. It only drives the
     * confirmation counts shown in the UI; it is deliberately well below any real chain tip so
     * that depths stay positive once the wallet starts syncing for real.
     */
    private const val SYNTHETIC_CHAIN_TIP = 1_000_000

    /**
     * Number of "received" transactions minted per denomination before mixing starts. These are the
     * only transactions with inputs the wallet does not own, so they alone determine the balance.
     */
    private const val BOOTSTRAP_TX_PER_DENOMINATION = 8

    /** The three smallest standard denominations, so the synthetic balance stays small. */
    private val DENOMINATIONS: List<Coin> = CoinJoin.getStandardDenominations().takeLast(3)

    val isEnabled: Boolean
        get() = BuildConfig.DEBUG && GENERATE_LARGE_WALLET

    /** Number of transactions needed to reach [targetBytes]. */
    fun transactionCountFor(targetBytes: Long = TARGET_WALLET_SIZE_BYTES): Int =
        (targetBytes / BYTES_PER_TRANSACTION).toInt()

    /**
     * Fills [wallet] with enough synthetic CoinJoin transactions to reach [targetBytes] when
     * serialized. Must be called on a background thread, on an unencrypted wallet whose CoinJoin
     * key chain has already been initialized, and before the wallet is encrypted: generating the
     * output keys requires an unencrypted CoinJoin key chain.
     */
    @JvmOverloads
    fun fillWallet(wallet: WalletEx, targetBytes: Long = TARGET_WALLET_SIZE_BYTES) {
        require(!wallet.isEncrypted) { "the wallet must be filled before it is encrypted" }
        val coinJoin = requireNotNull(wallet.coinJoin) { "the CoinJoin key chain is not initialized" }

        val count = transactionCountFor(targetBytes)
        val started = System.currentTimeMillis()
        log.info(
            "generating {} synthetic CoinJoin transactions (~{} MB) with {} CoinJoin keys",
            count,
            targetBytes / (1024 * 1024),
            count * OUTPUTS_PER_TX
        )

        val params = wallet.params
        val random = SecureRandom()
        val scriptSig = dummyScriptSig(random)
        val endTime = System.currentTimeMillis()
        val startTime = endTime - HISTORY_DAYS * MILLIS_PER_DAY
        val startHeight = SYNTHETIC_CHAIN_TIP - HISTORY_BLOCKS

        // One queue of spendable outputs per denomination: a mixing transaction may only combine
        // inputs of the same denomination.
        val unspentByDenomination = DENOMINATIONS.associateWith { ArrayDeque<TransactionOutput>() }
        val bootstrapCount = BOOTSTRAP_TX_PER_DENOMINATION * DENOMINATIONS.size
        val generated = ArrayList<Transaction>(count)

        for (i in 0 until count) {
            val denomination = DENOMINATIONS[i % DENOMINATIONS.size]
            val unspent = unspentByDenomination.getValue(denomination)
            val tx = Transaction(params)

            if (i >= bootstrapCount && unspent.size >= INPUTS_PER_TX) {
                // A mixing round: spend our own denominated outputs from an earlier round.
                repeat(INPUTS_PER_TX) {
                    val previous = unspent.poll()
                    val input = TransactionInput(
                        params,
                        tx,
                        scriptSig,
                        TransactionOutPoint(params, previous.index.toLong(), previous.parentTransaction!!.txId),
                        previous.value
                    )
                    tx.addInput(input)
                    previous.markAsSpent(input)
                }
            } else {
                // Bootstrap: denominated coins arriving from outside the wallet.
                repeat(INPUTS_PER_TX) {
                    tx.addInput(
                        TransactionInput(
                            params,
                            tx,
                            scriptSig,
                            TransactionOutPoint(params, random.nextInt(INPUTS_PER_TX).toLong(), randomHash(random)),
                            denomination
                        )
                    )
                }
            }

            repeat(OUTPUTS_PER_TX) {
                val key = coinJoin.freshReceiveKey()
                tx.addOutput(
                    TransactionOutput(
                        params,
                        tx,
                        denomination,
                        ScriptBuilder.createP2PKHOutputScript(key.pubKeyHash).program
                    )
                )
            }

            val progress = i.toDouble() / count
            val height = startHeight + (progress * HISTORY_BLOCKS).toInt()
            tx.updateTime = Date(startTime + (progress * HISTORY_DAYS * MILLIS_PER_DAY).toLong())
            tx.confidence.apply {
                setAppearedAtChainHeight(height)
                depthInBlocks = SYNTHETIC_CHAIN_TIP - height + 1
                source = TransactionConfidence.Source.NETWORK
            }

            generated.add(tx)
            tx.outputs.forEach { unspent.add(it) }

            if ((i + 1) % 1000 == 0) {
                log.info("generated {}/{} transactions in {} ms", i + 1, count, System.currentTimeMillis() - started)
            }
        }

        // The pool a transaction belongs to is only known once every transaction exists, since a
        // later round is what spends an earlier one's outputs.
        generated.forEach { tx ->
            val pool = if (tx.outputs.any { it.isAvailableForSpending }) {
                WalletTransaction.Pool.UNSPENT
            } else {
                WalletTransaction.Pool.SPENT
            }
            wallet.addWalletTransaction(WalletTransaction(pool, tx))
        }

        log.info(
            "generated {} transactions and {} CoinJoin keys in {} ms, balance is {}",
            count,
            count * OUTPUTS_PER_TX,
            System.currentTimeMillis() - started,
            wallet.balance.toFriendlyString()
        )
    }

    /** A P2PKH scriptSig of realistic length (signature + compressed public key). */
    private fun dummyScriptSig(random: SecureRandom): ByteArray {
        val signature = ByteArray(72).also { random.nextBytes(it) }
        val publicKey = ByteArray(33).also { random.nextBytes(it) }
        publicKey[0] = 0x02
        return ScriptBuilder().data(signature).data(publicKey).build().program
    }

    private fun randomHash(random: SecureRandom): Sha256Hash =
        Sha256Hash.wrap(ByteArray(32).also { random.nextBytes(it) })
}
