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

package de.schildbach.wallet.service

import org.bitcoinj.core.Coin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the received-coins notification gate
 * ([shouldAnnounceCoinsReceived]) — "only receives are announced".
 *
 * Post-cutover the SDK authors every send and
 * [de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory] commits it into the
 * dashj wallet-of-record, where dashj cannot attribute the SDK-owned inputs: it values the send by
 * its `+change` output alone and delivers it to `onCoinsReceived`. MO-995: a gift-card purchase was
 * announced as "Received -0.0x" because the gate and the announced amount each resolved the
 * SDK-corrected net separately — the gate's lookup ran on the main thread, where Room throws and
 * the lookup fails soft to dashj's positive misread.
 */
class CoinsReceivedNotificationGateTest {

    @Test
    fun genuineReceiveIsAnnounced() {
        assertTrue(shouldAnnounceCoinsReceived(walletAuthored = false, correctedNet = Coin.valueOf(1_000_000)))
    }

    /** The gift-card purchase from MO-995: dashj's +change misread must never announce. */
    @Test
    fun selfAuthoredSendIsNotAnnouncedEvenWhenDashjMisreadsItPositive() {
        assertFalse(shouldAnnounceCoinsReceived(walletAuthored = true, correctedNet = Coin.valueOf(383_000_000)))
    }

    @Test
    fun selfAuthoredSendIsNotAnnouncedWithItsCorrectedNegativeNet() {
        assertFalse(shouldAnnounceCoinsReceived(walletAuthored = true, correctedNet = Coin.valueOf(-1_000_146)))
    }

    /** No caller may announce a negative "Received" — the value QA actually saw in the push. */
    @Test
    fun negativeNetIsNeverAnnounced() {
        assertFalse(shouldAnnounceCoinsReceived(walletAuthored = false, correctedNet = Coin.valueOf(-1_000_146)))
    }

    /** A zero-net delivery (a fee-only self-spend dashj values at 0) is not a receive. */
    @Test
    fun zeroNetIsNeverAnnounced() {
        assertFalse(shouldAnnounceCoinsReceived(walletAuthored = false, correctedNet = Coin.ZERO))
    }
}
