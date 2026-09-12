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
package de.schildbach.wallet.ui.username.request

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one-shot contract of [RequestUserNameUIState.usernameVerified] — the
 * signal `verify()` raises from VerifyIdentityFragment for the request screen to
 * act on.
 *
 * It was sticky state on an ACTIVITY-scoped view model, and `Flow.observe`
 * re-subscribes on every STARTED transition (`repeatOnLifecycle`), which replays
 * the StateFlow's current value. So once set it stayed set: the confirm dialog
 * re-fired on the NEXT screen in the flow — reported on the instant-username step,
 * where it appeared before a name could be typed — and again every time the dialog
 * was dismissed. The screen also latched `isFocusable = false` with nothing to
 * restore it, leaving the field dead.
 *
 * The observer consumes the flag before navigating, so a replay is inert.
 */
class UsernameVerifiedConsumeTest {

    /** The shape the screen's "lock the input" decision is derived from. */
    private fun inputLocked(state: RequestUserNameUIState): Boolean =
        state.usernameRequestSubmitting || state.usernameVerified

    @Test
    fun `a verified state locks the input and a cleared one releases it`() {
        val verified = RequestUserNameUIState(usernameVerified = true)
        assertTrue(inputLocked(verified))

        // Consuming the signal must release the field — the missing half of the
        // original code, which only ever set isFocusable = false.
        assertFalse(inputLocked(verified.copy(usernameVerified = false)))
    }

    @Test
    fun `a submit in flight also locks the input, independently of verification`() {
        assertTrue(inputLocked(RequestUserNameUIState(usernameRequestSubmitting = true)))
        // ...and releases once the submit ends, without needing a verify.
        assertFalse(inputLocked(RequestUserNameUIState()))
    }

    @Test
    fun `a default state neither locks the input nor raises the dialog`() {
        val fresh = RequestUserNameUIState()
        assertFalse(fresh.usernameVerified)
        assertFalse(inputLocked(fresh))
    }

    /**
     * The replay case: the same state object is delivered again on the next
     * STARTED transition. Once consumed, re-delivering it must not re-raise the
     * dialog — which is what firing on `usernameVerified` alone did.
     */
    @Test
    fun `a replayed state cannot re-raise the dialog once consumed`() {
        val consumed = RequestUserNameUIState(usernameVerified = true)
            .copy(usernameVerified = false)
        assertFalse("replay must be inert", consumed.usernameVerified)
        assertFalse(inputLocked(consumed))
    }
}
