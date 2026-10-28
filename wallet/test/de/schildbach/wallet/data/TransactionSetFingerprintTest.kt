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
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSetFingerprintTest {
    private fun txId(seed: Int): Sha256Hash = Sha256Hash.of(byteArrayOf(seed.toByte(), (seed shr 8).toByte()))

    // Stand-ins for a watching key's pubkey + chain code (33 + 32 bytes) and an imported pubkey
    private fun keyId(seed: Int, size: Int = 65): ByteArray = ByteArray(size) { (seed + it).toByte() }

    private val ids = (1..1000).map(::txId)
    private val walletA = listOf(keyId(1))
    private val walletB = listOf(keyId(2))

    private fun fingerprint(txIds: Collection<Sha256Hash>, keyIds: Collection<ByteArray> = walletA) =
        TransactionSetFingerprint.of(keyIds, txIds)

    @Test
    fun sameSetInDifferentOrder_sameFingerprint() {
        assertEquals(fingerprint(ids), fingerprint(ids.reversed()))
        assertEquals(fingerprint(ids), fingerprint(ids.shuffled()))
    }

    @Test
    fun sameKeysAndSameSet_sameFingerprint_acrossFreshByteArrays() {
        assertEquals(fingerprint(ids, listOf(keyId(1))), fingerprint(ids.shuffled(), listOf(keyId(1))))
    }

    @Test
    fun differentSetWithSameCount_differentFingerprint() {
        val other = ids.dropLast(1) + txId(1001)

        assertEquals(ids.size, other.size)
        assertNotEquals(fingerprint(ids), fingerprint(other))
    }

    @Test
    fun addedTransaction_changesFingerprint() {
        assertNotEquals(fingerprint(ids), fingerprint(ids + txId(1001)))
    }

    @Test
    fun sameSetWithDifferentKeys_differentFingerprint() {
        assertNotEquals(fingerprint(ids, walletA), fingerprint(ids, walletB))
        assertNotEquals(fingerprint(emptyList(), walletA), fingerprint(emptyList(), walletB))
    }

    @Test
    fun addedImportedKey_changesFingerprint() {
        assertNotEquals(fingerprint(ids, walletA), fingerprint(ids, walletA + keyId(3, size = 33)))
    }

    @Test
    fun noKeys_differsFromKeys() {
        assertNotEquals(fingerprint(ids, emptyList()), fingerprint(ids, walletA))
    }

    @Test
    fun keyIdentitiesInDifferentOrder_sameFingerprint() {
        val keys = listOf(keyId(1), keyId(2), keyId(3, size = 33))

        assertEquals(fingerprint(ids, keys), fingerprint(ids, keys.reversed()))
    }

    @Test
    fun includesVersionAndCount() {
        assertTrue(fingerprint(ids).startsWith("v2:1000:"))
    }

    @Test
    fun emptySet_isNotTheNoWalletValue() {
        val empty = fingerprint(emptyList())

        assertTrue(empty.startsWith("v2:0:"))
        assertNotEquals(TransactionSetFingerprint.NONE, empty)
    }

    @Test
    fun noWallet_isNone() {
        assertEquals(TransactionSetFingerprint.NONE, TransactionSetFingerprint.of(null as Wallet?))
    }
}
