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
 * Pins [usernameCheckResultIsCurrent] — the gate that keeps an availability verdict from being
 * applied to a name the user has already replaced.
 *
 * CodeRabbit finding on dashpay/dash-wallet#1564: `checkUsername` launches a coroutine per
 * debounced keystroke burst and cancels nothing, so a slow lookup can land after a newer one
 * and write its own `usernameExists` / `usernameContested` / `usernameBlocked` over the current
 * name's state.
 */
class UsernameCheckCurrencyTest {

    @Test
    fun theNameStillInTheFieldIsCurrent() {
        assertTrue(usernameCheckResultIsCurrent("alice", "alice"))
    }

    @Test
    fun aNameTheFieldHasMovedPastIsNotCurrent() {
        assertFalse(usernameCheckResultIsCurrent("alice", "bob"))
    }

    @Test
    fun aClearedFieldLeavesNothingCurrent() {
        // reset() nulls the pending label; a verdict has no name left to describe.
        assertFalse(usernameCheckResultIsCurrent("alice", null))
    }

    @Test
    fun matchingIsExactNotAPrefix() {
        // The instant-name screen pre-fills the primary and the user appends a suffix, so
        // prefix-matching would treat the primary's verdict as the suffixed name's.
        assertFalse(usernameCheckResultIsCurrent("alice", "alice1"))
        assertFalse(usernameCheckResultIsCurrent("alice1", "alice"))
    }

    @Test
    fun matchingIsCaseSensitiveOnTheRawLabel() {
        // The raw label is what both checkUsernameValid and checkUsername receive; DPNS
        // normalization happens elsewhere. Two spellings are two different lookups.
        assertFalse(usernameCheckResultIsCurrent("Alice", "alice"))
    }

    @Test
    fun aNameRetypedAfterDetouringIsCurrentAgain() {
        // alice -> bob -> alice: a lookup still in flight for 'alice' describes exactly what
        // the field holds again, so applying it is correct, not stale.
        assertTrue(usernameCheckResultIsCurrent("alice", "alice"))
    }
}
