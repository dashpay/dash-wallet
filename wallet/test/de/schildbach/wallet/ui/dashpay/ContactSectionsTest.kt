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
package de.schildbach.wallet.ui.dashpay

import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contacts screen's three sections.
 *
 * Live defect (11.10.86, splawik): ContactDerivationFacts enumerated 32
 * contacts while the screen listed 29. The three absent — ryszard1951,
 * TheBitcoinBarbie, thedesertlynx63 — were exactly the rows logged with
 * `accountRefFromContact=unavailable`, i.e. requests WE sent that were never
 * reciprocated. They were dropped at PlatformRepo.getFromProfiles (which
 * requires `includeSentPending` for them, and nothing set it) and the screen
 * had no section for them either.
 */
class ContactSectionsTest {

    private fun profile(userId: String) = DashPayProfile(userId, userId)

    private fun request(from: String, to: String) = DashPayContactRequest(
        userId = from,
        toUserId = to,
        accountReference = 0,
        encryptedPublicKey = ByteArray(0),
        senderKeyIndex = 0,
        recipientKeyIndex = 0,
        timestamp = 0L,
        encryptedAccountLabel = null,
        autoAcceptProof = null
    )

    private fun result(username: String, sent: Boolean, received: Boolean) = UsernameSearchResult(
        username,
        profile(username),
        if (sent) request("me", username) else null,
        if (received) request(username, "me") else null
    )

    @Test
    fun `an outgoing-only request lands in its own section`() {
        val sections = splitContactSections(listOf(result("ryszard1951", sent = true, received = false)))
        assertEquals(listOf("ryszard1951"), sections.outgoingPending.map { it.username })
        assertTrue(sections.established.isEmpty())
        assertTrue(sections.incomingPending.isEmpty())
    }

    @Test
    fun `the three sections are disjoint and cover everything`() {
        val data = listOf(
            result("ryszard1951", sent = true, received = false),
            result("TheBitcoinBarbie", sent = true, received = false),
            result("thedesertlynx63", sent = true, received = false),
            result("voja", sent = true, received = true),
            result("skynet", sent = false, received = true)
        )
        val sections = splitContactSections(data)

        assertEquals(3, sections.outgoingPending.size)
        assertEquals(1, sections.established.size)
        assertEquals(1, sections.incomingPending.size)
        // Nothing double-listed, nothing dropped.
        val all = sections.outgoingPending + sections.established + sections.incomingPending
        assertEquals(data.size, all.size)
        assertEquals(data.map { it.username }.toSet(), all.map { it.username }.toSet())
    }

    /** A one-way request must never be presented as a mutual contact. */
    @Test
    fun `an outgoing-only request is never counted as an established contact`() {
        val sections = splitContactSections(listOf(result("ryszard1951", sent = true, received = false)))
        assertTrue(sections.established.isEmpty())
    }
}
