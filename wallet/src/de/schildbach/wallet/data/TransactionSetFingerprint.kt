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
import java.nio.ByteBuffer

/**
 * Identifies a wallet's keys together with its set of transactions, so that a result derived from
 * classifying those transactions against those keys (e.g. "no CrowdNode sign up") can be reused only
 * for the same keys and the same transactions.
 *
 * The key identity is public, non-secret material only: each active deterministic key chain's
 * watching (account) key as public key + chain code, i.e. the content of its xpub, together with
 * the chain's derived-key state (how many keys it has derived, lookahead included, and how many
 * external and internal keys it has issued), and the public key of every imported key. The
 * derived-key state matters because a chain only recognizes outputs paying to keys it has already
 * derived: deriving more keys can make an output already in the wallet "mine" without the watching
 * key or the transaction ids changing. Public keys and key counts are readable without the spending
 * password, so an encrypted wallet fingerprints the same as when it was unencrypted. The same seed
 * with the same derived keys and the same transactions gives the same fingerprint, which is
 * correct: the classification is identical.
 */
object TransactionSetFingerprint {
    /** Returned when there is no wallet; never equal to the fingerprint of a real wallet. */
    const val NONE = ""

    /**
     * Format version, so a fingerprint cached by an older format never matches one computed now:
     * v1 covered only the transaction ids, v2 also the watching and imported keys but not the
     * chains' derived-key state, so a v2 value could outlive a lookahead change that made an
     * existing output "mine".
     */
    private const val VERSION = "v3"

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
     * "v3:<count>:<hex SHA-256>", where count is the number of transaction ids and the hash covers
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
