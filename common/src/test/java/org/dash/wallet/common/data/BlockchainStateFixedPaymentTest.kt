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

package org.dash.wallet.common.data

import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.BlockchainState.Impediment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet

/** [BlockchainState.blocksFixedPayment]: what the Send, gift card and Coinbase screens block on. */
class BlockchainStateFixedPaymentTest {

    private fun state(
        percentageSync: Int,
        replaying: Boolean = false,
        impediments: Set<Impediment> = emptySet()
    ) = BlockchainState(replaying).apply {
        this.percentageSync = percentageSync
        this.impediments = EnumSet.noneOf(Impediment::class.java).apply { addAll(impediments) }
    }

    @Test
    fun beforeTheFirstScanIsDone_anythingShortOfSyncedBlocks() {
        assertTrue(state(percentageSync = 60).blocksFixedPayment(firstSyncDone = false))
        assertTrue(state(percentageSync = 100, replaying = true).blocksFixedPayment(firstSyncDone = false))
        assertFalse(state(percentageSync = 100).blocksFixedPayment(firstSyncDone = false))
    }

    @Test
    fun afterTheFirstScan_aCatchUpDoesNotBlock() {
        assertFalse(state(percentageSync = 60).blocksFixedPayment(firstSyncDone = true))
    }

    @Test
    fun aSyncFailureBlocks_evenAfterTheFirstScan() {
        val failed = state(percentageSync = 100, impediments = setOf(Impediment.NETWORK))
        assertTrue(failed.blocksFixedPayment(firstSyncDone = true))
        assertTrue(failed.blocksFixedPayment(firstSyncDone = false))
    }
}
