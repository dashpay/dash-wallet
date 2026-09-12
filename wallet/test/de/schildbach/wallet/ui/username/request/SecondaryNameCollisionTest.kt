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
 * Pure-logic tests for [secondaryNameCollidesWithPrimary] — the instant
 * (secondary) username must be a DIFFERENT DPNS name from the contested primary.
 *
 * Pins MO-973 report 3 (Samsung, 12000011): "I've requested a contested username,
 * but the wallet allowed me to use the same name for instance username."
 * `RequestUsernameFragment` pre-fills the secondary input with the primary and
 * only enforces `startsWith(primary)` — nothing required a suffix, the
 * suffix-character rule is commented out, and the clear button restores the bare
 * primary. The usual availability query cannot catch it either: a contested name
 * is absent from the DPNS unique index until its vote resolves, so it reads as
 * "not taken".
 *
 * Uses the REAL `Names.normalizeString`, so the homoglyph fold (o→0, i/l→1,
 * lowercase) is genuinely exercised — that fold is what the contract's unique
 * index keys on, so two names that differ only by a homoglyph are ONE name.
 */
class SecondaryNameCollisionTest {

    @Test
    fun `the untouched pre-filled name is a collision`() {
        // Exactly the field case: the user pressed on without adding a suffix.
        assertTrue(secondaryNameCollidesWithPrimary("asdaug11sh", "asdaug11sh"))
    }

    @Test
    fun `the intended suffixed name is not a collision`() {
        // The flow's own example: contested "gffh" + instant "gffh-2".
        assertFalse(secondaryNameCollidesWithPrimary("gffh-2", "gffh"))
        assertFalse(secondaryNameCollidesWithPrimary("asdaug11sh2", "asdaug11sh"))
    }

    @Test
    fun `a homoglyph-only difference is still the same DPNS name`() {
        // o/0 and i/l/1 fold together in the contested index, so these collide
        // even though the typed text differs.
        assertTrue(secondaryNameCollidesWithPrimary("asd0", "asdo"))
        assertTrue(secondaryNameCollidesWithPrimary("a1ice", "alice"))
        assertTrue(secondaryNameCollidesWithPrimary("ALICE", "alice"))
    }

    @Test
    fun `a genuinely different name is not a collision`() {
        assertFalse(secondaryNameCollidesWithPrimary("something-else", "asdaug11sh"))
    }

    @Test
    fun `no primary means nothing to collide with`() {
        // Single-name flow: the guard must never block it.
        assertFalse(secondaryNameCollidesWithPrimary("asdaug11sh", null))
        assertFalse(secondaryNameCollidesWithPrimary("asdaug11sh", ""))
        assertFalse(secondaryNameCollidesWithPrimary("asdaug11sh", "   "))
    }

    @Test
    fun `a blank secondary is not reported as a collision`() {
        // Emptiness is the "too short" rule's business, not this one's.
        assertFalse(secondaryNameCollidesWithPrimary("", "asdaug11sh"))
    }

    // ── CodeRabbit: the stale in-flight lookup must not enable submit ──────

    /**
     * checkUsernameValid stops NEW lookups once the secondary collides with the
     * primary, but a lookup already in flight for the SUFFIXED name can land
     * afterwards and set usernameCheckSuccess. The fragment's success branch then
     * asks [usernameSubmitButtonState] — which takes no collision input, and for a
     * Secondary enables on `!usernameExists && !usernameContestable` — about the
     * STALE name, while the field holds the bare primary.
     *
     * This pins the shape of that hazard: the stale suffixed name genuinely WOULD
     * enable the button, which is why the fragment now gates its success branch on
     * `!secondaryNameSameAsPrimary` rather than relying on the button state alone.
     */
    @Test
    fun `a stale lookup for the suffixed name would otherwise enable submit`() {
        // The stale result: "gffh-2" — 6 chars but carrying a 2, so NOT contestable,
        // and not taken. Exactly the inputs that enable a Secondary submit.
        assertFalse(secondaryNameCollidesWithPrimary("gffh-2", "gffh"))

        // Meanwhile the field has been reverted to the bare primary, which DOES
        // collide — so the collision flag, not the stale lookup, must decide.
        assertTrue(secondaryNameCollidesWithPrimary("gffh", "gffh"))
    }

}
