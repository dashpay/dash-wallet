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
 * Pins [usernameCheckResultIsCurrent] — the gate that keeps an availability verdict from
 * being applied once a newer claim has taken the slot.
 *
 * CodeRabbit findings on dashpay/dash-wallet#1564. The first was that `checkUsername`
 * launches a coroutine per debounced keystroke burst and cancels nothing, so a slow lookup
 * can land after a newer one and write its own `usernameExists` / `usernameContested` /
 * `usernameBlocked` over the current name's state. The second was that comparing LABELS is
 * not enough to tell two lookups apart, which is why this takes tokens.
 */
class UsernameCheckCurrencyTest {

    @Test
    fun theNewestClaimOnTheSlotIsCurrent() {
        assertTrue(usernameCheckResultIsCurrent(checkedToken = 7L, currentToken = 7L))
    }

    @Test
    fun anOvertakenLookupIsNotCurrent() {
        assertFalse(usernameCheckResultIsCurrent(checkedToken = 6L, currentToken = 7L))
    }

    @Test
    fun aRepeatedLabelStillGetsItsOwnToken() {
        // The case label equality got wrong: alice -> bob -> alice. Every claim takes a
        // fresh token, so the FIRST alice lookup (#1) cannot pass while the third (#5) is
        // the live one — even though both were asked about the same name.
        val firstAlice = 1L
        val secondAlice = 5L
        assertFalse(
            "the first alice lookup must not answer for the second",
            usernameCheckResultIsCurrent(checkedToken = firstAlice, currentToken = secondAlice)
        )
        assertTrue(usernameCheckResultIsCurrent(checkedToken = secondAlice, currentToken = secondAlice))
    }

    @Test
    fun tokensOnlyEverMoveForward() {
        // The token is monotonic, so "checked > current" is not a state the VM can reach.
        // Pinned anyway: the predicate must not treat it as current if it ever did.
        assertFalse(usernameCheckResultIsCurrent(checkedToken = 8L, currentToken = 7L))
    }

    @Test
    fun theInitialSlotIsNotClaimedByAnyLookup() {
        // usernameCheckToken starts at 0 and every lookup pre-increments, so no real
        // lookup ever carries 0 — a reset (which bumps) cannot be satisfied by one.
        assertFalse(usernameCheckResultIsCurrent(checkedToken = 0L, currentToken = 1L))
    }
}
