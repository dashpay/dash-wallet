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
package de.schildbach.wallet.ui.send

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * thepastaclaw on #1714. Two taps on a refused preview each start an authentication. The first
 * answer rebuilds the preview; the second must not submit the rebuilt payment, whose fee no tap
 * has confirmed.
 */
class PayTapActionTest {
    private fun action(tappedOnPayable: Boolean, canSend: Boolean, canRetry: Boolean, building: Boolean = false) =
        payTapAction(tappedOnPayable, canSend, canRetry, building)

    @Test
    fun `a tap on a payable preview submits it`() {
        assertEquals(PayTapAction.SUBMIT, action(tappedOnPayable = true, canSend = true, canRetry = false))
    }

    @Test
    fun `a tap on a refused preview rebuilds it`() {
        assertEquals(PayTapAction.REBUILD, action(tappedOnPayable = false, canSend = false, canRetry = true))
    }

    @Test
    fun `two taps on a refused preview - the second answer does not submit the rebuilt payment`() {
        // first answer: the preview is still refused
        assertEquals(PayTapAction.REBUILD, action(tappedOnPayable = false, canSend = false, canRetry = true))
        // second answer while the rebuild runs
        assertEquals(
            PayTapAction.IGNORE,
            action(tappedOnPayable = false, canSend = false, canRetry = false, building = true)
        )
        // second answer after the rebuild armed a payment
        assertEquals(PayTapAction.IGNORE, action(tappedOnPayable = false, canSend = true, canRetry = false))
        // a new tap, on the rebuilt payment, submits it
        assertEquals(PayTapAction.SUBMIT, action(tappedOnPayable = true, canSend = true, canRetry = false))
    }

    @Test
    fun `a failure that cannot be retried is reported`() {
        assertEquals(PayTapAction.REPORT, action(tappedOnPayable = false, canSend = false, canRetry = false))
    }
}
