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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 5d: the pure routing decision for dashj-typed sends under the
 * cutover state ([cutoverSendRoute]). The load-bearing invariant: once the
 * cutover is committed there is NO dashj route — a held peergroup would
 * queue-not-send, so anything the SDK can't take must FAIL CLOSED.
 */
class CutoverSendRouteTest {

    @Test
    fun preCutover_everySendIsTheUnchangedDashjPath() {
        assertEquals(CutoverSendRoute.DASHJ, cutoverSendRoute(false, hasCustomSelector = false, emptyWallet = false))
        assertEquals(CutoverSendRoute.DASHJ, cutoverSendRoute(false, hasCustomSelector = true, emptyWallet = false))
        assertEquals(CutoverSendRoute.DASHJ, cutoverSendRoute(false, hasCustomSelector = false, emptyWallet = true))
        assertEquals(CutoverSendRoute.DASHJ, cutoverSendRoute(false, hasCustomSelector = true, emptyWallet = true))
    }

    @Test
    fun committed_simplePayToAddressRoutesThroughTheSdkBridge() {
        assertEquals(
            CutoverSendRoute.SDK_BRIDGED,
            cutoverSendRoute(true, hasCustomSelector = false, emptyWallet = false)
        )
    }

    @Test
    fun committed_customSelectorOrSendAll_failsClosed_neverDashj() {
        assertEquals(CutoverSendRoute.FAIL_CLOSED, cutoverSendRoute(true, hasCustomSelector = true, emptyWallet = false))
        assertEquals(CutoverSendRoute.FAIL_CLOSED, cutoverSendRoute(true, hasCustomSelector = false, emptyWallet = true))
        assertEquals(CutoverSendRoute.FAIL_CLOSED, cutoverSendRoute(true, hasCustomSelector = true, emptyWallet = true))
    }
}
