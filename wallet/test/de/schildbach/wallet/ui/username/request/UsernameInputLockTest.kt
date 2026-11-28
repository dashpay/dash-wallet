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
 * Pins when the username input is locked, and by extension that the confirm
 * dialog is raised by USER ACTION only.
 *
 * Two defects sat here. The screen raised the confirm dialog off OBSERVED STATE
 * whenever it saw a `usernameVerified` flag — which was sticky, lived on an
 * activity-scoped view model, and was replayed by `Flow.observe`'s
 * `repeatOnLifecycle(STARTED)` on every re-subscribe. A later screen in the flow
 * therefore received a stale true and opened the dialog by itself: reported on the
 * instant-username step, where it appeared unbidden after typing a name. That
 * trigger was also redundant — the verify path it existed to resume already opens
 * the dialog from VerifyIdentityFragment before navigating back — so it was
 * removed along with the flag, leaving the Continue button, the skip-verify
 * branch, and VerifyIdentityFragment as the only sources.
 *
 * Second, the lock latched `isFocusable = false` with nothing anywhere setting it
 * back, so once it fired the screen was inert — the field could not be typed in
 * and back was the only way out. It is now derived from the state each emission.
 */
class UsernameInputLockTest {

    /** Mirrors the screen's lock decision. */
    private fun inputLocked(state: RequestUserNameUIState): Boolean =
        state.usernameRequestSubmitting

    @Test
    fun `the input is locked only while a submit is in flight`() {
        assertTrue(inputLocked(RequestUserNameUIState(usernameRequestSubmitting = true)))
    }

    @Test
    fun `the input is released once the submit ends`() {
        // The missing half of the original code, which only ever set it false.
        assertFalse(inputLocked(RequestUserNameUIState(usernameRequestSubmitting = false)))
    }

    @Test
    fun `a default state leaves the input usable`() {
        assertFalse(inputLocked(RequestUserNameUIState()))
    }

    /**
     * A replayed state cannot lock a screen that is not submitting. This is the
     * regression: state replayed onto a later screen used to both disable the
     * field and raise the dialog there.
     */
    @Test
    fun `a replayed non-submitting state cannot lock a later screen`() {
        val replayed = RequestUserNameUIState(
            usernameCheckSuccess = true,
            usernameCharactersValid = true,
            usernameLengthValid = true
        )
        assertFalse(inputLocked(replayed))
    }
}
