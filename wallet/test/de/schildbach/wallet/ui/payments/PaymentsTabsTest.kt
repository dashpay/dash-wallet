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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the flag-gated shielded "Internal" tab: with the
 * flags off the payments screen must have exactly the two pre-existing
 * tabs in their original order and positions.
 */
class PaymentsTabsTest {

    @Test
    fun `flags off - exactly the two legacy tabs at their legacy positions`() {
        val tabs = PaymentsFragment.tabIdsFor(showInternalTab = false)
        assertEquals(
            listOf(PaymentsFragment.ACTIVE_TAB_RECEIVE, PaymentsFragment.ACTIVE_TAB_PAY),
            tabs
        )
        // legacy semantics: logical id == pager position
        assertEquals(PaymentsFragment.ACTIVE_TAB_RECEIVE, tabs.indexOf(PaymentsFragment.ACTIVE_TAB_RECEIVE))
        assertEquals(PaymentsFragment.ACTIVE_TAB_PAY, tabs.indexOf(PaymentsFragment.ACTIVE_TAB_PAY))
    }

    @Test
    fun `flags on - Internal tab sits between Receive and Send`() {
        assertEquals(
            listOf(
                PaymentsFragment.ACTIVE_TAB_RECEIVE,
                PaymentsFragment.ACTIVE_TAB_INTERNAL,
                PaymentsFragment.ACTIVE_TAB_PAY
            ),
            PaymentsFragment.tabIdsFor(showInternalTab = true)
        )
    }

    @Test
    fun `logical ids stay stable when the flag flips`() {
        val withInternal = PaymentsFragment.tabIdsFor(showInternalTab = true)
        val withoutInternal = PaymentsFragment.tabIdsFor(showInternalTab = false)
        // A stored recent-tab id (logical) resolves to a valid position either way
        for (id in withoutInternal) {
            assert(withInternal.contains(id))
        }
    }
}
