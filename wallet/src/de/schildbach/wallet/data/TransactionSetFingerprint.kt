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

object TransactionSetFingerprint {
    /** Returned when there is no wallet; never equal to the fingerprint of a real transaction set. */
    const val NONE = ""

    /**
     * Fingerprints [wallet]'s transactions, dead ones included (the set
     * `Wallet.getTransactionCount(true)` counts), by their ids. See [of].
     */
    @JvmStatic
    fun of(wallet: Wallet?): String {
        if (wallet == null) {
            return NONE
        }

        // A snapshot list of the existing Transaction objects, taken under the wallet's read lock
        return of(wallet.getTransactionList(true).map(Transaction::getTxId))
    }

    /**
     * "<count>:<hex SHA-256 of the sorted ids>". Sorting makes it independent of the order the
     * ids come in, and hashing makes two different id sets (even of the same size) collide only
     * with negligible probability, so an equal fingerprint means the same set of transactions.
     */
    @JvmStatic
    fun of(txIds: Collection<Sha256Hash>): String {
        val digest = Sha256Hash.newDigest()
        txIds.sorted().forEach { digest.update(it.bytes) }
        return "${txIds.size}:${Utils.HEX.encode(digest.digest())}"
    }
}
