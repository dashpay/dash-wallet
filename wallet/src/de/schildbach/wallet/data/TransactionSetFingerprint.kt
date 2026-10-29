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

package de.schildbach.wallet.data

import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.Utils
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletTransaction
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Identifies a wallet's keys together with its transactions and their wallet-relative graph state,
 * so that a result derived from classifying those transactions against those keys (e.g. "no
 * CrowdNode sign up") can be reused only while everything the classification reads is the same.
 *
 * The key identity is public, non-secret material only: each active deterministic key chain's
 * watching (account) key as public key + chain code, i.e. the content of its xpub, together with
 * the chain's derived-key state (how many keys it has derived, lookahead included, and how many
 * external and internal keys it has issued), and the public key of every imported key. The
 * derived-key state matters because a chain only recognizes outputs paying to keys it has already
 * derived: deriving more keys can make an output already in the wallet "mine" without the watching
 * key or the transaction ids changing. Public keys and key counts are readable without the spending
 * password, so an encrypted wallet fingerprints the same as when it was unencrypted.
 *
 * Each transaction is identified by its id, which fixes its own content (inputs' outpoints, output
 * values and scripts, so addresses and OP_RETURNs), and by the wallet-relative state the matchers
 * read that its id doesn't fix (see [hashTransaction]): its wallet pool, whether each input is
 * connected to the output it spends (the connected address, `isEntirelySelf` and the value sent from
 * the wallet come from it) and which transaction, if any, spends each output (`spentBy`, which the
 * CrowdNode top-up discovery follows). Whether an output is "mine" is decided by its script and the
 * keys, both already covered, so it isn't hashed again. Confidence (locked / pending), the update
 * time and the fee aren't covered: the CrowdNode sign-up matchers don't decide on them.
 *
 * The same seed with the same derived keys, the same transactions and the same graph state gives
 * the same fingerprint, which is correct: the classification is identical.
 */
object TransactionSetFingerprint {
    /** Returned when there is no wallet; never equal to the fingerprint of a real wallet. */
    const val NONE = ""

    /**
     * Format version, so a fingerprint cached by an older format never matches one computed now:
     * v1 covered only the transaction ids, v2 also the watching and imported keys but not the
     * chains' derived-key state, so a v2 value could outlive a lookahead change that made an
     * existing output "mine"; v3 added the derived-key state but not the transactions' graph state
     * (pool, input connections, spending transactions), so a v3 value could outlive a reconnection
     * or a spend that changes what the matchers read.
     */
    private const val VERSION = "v4"

    /** Hashed in place of a spending transaction for an output nobody (known) spends. */
    private const val UNSPENT: Byte = 0
    private const val SPENT: Byte = 1

    /**
     * Fingerprints [wallet]'s key identity (see [keyIds]) and its transactions, dead ones included
     * (the set `Wallet.getTransactionCount(true)` counts), with their graph state. See [of].
     *
     * `Wallet.getWalletTransactions()` snapshots each transaction with its pool under the wallet's
     * read lock, but the inputs' connections and the outputs' spenders are read afterwards, without
     * it (dashj exposes no public way to hold the wallet lock). A change racing this read can only
     * produce a fingerprint that is stale or mixed; the caller guards against that by computing the
     * fingerprint before and after its scan and trusting the result only when both are equal.
     */
    @JvmStatic
    fun of(wallet: Wallet?): String {
        if (wallet == null) {
            return NONE
        }

        return of(keyIds(wallet), wallet.walletTransactions)
    }

    /**
     * The public identity of [wallet]'s keys: for each active deterministic key chain, the watching
     * key's public key followed by its chain code and its derived-key state (see [derivedKeyState]);
     * then each imported key's public key. Never any private key material.
     */
    @JvmStatic
    fun keyIds(wallet: Wallet): List<ByteArray> {
        val chainIds = wallet.activeKeyChains.map { chain ->
            val watchingKey = chain.watchingKey
            watchingKey.pubKey + watchingKey.chainCode + derivedKeyState(chain)
        }
        return chainIds + wallet.importedKeys.map { it.pubKey }
    }

    /**
     * [chain]'s derived-key state as three fixed-width big-endian ints: the number of keys it holds
     * (lookahead keys included, so this is what decides which outputs are "mine"), then the number
     * of issued external and internal keys. Each getter takes the chain's lock; the three aren't read
     * atomically, but a chain that changes in between changes the fingerprint on the next read
     * anyway, and the cache is only written when the fingerprint is the same before and after a
     * scan. The lookahead size and threshold are configuration, not state: what they cause is
     * already counted by the number of keys.
     */
    private fun derivedKeyState(chain: DeterministicKeyChain): ByteArray =
        ByteBuffer.allocate(3 * Int.SIZE_BYTES)
            .putInt(chain.numKeys())
            .putInt(chain.issuedExternalKeys)
            .putInt(chain.issuedInternalKeys)
            .array()

    /**
     * "v4:<count>:<hex SHA-256>", where count is the number of transactions and the hash covers the
     * key identities and, per transaction, its id and graph state (see [hashTransaction]). Key
     * identities and transactions are sorted first (the transactions by id, which is unique within a
     * wallet), so the result doesn't depend on the order either comes in. Each key identity is hashed
     * to a fixed length and their number is hashed first, and each transaction is hashed in a
     * fixed-width encoding led by its id and counts, so no part can be shifted into another. Two
     * different inputs collide only with negligible probability, so an equal fingerprint means the
     * same keys, the same transactions and the same graph state.
     *
     * This is a single streaming pass over the transactions: nothing is retained per transaction
     * beyond the sorted list of the given objects.
     */
    @JvmStatic
    fun of(keyIds: Collection<ByteArray>, transactions: Iterable<WalletTransaction>): String {
        val sorted = transactions.sortedBy { it.transaction.txId }
        val digest = Sha256Hash.newDigest()
        digest.update(intBytes(keyIds.size))
        keyIds.map(Sha256Hash::of).sorted().forEach { digest.update(it.bytes) }
        val hashedSpenders = HashSet<Sha256Hash>()
        sorted.forEach { hashTransaction(digest, it.pool, it.transaction, hashedSpenders) }
        return "$VERSION:${sorted.size}:${Utils.HEX.encode(digest.digest())}"
    }

    /**
     * Hashes [tx]: its id; its [pool] (the value sent from the wallet only counts inputs spending a
     * transaction in the unspent, spent or pending pool, so a parent moving to dead changes it); its
     * input connections (see [hashInputConnections]); then its number of outputs and, per output,
     * [UNSPENT], or [SPENT] followed by the spending transaction's id and that transaction's input
     * connections. The spender is read as `spentBy.parentTransaction`, like the matcher; its own
     * connections are hashed here too because the top-up discovery reads its connected addresses and
     * it need not be one of the wallet's transactions. They are hashed only the first time a spender
     * is met ([hashedSpenders]), so a transaction spending many outputs costs one pass over its
     * inputs, not one per output; the traversal order is fixed, so the encoding still is too. The
     * spending input itself needs no index: it is the spender's input whose outpoint is this output,
     * which the spender's id fixes.
     */
    private fun hashTransaction(
        digest: MessageDigest,
        pool: WalletTransaction.Pool,
        tx: Transaction,
        hashedSpenders: MutableSet<Sha256Hash>
    ) {
        digest.update(tx.txId.bytes)
        digest.update(intBytes(pool.ordinal))
        hashInputConnections(digest, tx)
        val outputs = tx.outputs
        digest.update(intBytes(outputs.size))
        for (output in outputs) {
            val spender = output.spentBy?.parentTransaction

            if (spender == null) {
                digest.update(UNSPENT)
            } else {
                digest.update(SPENT)
                digest.update(spender.txId.bytes)
                if (hashedSpenders.add(spender.txId)) {
                    hashInputConnections(digest, spender)
                }
            }
        }
    }

    /**
     * [tx]'s number of inputs, then one byte per input: whether it is connected to the output it
     * spends. Which output that is follows from the outpoint, part of [tx]'s id, so whether there is
     * a connection is all that can vary; the connected address and whether it is "mine" follow from
     * that output's script and the keys.
     */
    private fun hashInputConnections(digest: MessageDigest, tx: Transaction) {
        val inputs = tx.inputs
        digest.update(intBytes(inputs.size))
        for (input in inputs) {
            digest.update(if (input.outpoint.connectedOutput != null) 1.toByte() else 0.toByte())
        }
    }

    private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()
}
