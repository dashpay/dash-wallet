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
package de.schildbach.wallet.service.platform

import de.schildbach.wallet.util.toTxId
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Sha256Hash
import org.dashj.platform.wallet.TxMetadataItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The txid selection for the batch prefetch around a TxMetadata fetch's import loop.
 */
class ImportableTxIdsTest {

    private fun wireTxId(n: Int) = ByteArray(32) { if (it == 0) n.toByte() else 7 }
    private fun item(txId: ByteArray, memo: String? = "memo") = TxMetadataItem(txId, memo = memo)

    @Test
    fun `should skip malformed txids instead of throwing`() = runTest {
        val good = wireTxId(1)
        val items = mapOf(
            "doc" to listOf(item(ByteArray(0)), item(ByteArray(31)), item(ByteArray(33)), item(good))
        )

        val txIds = importableTxIds(items) { false }

        assertEquals(listOf(Sha256Hash.wrapReversed(good).toTxId()), txIds)
    }

    @Test
    fun `should skip documents already held`() = runTest {
        val held = wireTxId(1)
        val fresh = wireTxId(2)
        val items = mapOf("held" to listOf(item(held)), "new" to listOf(item(fresh)))

        val txIds = importableTxIds(items) { doc -> doc == "held" }

        assertEquals(listOf(Sha256Hash.wrapReversed(fresh).toTxId()), txIds)
    }

    @Test
    fun `should skip items with no metadata`() = runTest {
        val items = mapOf("doc" to listOf(item(wireTxId(1), memo = null), item(wireTxId(2))))

        val txIds = importableTxIds(items) { false }

        assertEquals(listOf(Sha256Hash.wrapReversed(wireTxId(2)).toTxId()), txIds)
    }
}
