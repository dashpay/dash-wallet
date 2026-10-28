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
import org.bitcoinj.wallet.Wallet
import java.nio.ByteBuffer

/**
 * Identifies a wallet's keys together with its set of transactions, so that a result derived from
 * classifying those transactions against those keys (e.g. "no CrowdNode sign up") can be reused only
 * for the same keys and the same transactions.
 *
 * The key identity is public, non-secret material only: each active deterministic key chain's
 * watching (account) key as public key + chain code, i.e. the content of its xpub, and the public
 * key of every imported key. Public keys are readable without the spending password, so an
 * encrypted wallet fingerprints the same as when it was unencrypted. The same seed with the same
 * transactions gives the same fingerprint, which is correct: the classification is identical.
 */
object TransactionSetFingerprint {
    /** Returned when there is no wallet; never equal to the fingerprint of a real wallet. */
    const val NONE = ""

    /**
     * Format version, so a fingerprint cached by an older format (which covered only the transaction
     * ids) never matches one computed now.
     */
    private const val VERSION = "v2"

    /**
     * Fingerprints [wallet]'s key identity (see [keyIds]) and its transactions, dead ones included
     * (the set `Wallet.getTransactionCount(true)` counts), by their ids. See [of].
     */
    @JvmStatic
    fun of(wallet: Wallet?): String {
        if (wallet == null) {
            return NONE
        }

        // A snapshot list of the existing Transaction objects, taken under the wallet's read lock
        return of(keyIds(wallet), wallet.getTransactionList(true).map(Transaction::getTxId))
    }

    /**
     * The public identity of [wallet]'s keys: for each active deterministic key chain, the watching
     * key's public key followed by its chain code; then each imported key's public key. Never any
     * private key material.
     */
    @JvmStatic
    fun keyIds(wallet: Wallet): List<ByteArray> {
        val chainIds = wallet.activeKeyChains.map { chain ->
            val watchingKey = chain.watchingKey
            watchingKey.pubKey + watchingKey.chainCode
        }
        return chainIds + wallet.importedKeys.map { it.pubKey }
    }

    /**
     * "v2:<count>:<hex SHA-256>", where count is the number of transaction ids and the hash covers
     * the key identities and the transaction ids. Both are sorted first, so the result doesn't depend
     * on the order either comes in: the key identities are treated as a set, like the transactions.
     * Each key identity is hashed to a fixed length and their number is hashed first, so key and
     * transaction data can't be shifted into one another. Two different inputs collide only with
     * negligible probability, so an equal fingerprint means the same keys and the same transactions.
     */
    @JvmStatic
    fun of(keyIds: Collection<ByteArray>, txIds: Collection<Sha256Hash>): String {
        val digest = Sha256Hash.newDigest()
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(keyIds.size).array())
        keyIds.map(Sha256Hash::of).sorted().forEach { digest.update(it.bytes) }
        txIds.sorted().forEach { digest.update(it.bytes) }
        return "$VERSION:${txIds.size}:${Utils.HEX.encode(digest.digest())}"
    }
}
