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
package de.schildbach.wallet.service.platform.work

import org.dashj.platform.sdk.platform.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the contested-name scan narrowing in
 * [RestoreIdentityWorker] — [contestedNameCandidates] / [isOwnContestedCandidate].
 *
 * Pins the live defect (11.10.84 mainnet restore of `thedesert1ynx`): the only
 * narrowing was gated on `maybeDualUsernames`, which is FALSE when the recovered
 * name is itself contestable, so the worker queried `getVoteContenders` for all
 * 728 contested names serially (~1.8 min each ≈ 22 h) and never reached
 * `finishRestoration()` → `initSync(true)`.
 *
 * The correctness bar is two-sided and both sides are asserted here:
 * - the identity's OWN name must still be checked (a contested name must still
 *   be DETECTED — the scan's decision is unchanged for it);
 * - every unrelated name must be skipped.
 *
 * Uses the REAL `Names.normalizeString` so the DPNS homoglyph fold (o→0, i/l→1,
 * lowercase) is genuinely exercised rather than stubbed.
 */
class ContestedNameNarrowingTest {

    /** Guards the premise: the field name really is its own normalized form. */
    @Test
    fun `the homoglyph premise holds`() {
        assertEquals("thedesert1ynx", Names.normalizeString("thedesertlynx"))
        assertEquals("thedesert1ynx", Names.normalizeString("thedesert1ynx"))
    }

    @Test
    fun `the recovered on-chain name is a candidate`() {
        val candidates = contestedNameCandidates(
            currentUsername = "thedesert1ynx",
            primaryUsername = "thedesert1ynx",
            secondaryUsername = null,
            usernameStatusKeys = setOf("thedesert1ynx"),
            requestedLabel = null
        )
        assertEquals(setOf("thedesert1ynx"), candidates)
    }

    /** The user typed the DISPLAY label; the index only knows the fold. */
    @Test
    fun `a display label is folded onto the index form`() {
        val candidates = contestedNameCandidates(
            currentUsername = null,
            primaryUsername = null,
            secondaryUsername = null,
            usernameStatusKeys = emptySet(),
            requestedLabel = "TheDesertLynx"
        )
        assertEquals(setOf("thedesert1ynx"), candidates)
    }

    @Test
    fun `blank and missing names never become candidates`() {
        val candidates = contestedNameCandidates(
            currentUsername = null,
            primaryUsername = "",
            secondaryUsername = "   ",
            usernameStatusKeys = emptySet(),
            requestedLabel = null
        )
        assertTrue(candidates.isEmpty())
    }

    /** The whole point: the identity's own contested name is STILL checked. */
    @Test
    fun `the identity's own contested name is still queried`() {
        val candidates = setOf("thedesert1ynx")
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
        val candidates = setOf("thedesert1ynx")
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

    /** The historic dual-username heuristic is preserved as a union term. */
    @Test
    fun `the dual-username substring heuristic still admits the contested name`() {
        assertTrue(
            isOwnContestedCandidate(
                name = "sam",
                candidates = setOf("sam-1234"),
                maybeDualUsernames = true,
                instantUsername = "sam-1234"
            )
        )
        assertFalse(
            isOwnContestedCandidate(
                name = "cat",
                candidates = setOf("sam-1234"),
                maybeDualUsernames = true,
                instantUsername = "sam-1234"
            )
        )
    }

    /** With no dual username, the substring term must not widen the scan. */
    @Test
    fun `the substring heuristic does not apply without dual usernames`() {
        assertFalse(
            isOwnContestedCandidate(
                name = "sam",
                candidates = setOf("sam-1234"),
                maybeDualUsernames = false,
                instantUsername = "sam-1234"
            )
        )
    }

    /**
     * An empty candidate set is the BROAD-scan signal: nothing matches, so the
     * caller must fall back to the (time-budgeted) full walk rather than silently
     * finding nothing.
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

    // ── skipping the list fetches outright ───────────────────────────────

    /**
     * Field 11.10.86 (splawik): a TARGETED scan for `sp1aw1k21` — not
     * contestable, it carries `2` and `9` — still fetched both contested
     * indexes (12 names in 32.65 s, 728 names in 3.56 s) and matched nothing
     * in either. Neither index can hold a non-contestable name, so both
     * fetches were provably pointless.
     */
    @Test
    fun `a targeted scan for a non-contestable name needs no list fetch`() {
        assertFalse(
            contestedNameListsCanMatch(
                targetedScan = true,
                ownCandidateNames = setOf("sp1aw1k21")
            )
        )
    }

    /** A contestable candidate could genuinely be in the index — still fetch. */
    @Test
    fun `a targeted scan for a contestable name still fetches`() {
        assertTrue(
            contestedNameListsCanMatch(
                targetedScan = true,
                ownCandidateNames = setOf("thedesert1ynx")
            )
        )
    }

    /** One contestable candidate among several is enough to need the lists. */
    @Test
    fun `a mixed candidate set still fetches`() {
        assertTrue(
            contestedNameListsCanMatch(
                targetedScan = true,
                ownCandidateNames = setOf("sp1aw1k21", "cat")
            )
        )
    }

    /** The BROAD path is untouched: with no candidate known, the lists ARE the search. */
    @Test
    fun `a broad scan always fetches`() {
        assertTrue(
            contestedNameListsCanMatch(targetedScan = false, ownCandidateNames = emptySet())
        )
    }
}
