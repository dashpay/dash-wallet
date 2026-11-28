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
package de.schildbach.wallet.service.platform.sdk

import org.bitcoinj.core.Base58
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure evidence match behind [AssetLockKind.UNSHIELD] vs
 * [AssetLockKind.UNSHIELD_EXTERNAL]: an AssetUnlock is OURS only when one of
 * its credited payout addresses hash-matches a counterparty script recorded
 * by this wallet's own Unshield/Withdrawal shielded activity. A foreign
 * unshield (another seed's pool paying this wallet) records no such
 * activity, so it must never match — that misclassification suppressed a
 * genuine receive in the field.
 */
class UnshieldOwnershipMatchTest {

    private val address = "yjSvwyLB5X4dqQqVMPMu6UdrFpYZ3u9v5U"
    private val hash160 = Base58.decodeChecked(address).copyOfRange(1, 21)

    /** P2PKH script embedding the address's pubkey hash — the recorded counterparty form. */
    private fun p2pkhScript(hash: ByteArray): ByteArray =
        byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())

    @Test
    fun ownUnshield_payoutMatchesRecordedCounterparty() {
        assertTrue(
            unshieldPayoutMatchesOwnActivity(
                payoutAddresses = listOf(address),
                ownPayoutCounterparties = listOf(p2pkhScript(hash160))
            )
        )
    }

    @Test
    fun foreignUnshield_noActivityRows_neverMatches() {
        assertFalse(
            unshieldPayoutMatchesOwnActivity(
                payoutAddresses = listOf(address),
                ownPayoutCounterparties = emptyList()
            )
        )
    }

    @Test
    fun foreignUnshield_differentCounterparty_neverMatches() {
        val otherHash = ByteArray(20) { 0x42 }
        assertFalse(
            unshieldPayoutMatchesOwnActivity(
                payoutAddresses = listOf(address),
                ownPayoutCounterparties = listOf(p2pkhScript(otherHash))
            )
        )
    }

    @Test
    fun malformedPayoutAddress_isSkippedNotMatched() {
        assertFalse(
            unshieldPayoutMatchesOwnActivity(
                payoutAddresses = listOf("not-a-base58-address", ""),
                // A counterparty that would match anything containing junk must
                // still never match a malformed address (decode fails first).
                ownPayoutCounterparties = listOf(p2pkhScript(hash160))
            )
        )
    }

    @Test
    fun containsSubArray_boundaries() {
        val hay = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(hay.containsSubArray(byteArrayOf(1, 2)))
        assertTrue(hay.containsSubArray(byteArrayOf(4, 5)))
        assertFalse(hay.containsSubArray(byteArrayOf(5, 1)))
        assertFalse(hay.containsSubArray(byteArrayOf()))
        assertFalse(byteArrayOf(1).containsSubArray(byteArrayOf(1, 2)))
    }
}
