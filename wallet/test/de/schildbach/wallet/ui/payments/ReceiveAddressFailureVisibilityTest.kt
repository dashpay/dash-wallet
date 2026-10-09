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

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visibility rule for a failed receive-address read
 * ([PaymentsReceiveFragment.shouldSurfaceAddressFailure]).
 *
 * Receive is a ViewPager2 page of [PaymentsFragment], and the address request
 * it starts is not cancelled when the user switches to Send or Internal — the
 * page is only capped at [Lifecycle.State.STARTED], and the read itself runs
 * on the ViewModel's scope. So a failure can complete while the user is
 * somewhere else entirely, and the one thing it must not do is interrupt that
 * other tab. The threshold is pinned here: STARTED is an offscreen page and
 * stays silent, only RESUMED is the page the user is actually looking at —
 * which is also when the request is retried
 * ([de.schildbach.wallet.ui.payments.PaymentsViewModel.requestReceiveAddress],
 * covered by [PaymentsReceiveAddressRequestTest]).
 *
 * What this cannot see: that the handler no longer navigates. That is a
 * Robolectric/instrumented concern (every Robolectric test in this module
 * currently fails on a missing conscrypt_jni native library), so it is held by
 * the absence of any NavController call in the `catch` block, not by a test.
 */
class ReceiveAddressFailureVisibilityTest {

    @Test
    fun `offscreen pager page stays silent`() {
        // ViewPager2 caps a non-primary page here: the user is on another tab.
        assertFalse(
            "a Receive failure must not interrupt the tab the user is on",
            PaymentsReceiveFragment.shouldSurfaceAddressFailure(Lifecycle.State.STARTED)
        )
    }

    @Test
    fun `selected pager page shows the error`() {
        assertTrue(
            "the user is looking at Receive, so the failure is theirs to see",
            PaymentsReceiveFragment.shouldSurfaceAddressFailure(Lifecycle.State.RESUMED)
        )
    }

    @Test
    fun `nothing below started surfaces either`() {
        // Host backgrounded, or the view torn down before the read completed.
        for (state in listOf(
            Lifecycle.State.DESTROYED,
            Lifecycle.State.INITIALIZED,
            Lifecycle.State.CREATED
        )) {
            assertFalse(
                "no user-visible error from state $state",
                PaymentsReceiveFragment.shouldSurfaceAddressFailure(state)
            )
        }
    }

    @Test
    fun `resumed is the only state that surfaces`() {
        val surfacing = Lifecycle.State.values().filter {
            PaymentsReceiveFragment.shouldSurfaceAddressFailure(it)
        }
        assertTrue(
            "expected only RESUMED to surface, got $surfacing",
            surfacing == listOf(Lifecycle.State.RESUMED)
        )
    }
}
