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
package de.schildbach.wallet.transactions.coinjoin

import io.mockk.mockk
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.transactions.TxInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MO-995: QA saw an ordinary 0.0002 DASH send labelled "CoinJoin Mixing" on
 * BOTH the sending and the receiving wallet, while the coinjoin balance was 0
 * for the entire session — no mixing had occurred. 0.0002 DASH is not a
 * CoinJoin denomination, so amount-matching does not explain it.
 *
 * The label comes from [CoinJoinMixingTxSet.tryInclude], which groups a
 * transaction as CoinJoin unless dashj's shape heuristic
 * `CoinJoinTransactionType.fromTx` returns `None` or `Send`. That heuristic is
 * evaluated against the DASHJ wallet, which post-cutover is held with its
 * balance frozen at the cutover snapshot — while the transaction itself comes
 * from the SDK.
 *
 * WHAT THIS FILE CAN AND CANNOT COVER: the misclassification itself lives
 * inside dashj's `fromTx` and depends on real wallet/UTXO context, which cannot
 * be assembled here (there is no `WalletEx` fixture, and `fromTx` reads input
 * values and script ownership). That half is answered by the diagnostic log
 * line added alongside these tests, from the next field report.
 *
 * What IS covered here is the payload contract that must hold regardless of
 * how `fromTx` behaves — and which used to be an outright crash.
 */
class CoinJoinMixingTxSetTest {

    // groupDate is derived from updateTimeMillis, not a constructor arg.
    private fun txInfo(txId: String, raw: Any?): TxInfo = TxInfo(
        txId = txId,
        updateTimeMillis = 1_756_400_000_000L,
        raw = raw
    )

    /**
     * REGRESSION: `tryInclude` did `tx.raw as Transaction` unconditionally, but
     * `TxInfo.raw` is `Any?`. Post-cutover a TxInfo can be built from the SDK
     * rather than from dashj, so a non-dashj payload — or null — threw a
     * ClassCastException straight out of the transaction-list grouping.
     *
     * A payload the CoinJoin heuristic cannot even read is, by definition, not
     * a transaction we can call CoinJoin: exclude it and keep the list alive.
     */
    @Test
    fun tryInclude_excludesANonDashjPayload_insteadOfThrowing() {
        val set = CoinJoinMixingTxSet(mockk<WalletEx>(relaxed = true))

        assertFalse(
            "a non-dashj payload must not be grouped as CoinJoin",
            set.tryInclude(txInfo("aa".repeat(32), raw = "not a dashj Transaction"))
        )
        assertTrue("and nothing must be added to the group", set.transactions.isEmpty())
    }

    @Test
    fun tryInclude_excludesANullPayload_insteadOfThrowing() {
        val set = CoinJoinMixingTxSet(mockk<WalletEx>(relaxed = true))

        assertFalse(set.tryInclude(txInfo("bb".repeat(32), raw = null)))
        assertTrue(set.transactions.isEmpty())
    }

    /**
     * The already-present short circuit must keep working: an update for a txId
     * already in the group is accepted without consulting the classifier at all
     * (the mock WalletEx would otherwise be asked, and on a real frozen wallet
     * the answer could differ from the one that put it here).
     */
    @Test
    fun tryInclude_updatesAnAlreadyGroupedTxWithoutReclassifying() {
        val set = CoinJoinMixingTxSet(mockk<WalletEx>(relaxed = true))
        val txId = "cc".repeat(32)
        // Seed it directly: the classifier cannot be driven from a unit test.
        set.transactions[txId] = txInfo(txId, raw = null)

        val updated = txInfo(txId, raw = "replacement payload")
        assertTrue("an already-grouped txId must be accepted", set.tryInclude(updated))
        // NB: TxInfo.equals compares txId only, so asserting equality here would
        // pass trivially. Check the payload actually swapped.
        assertEquals("replacement payload", set.transactions[txId]?.raw)
        assertEquals(1, set.transactions.size)
    }
}
