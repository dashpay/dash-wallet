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

import io.mockk.every
import io.mockk.mockk
import org.bitcoinj.core.TransactionConfidence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Phase D never-broadcast predicate: the
 * once-per-start legacy top-up scan re-announces ONLY our own pending
 * transactions that no peer has ever reported.
 */
class TopUpReannounceTest {

    private fun confidence(
        type: TransactionConfidence.ConfidenceType,
        source: TransactionConfidence.Source,
        broadcastPeers: Int
    ): TransactionConfidence = mockk {
        every { confidenceType } returns type
        every { this@mockk.source } returns source
        every { numBroadcastPeers() } returns broadcastPeers
    }

    @Test
    fun neverBroadcastSelfTx_isReannounced() {
        assertTrue(
            shouldReannounceLegacyTopUp(
                confidence(
                    TransactionConfidence.ConfidenceType.PENDING,
                    TransactionConfidence.Source.SELF,
                    broadcastPeers = 0
                )
            )
        )
    }

    @Test
    fun seenByAnyPeer_isLeftToDashjRebroadcast() {
        assertFalse(
            shouldReannounceLegacyTopUp(
                confidence(
                    TransactionConfidence.ConfidenceType.PENDING,
                    TransactionConfidence.Source.SELF,
                    broadcastPeers = 1
                )
            )
        )
    }

    @Test
    fun confirmedDeadOrNetworkSourced_isNeverTouched() {
        assertFalse(
            shouldReannounceLegacyTopUp(
                confidence(
                    TransactionConfidence.ConfidenceType.BUILDING,
                    TransactionConfidence.Source.SELF,
                    broadcastPeers = 0
                )
            )
        )
        assertFalse(
            shouldReannounceLegacyTopUp(
                confidence(
                    TransactionConfidence.ConfidenceType.DEAD,
                    TransactionConfidence.Source.SELF,
                    broadcastPeers = 0
                )
            )
        )
        assertFalse(
            shouldReannounceLegacyTopUp(
                confidence(
                    TransactionConfidence.ConfidenceType.PENDING,
                    TransactionConfidence.Source.NETWORK,
                    broadcastPeers = 0
                )
            )
        )
    }

    @Test
    fun missingConfidence_isNeverTouched() {
        assertFalse(shouldReannounceLegacyTopUp(null))
    }
}
