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

package de.schildbach.wallet.payments

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.params.TestNet3Params
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Host-JVM tests of the ChainLocked-only selection (AC4/AC6): real dashj
 * transactions at fixture heights, no wallet needed — the selector sees
 * exactly what `Wallet.getBalance(selector)` would hand it.
 */
class ChainLockedCoinSelectorTest {

    private val params = TestNet3Params.get()

    @Before
    fun setUp() {
        Context.propagate(Context(params))
    }

    /** A spendable output of [value] whose parent appeared at [height] (null = still pending). */
    private fun output(
        value: String,
        height: Int? = null,
        chainLockFlag: Boolean = false
    ): TransactionOutput {
        val tx = Transaction(params)
        tx.addOutput(Coin.parseCoin(value), Address.fromKey(params, ECKey()))
        if (height != null) {
            tx.confidence.appearedAtChainHeight = height // sets BUILDING
        }
        if (chainLockFlag) {
            tx.confidence.setChainLock(true)
        }
        return tx.getOutput(0)
    }

    private fun select(selector: ChainLockedCoinSelector, vararg outputs: TransactionOutput) =
        selector.select(params.maxMoney, outputs.toMutableList())

    @Test
    fun selectsOnlyOutputsAtOrBelowTheChainlockHeight() {
        val selector = ChainLockedCoinSelector(chainLockHeight = 100, bestChainHeight = 110)

        val selection = select(
            selector,
            output("1.00", height = 99), // below the chainlock — locked
            output("2.00", height = 100), // exactly at the chainlock — locked
            output("4.00", height = 101), // above the chainlock — not locked
            output("8.00", height = null) // pending — never locked
        )

        assertEquals(Coin.parseCoin("3.00"), selection.valueGathered)
        assertEquals(2, selection.gathered.size)
    }

    @Test
    fun chainLockedConfidenceFlag_countsEvenWhenThePersistedHeightLags() {
        // BlockchainState.chainlockHeight is only refreshed on sync-progress
        // updates, so dashj's live per-tx flag must win over a lagging height.
        val selector = ChainLockedCoinSelector(chainLockHeight = 100, bestChainHeight = 160)

        val selection = select(
            selector,
            output("1.00", height = 150, chainLockFlag = true),
            output("2.00", height = 150)
        )

        assertEquals(Coin.parseCoin("1.00"), selection.valueGathered)
    }

    @Test
    fun fallsBackToSixConfirmationDepth_whenNoChainlockHeightIsKnown() {
        val selector = ChainLockedCoinSelector(chainLockHeight = 0, bestChainHeight = 100)

        val selection = select(
            selector,
            output("1.00", height = 95), // 6 confirmations — counts
            output("2.00", height = 96), // 5 confirmations — too shallow
            output("4.00", height = 100) // 1 confirmation — too shallow
        )

        assertEquals(Coin.parseCoin("1.00"), selection.valueGathered)
    }

    @Test
    fun selectsNothing_whenNoHeightsAreKnownAtAll() {
        // No persisted blockchain state: the chain is not synced and the
        // transfer is gated off anyway — stay conservative.
        val selector = ChainLockedCoinSelector(chainLockHeight = 0, bestChainHeight = 0)

        val selection = select(selector, output("1.00", height = 50))

        assertEquals(Coin.ZERO, selection.valueGathered)
        assertTrue(selection.gathered.isEmpty())
    }

    @Test
    fun heightMath_isPureAndExact() {
        // chainlock known: inclusive boundary
        assertTrue(ChainLockedCoinSelector.isChainLockedHeight(100, 100, 110))
        assertFalse(ChainLockedCoinSelector.isChainLockedHeight(101, 100, 110))

        // fallback: depth ≥ 6 relative to the best height
        assertTrue(ChainLockedCoinSelector.isChainLockedHeight(95, 0, 100))
        assertFalse(ChainLockedCoinSelector.isChainLockedHeight(96, 0, 100))

        // degenerate inputs never count
        assertFalse(ChainLockedCoinSelector.isChainLockedHeight(0, 100, 110))
        assertFalse(ChainLockedCoinSelector.isChainLockedHeight(-1, 100, 110))
        assertFalse(ChainLockedCoinSelector.isChainLockedHeight(50, 0, 0))
    }
}
