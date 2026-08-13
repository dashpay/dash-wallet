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
package de.schildbach.wallet.service.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ContactDerivationFacts` log line ([contactDerivationFactsLine]).
 *
 * The line has to answer one question from a single log pull: does any address
 * WE derive for a contact equal the address that actually received a payment
 * from them? So the tests pin the two properties that make that possible —
 * every derivation input is present, and a surface we could not read prints as
 * the literal `unavailable` rather than a guess or a silent omission.
 */
class ContactDerivationFactsTest {

    @Test
    fun `a fully populated line carries every derivation input`() {
        val line = contactDerivationFactsLine(
            ContactDerivationFacts(
                contactIdentityId = "4Zq4x62TE8UfF5PqB1ByJhkSHYQ8Kmc4wG2yqQqXyMkN",
                username = "splawik21",
                accountRefToContact = 133448798,
                accountRefFromContact = 67507117,
                derivationPath = "M/9H/5H/15H/0H/1234/5678",
                receivingAddresses = listOf("yVNhzVxgA6J1Vze3mLv9RQaFdwitVzb7nd", "ybiRq8Ecxk7TgRgj57V5G2bMdPW7jB6Utd"),
                sendingPath = "M/9H/5H/15H/67507117H/5678/1234",
                sendingAddresses = listOf("yWzRnHYCPqNQeCVpErgAyPFcqA8t9J1u2N")
            )
        )
        assertEquals(
            "ContactDerivationFacts: contact=4Zq4x62TE8UfF5PqB1ByJhkSHYQ8Kmc4wG2yqQqXyMkN " +
                "username=splawik21 accountRefToContact=133448798 accountRefFromContact=67507117 " +
                "path=M/9H/5H/15H/0H/1234/5678 " +
                "receiving=[yVNhzVxgA6J1Vze3mLv9RQaFdwitVzb7nd,ybiRq8Ecxk7TgRgj57V5G2bMdPW7jB6Utd] " +
                "sendingPath=M/9H/5H/15H/67507117H/5678/1234 " +
                "sending=[yWzRnHYCPqNQeCVpErgAyPFcqA8t9J1u2N]",
            line
        )
    }

    /** A grep on the tag must find every contact's line. */
    @Test
    fun `the line is greppable on the tag`() {
        val line = contactDerivationFactsLine(
            ContactDerivationFacts("id", null, null, null, null, null)
        )
        assertTrue(line.startsWith("ContactDerivationFacts: "))
    }

    /** Nothing is ever guessed: an unreadable surface says so. */
    @Test
    fun `unreadable surfaces print unavailable`() {
        val line = contactDerivationFactsLine(
            ContactDerivationFacts(
                contactIdentityId = "4Zq4x62TE8UfF5PqB1ByJhkSHYQ8Kmc4wG2yqQqXyMkN",
                username = null,
                accountRefToContact = null,
                accountRefFromContact = null,
                derivationPath = null,
                receivingAddresses = null
            )
        )
        assertEquals(
            "ContactDerivationFacts: contact=4Zq4x62TE8UfF5PqB1ByJhkSHYQ8Kmc4wG2yqQqXyMkN " +
                "username=unavailable accountRefToContact=unavailable accountRefFromContact=unavailable " +
                "path=unavailable receiving=unavailable sendingPath=unavailable sending=unavailable",
            line
        )
    }

    /**
     * A keychain that exists but yields nothing is NOT the same as no keychain
     * — the empty list has to stay distinguishable from `unavailable`.
     */
    @Test
    fun `an empty address list is distinct from an unreadable one`() {
        val empty = contactDerivationFactsLine(
            ContactDerivationFacts("id", "alice", 0, 0, "M/9H", emptyList())
        )
        assertTrue(empty.contains(" receiving=[] "))
    }

    /** accountReference 0 is a real value, not a missing one. */
    @Test
    fun `a zero accountReference is printed, not treated as missing`() {
        val line = contactDerivationFactsLine(
            ContactDerivationFacts("id", "alice", 0, 0, "M/9H", listOf("yAddr"))
        )
        assertTrue(line.contains("accountRefToContact=0"))
        assertTrue(line.contains("accountRefFromContact=0"))
    }

    /**
     * The two directions must be separately readable: a wallet with no request
     * FROM the contact has no sending chain to derive from (their xpub is what
     * that request carries), and that must print as `unavailable` while the
     * receiving side still reports its addresses. This is the exact shape of
     * splawik's three outgoing-only rows.
     */
    @Test
    fun `an outgoing-only contact reports receiving addresses and no sending ones`() {
        val line = contactDerivationFactsLine(
            ContactDerivationFacts(
                contactIdentityId = "id",
                username = "ryszard1951",
                accountRefToContact = 133448798,
                accountRefFromContact = null,
                derivationPath = "M/9H/5H/15H/0H/1234/5678",
                receivingAddresses = listOf("yVNhzVxgA6J1Vze3mLv9RQaFdwitVzb7nd"),
                sendingPath = null,
                sendingAddresses = null
            )
        )
        assertTrue(line.contains("receiving=[yVNhzVxgA6J1Vze3mLv9RQaFdwitVzb7nd]"))
        assertTrue(line.endsWith("sendingPath=unavailable sending=unavailable"))
    }

    /** The cap keeps the line readable while still covering a payer's first picks. */
    @Test
    fun `the address cap is small enough to keep the line readable`() {
        assertTrue("cap should stay small", MAX_LOGGED_ADDRESSES in 1..10)
    }
}
