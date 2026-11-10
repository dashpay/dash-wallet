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

import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ACTIVE_TAB_INTERNAL
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ACTIVE_TAB_PAY
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ACTIVE_TAB_RECEIVE
import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedPaymentsTabsTest {
    private val twoTabs = listOf(ACTIVE_TAB_RECEIVE, ACTIVE_TAB_PAY)
    private val threeTabs = listOf(ACTIVE_TAB_RECEIVE, ACTIVE_TAB_INTERNAL, ACTIVE_TAB_PAY)

    @Test
    fun `Internal is always shown where Platform is supported, whatever the shielded flag`() {
        assertEquals(threeTabs, advancedPaymentsTabIds(supportsPlatform = true))
        assertEquals(twoTabs, advancedPaymentsTabIds(supportsPlatform = false))
    }

    @Test
    fun `Basic mode never shows Internal`() {
        // PaymentsFragment shows Receive | Send whatever the flags; Internal is Advanced only.
        assertEquals(twoTabs, PaymentsFragment.BASIC_TAB_IDS)
        assertEquals(threeTabs, PaymentsFragment.tabIdsFor(showInternalTab = true))
    }

    @Test
    fun `a stored Internal tab opens Receive in Basic mode`() {
        assertEquals(0, AdvancedPaymentsFragment.positionOf(PaymentsFragment.BASIC_TAB_IDS, ACTIVE_TAB_INTERNAL))
        assertEquals(ACTIVE_TAB_RECEIVE, PaymentsFragment.BASIC_TAB_IDS[0])
        assertEquals(1, AdvancedPaymentsFragment.positionOf(PaymentsFragment.BASIC_TAB_IDS, ACTIVE_TAB_PAY))
    }

    @Test
    fun `a tab that is not shown falls back to the first page`() {
        assertEquals(1, AdvancedPaymentsFragment.positionOf(threeTabs, ACTIVE_TAB_INTERNAL))
        assertEquals(2, AdvancedPaymentsFragment.positionOf(threeTabs, ACTIVE_TAB_PAY))
        assertEquals(1, AdvancedPaymentsFragment.positionOf(twoTabs, ACTIVE_TAB_PAY))
        assertEquals(0, AdvancedPaymentsFragment.positionOf(twoTabs, ACTIVE_TAB_INTERNAL))
    }
}
