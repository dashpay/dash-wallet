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

    private val ids = (1..1000).map(::txId)

    @Test
    fun sameSetInDifferentOrder_sameFingerprint() {
        assertEquals(TransactionSetFingerprint.of(ids), TransactionSetFingerprint.of(ids.reversed()))
        assertEquals(TransactionSetFingerprint.of(ids), TransactionSetFingerprint.of(ids.shuffled()))
    }

    @Test
    fun differentSetWithSameCount_differentFingerprint() {
        val other = ids.dropLast(1) + txId(1001)

        assertEquals(ids.size, other.size)
        assertNotEquals(TransactionSetFingerprint.of(ids), TransactionSetFingerprint.of(other))
    }

    @Test
    fun addedTransaction_changesFingerprint() {
        assertNotEquals(TransactionSetFingerprint.of(ids), TransactionSetFingerprint.of(ids + txId(1001)))
    }

    @Test
    fun includesTheCount() {
        assertTrue(TransactionSetFingerprint.of(ids).startsWith("1000:"))
    }

    @Test
    fun emptySet_isNotTheNoWalletValue() {
        val empty = TransactionSetFingerprint.of(emptyList<Sha256Hash>())

        assertTrue(empty.startsWith("0:"))
        assertNotEquals(TransactionSetFingerprint.NONE, empty)
    }

    @Test
    fun noWallet_isNone() {
        assertEquals(TransactionSetFingerprint.NONE, TransactionSetFingerprint.of(null as Wallet?))
    }
}
