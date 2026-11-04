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
    private val tabs = listOf(
        PaymentsFragment.ACTIVE_TAB_RECEIVE,
        PaymentsFragment.ACTIVE_TAB_INTERNAL,
        PaymentsFragment.ACTIVE_TAB_PAY
    )

    @Test
    fun `advanced mode off opens the basic screen with no extra transition`() {
        val route = paymentsRoute(advancedMode = false)
        assertEquals(R.id.paymentsFragment, route.destinationId)
        assertNull(route.activeTab)
        assertNull(route.enterAnim)
        assertNull(route.popExitAnim)
    }

    @Test
    fun `advanced mode on opens the advanced screen with its transition`() {
        val route = paymentsRoute(advancedMode = true)
        assertEquals(R.id.advancedPaymentsFragment, route.destinationId)
        assertNull(route.activeTab)
        assertEquals(R.anim.slide_in_bottom, route.enterAnim)
        assertEquals(R.anim.slide_out_bottom, route.popExitAnim)
    }

    @Test
    fun `every tab is passed through unchanged in both modes`() {
        for (advanced in listOf(false, true)) {
            for (tab in tabs) {
                val route = paymentsRoute(advanced, tab)
                assertEquals(tab, route.activeTab)
                assertEquals(
                    if (advanced) R.id.advancedPaymentsFragment else R.id.paymentsFragment,
                    route.destinationId
                )
            }
        }
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
