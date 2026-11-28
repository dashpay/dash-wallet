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

import de.schildbach.wallet.service.platform.work.isOwnContestedCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the contested-name scan narrowing on the identity
 * CREATION path ([requestedContestedCandidates], consumed by
 * `CreateIdentityService.restoreIdentity`'s `getAllContestedNames()` walk).
 *
 * Mirrors
 * [de.schildbach.wallet.service.platform.work.ContestedNameNarrowingTest] —
 * the restore-path version of the same defect — with the one difference that
 * matters here: the creation path's candidate is the name the identity
 * REQUESTED (the persisted creation record), not a recovered on-chain name,
 * because the walk is gated on `currentUsername == null`.
 *
 * The cost being narrowed away: mainnet DAPI nodes serving expired TLS certs
 * make each `getVoteContenders` round trip take ~1.8 min before the legacy
 * Platform client answers, and the unnarrowed walk queries every contested
 * name (~728) serially.
 *
 * Both sides of the correctness bar are asserted: the identity's OWN requested
 * name must still be queried, and every unrelated name must be skipped.
 */
class CreationContestedNameNarrowingTest {

    @Test
    fun `the requested primary name is a candidate`() {
        val candidates = requestedContestedCandidates(
            recoveredCurrentUsername = null,
            recoveredPrimaryUsername = null,
            recoveredSecondaryUsername = null,
            recoveredUsernameStatusKeys = emptySet(),
            requestedPrimary = "thedesert1ynx",
            requestedSecondary = null
        )
        assertEquals(setOf("thedesert1ynx"), candidates)
    }

    /** The user typed the DISPLAY label; the contested index only knows the fold. */
    @Test
    fun `a requested display label is folded onto the index form`() {
        val candidates = requestedContestedCandidates(
            recoveredCurrentUsername = null,
            recoveredPrimaryUsername = null,
            recoveredSecondaryUsername = null,
            recoveredUsernameStatusKeys = emptySet(),
            requestedPrimary = "TheDesertLynx",
            requestedSecondary = null
        )
        assertEquals(setOf("thedesert1ynx"), candidates)
    }

    /** Dual-username signup requests two names; both are this identity's own. */
    @Test
    fun `both requested names are candidates`() {
        val candidates = requestedContestedCandidates(
            recoveredCurrentUsername = null,
            recoveredPrimaryUsername = null,
            recoveredSecondaryUsername = null,
            recoveredUsernameStatusKeys = emptySet(),
            requestedPrimary = "Sam",
            requestedSecondary = "sam-1234"
        )
        assertEquals(setOf("sam", "sam-1234"), candidates)
    }

    /** A partially recovered identity keeps its recovered names in the set. */
    @Test
    fun `recovered names are folded in alongside the requested ones`() {
        val candidates = requestedContestedCandidates(
            recoveredCurrentUsername = "c0ntested1",
            recoveredPrimaryUsername = "c0ntested1",
            recoveredSecondaryUsername = null,
            recoveredUsernameStatusKeys = setOf("c0ntested1"),
            requestedPrimary = "thedesert1ynx",
            requestedSecondary = null
        )
        assertEquals(setOf("c0ntested1", "thedesert1ynx"), candidates)
    }

    @Test
    fun `blank and missing names never become candidates`() {
        val candidates = requestedContestedCandidates(
            recoveredCurrentUsername = null,
            recoveredPrimaryUsername = "",
            recoveredSecondaryUsername = "   ",
            recoveredUsernameStatusKeys = emptySet(),
            requestedPrimary = null,
            requestedSecondary = "  "
        )
        assertTrue(candidates.isEmpty())
    }

    /** The whole point: the identity's own requested name is STILL queried. */
    @Test
    fun `the requested contested name is still queried`() {
        val candidates = requestedContestedCandidates(
            recoveredCurrentUsername = null,
            recoveredPrimaryUsername = null,
            recoveredSecondaryUsername = null,
            recoveredUsernameStatusKeys = emptySet(),
            requestedPrimary = "TheDesertLynx",
            requestedSecondary = null
        )
        assertTrue(
            isOwnContestedCandidate(
                name = "thedesert1ynx",
                candidates = candidates,
                maybeDualUsernames = false,
                instantUsername = null
            )
        )
    }

    @Test
    fun `unrelated contested names are skipped`() {
        val candidates = requestedContestedCandidates(
            recoveredCurrentUsername = null,
            recoveredPrimaryUsername = null,
            recoveredSecondaryUsername = null,
            recoveredUsernameStatusKeys = emptySet(),
            requestedPrimary = "TheDesertLynx",
            requestedSecondary = null
        )
        listOf("0xb1tsp11t", "11ndbergdashw0rk", "asdfeb", "cat").forEach { name ->
            assertFalse(
                "expected $name to be skipped",
                isOwnContestedCandidate(
                    name = name,
                    candidates = candidates,
                    maybeDualUsernames = false,
                    instantUsername = null
                )
            )
        }
    }

    /**
     * An empty candidate set is the BROAD-scan signal: nothing matches, so the
     * caller must fall back to the (time-budgeted) full walk rather than
     * silently finding nothing.
     */
    @Test
    fun `an empty candidate set matches nothing`() {
        assertFalse(
            isOwnContestedCandidate(
                name = "thedesert1ynx",
                candidates = emptySet(),
                maybeDualUsernames = false,
                instantUsername = null
            )
        )
    }
}
