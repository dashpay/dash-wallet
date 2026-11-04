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

package de.schildbach.wallet.ui.payments

import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.advancedModeFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PaymentsRouteTest {
    @Test
    fun `advanced mode off opens the basic screen with no extra transition`() {
        val route = paymentsRoute(advancedMode = false)
        assertEquals(R.id.paymentsFragment, route.destinationId)
        assertNull(route.enterAnim)
        assertNull(route.popExitAnim)
    }

    @Test
    fun `advanced mode on opens the advanced screen with its transition`() {
        val route = paymentsRoute(advancedMode = true)
        assertEquals(R.id.advancedPaymentsFragment, route.destinationId)
        assertEquals(R.anim.slide_in_bottom, route.enterAnim)
        assertEquals(R.anim.slide_out_bottom, route.popExitAnim)
    }

    @Test
    fun `an unreadable setting routes to the basic screen`() = runBlocking {
        // What MainViewModel.advancedMode holds while the read fails.
        val advanced = advancedModeFlow(
            flow { throw IOException("datastore unreadable") },
            retryDelayMs = { 0L }
        ).first()
        assertEquals(R.id.paymentsFragment, paymentsRoute(advanced).destinationId)
    }

    @Test
    fun `an unset setting routes to the basic screen`() = runBlocking {
        val advanced = advancedModeFlow(flow { emit(null) }).first()
        assertEquals(R.id.paymentsFragment, paymentsRoute(advanced).destinationId)
    }

    @Test
    fun `both payments screens highlight the payments item`() {
        assertTrue(isPaymentsDestination(R.id.paymentsFragment))
        assertTrue(isPaymentsDestination(R.id.advancedPaymentsFragment))
        assertFalse(isPaymentsDestination(R.id.walletFragment))
    }
}
