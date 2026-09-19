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

import de.schildbach.wallet.Constants
import kotlin.random.Random
import org.dashj.platform.sdk.platform.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DPNS contested-name rule, pinned.
 *
 * `Names.isUsernameContestable` is the app's ONE contestability predicate and
 * every funding decision reads it, so this test characterises it directly: a
 * dashj bump that changes the rule has to break here rather than quietly
 * change what a username creation charges.
 *
 * It also pins the two request-screen hints in [UsernameContestability] as an
 * exact decomposition of that rule, so the checkmarks cannot drift away from
 * what the wallet actually spends.
 *
 * Written off a QA report (MO-973) that read `asd10augsh` as non-contestable
 * because it contains digits, and concluded the creation path had overcharged
 * it. It had not: `0` and `1` are letter homoglyphs and stay inside the
 * contested index, so 0.25 DASH was the correct denomination.
 */
class UsernameContestabilityTest {

    private fun contestable(name: String) = Names.isUsernameContestable(name)

    @Test
    fun `a short all-letter name is contestable`() {
        assertTrue(contestable("asd"))
        assertTrue(contestable("asdsd"))
        assertTrue(contestable("alice"))
        assertTrue(contestable("MixedCase"))
        assertTrue(contestable("with-hyphen"))
    }

    @Test
    fun `a 2-9 digit takes a name out of the contest`() {
        assertFalse(contestable("asd2"))
        assertFalse(contestable("asd9"))
        assertFalse(contestable("alice42"))
        assertFalse(contestable("asd10augda6"))
        // One non-homoglyph digit anywhere is enough, wherever it sits.
        assertFalse(contestable("5lice"))
        assertFalse(contestable("ali5ce"))
        assertFalse(contestable("alice5"))
    }

    @Test
    fun `0 and 1 are letter homoglyphs, so digits alone do not clear the contest`() {
        // The premise the QA report got wrong: "contains a digit" is NOT the
        // rule. `0` looks like `o` and `1` looks like `l`, so DPNS keeps both
        // in the contested index.
        assertTrue(contestable("he110"))
        assertTrue(contestable("asd10"))
        assertTrue(contestable("asd10augsh"))
        assertTrue(contestable("asd10augda1"))
        assertTrue(contestable("0110"))
    }

    @Test
    fun `the contested index has a 3 to 19 character length window`() {
        val letters = "a".repeat(Constants.USERNAME_MAX_LENGTH)

        // Below the window DPNS rejects the name outright.
        assertFalse(contestable(letters.take(DPNS_CONTESTED_MIN_LENGTH - 1)))
        // First and last contestable lengths.
        assertTrue(contestable(letters.take(DPNS_CONTESTED_MIN_LENGTH)))
        assertTrue(contestable(letters.take(DPNS_CONTESTED_MAX_LENGTH)))
        // One past the window an all-letter name escapes the contest.
        assertFalse(contestable(letters.take(DPNS_CONTESTED_MAX_LENGTH + 1)))
        assertFalse(contestable(letters.take(Constants.USERNAME_MAX_LENGTH)))
    }

    @Test
    fun `the non-contested length hint starts one past the DPNS maximum`() {
        // The hint threshold is a hand-written constant; if the DPNS window
        // ever moves, this is the line that has to move with it.
        assertEquals(
            DPNS_CONTESTED_MAX_LENGTH + 1,
            Constants.USERNAME_NON_CONTESTED_MIN_LENGTH
        )
    }

    @Test
    fun `the field-log names resolve to the denominations the creation path chose`() {
        // MO-973: routed to the shielded path, funded at the 0.25 contested
        // denomination, logged contested=true. Correct.
        assertTrue(contestable("asd10augsh"))
        // MO-972: the balance gate required 0.25 for every name the user
        // typed, because none of them contained a 2-9 digit.
        listOf("asd", "asd1", "asd10", "asd10a", "asd10au", "asd10aug", "asd10augd", "asd10augda", "asd10augda1")
            .forEach { assertTrue("expected '$it' to be contestable", contestable(it)) }
        // Adding a `6` is what actually cleared the gate at 0.03.
        assertFalse(contestable("asd10augda6"))
    }

    @Test
    fun `each request-screen hint names one reason, and together they match the rule exactly`() {
        val alphabet = (('a'..'z') + ('A'..'Z') + ('0'..'9') + '-').toList()
        val random = Random(20260910)
        var escapedByLength = 0
        var escapedByCharacters = 0
        var contestableSeen = 0

        for (length in DPNS_CONTESTED_MIN_LENGTH..Constants.USERNAME_MAX_LENGTH) {
            repeat(400) {
                // Hyphens are legal inside a name but not at either end, so
                // build only names the request screen would actually accept.
                val name = buildString {
                    append(('a'..'z').random(random))
                    repeat(length - 2) { append(alphabet.random(random)) }
                    append(('a'..'z').random(random))
                }

                val byLength = isNonContestedByLength(name)
                val byCharacters = isNonContestedByCharacters(name)
                assertTrue(
                    "hints disagree with Names.isUsernameContestable for '$name'",
                    !contestable(name) == (byLength || byCharacters)
                )

                if (byLength) escapedByLength++
                if (byCharacters) escapedByCharacters++
                if (contestable(name)) contestableSeen++
            }
        }

        // Guard against the equivalence above passing vacuously.
        assertTrue("no name escaped by length", escapedByLength > 0)
        assertTrue("no name escaped by characters", escapedByCharacters > 0)
        assertTrue("no contestable name generated", contestableSeen > 0)
    }
}
