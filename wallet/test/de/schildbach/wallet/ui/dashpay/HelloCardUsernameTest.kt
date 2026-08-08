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

import org.dashj.platform.sdk.platform.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for the hello card's greeting name
 * ([helloCardUsername]) — the "Hello %s," tile on the home screen.
 *
 * Pins the live defect: after registering `brian-s21` the identity record
 * held the DPNS-NORMALIZED label `br1an-s21` (dashj `recoverUsernames()`
 * writes `DomainDocument.normalizedLabel` into `primaryUsername`, which
 * `IdentityRepository.updateBlockchainIdentityData` copied into the record),
 * so the card greeted the user with "Hello br1an-s21," while the profile
 * screen showed `brian-s21` correctly.
 *
 * These use the REAL `Names.normalizeString` so the homoglyph folding
 * (i/l→1, o→0, lowercased) is genuinely exercised rather than stubbed.
 */
class HelloCardUsernameTest {

    private val normalize: (String) -> String = Names::normalizeString

    /** Guards the premise: `brian-s21` really does fold to `br1an-s21`. */
    @Test
    fun `the homoglyph premise holds`() {
        assertEquals("br1an-s21", Names.normalizeString("brian-s21"))
        assertEquals("br1antest63a", Names.normalizeString("briantest63a"))
    }

    @Test
    fun `a normalized record label greets with the label the user registered`() {
        assertEquals(
            "brian-s21",
            helloCardUsername(
                recordUsername = "br1an-s21",
                profileUsername = "brian-s21",
                normalize = normalize
            )
        )
    }

    @Test
    fun `the earlier testnet case greets with the display label too`() {
        assertEquals(
            "briantest63a",
            helloCardUsername(
                recordUsername = "br1antest63a",
                profileUsername = "briantest63a",
                normalize = normalize
            )
        )
    }

    @Test
    fun `a record already holding the display label is left alone`() {
        assertEquals(
            "brian-s21",
            helloCardUsername(
                recordUsername = "brian-s21",
                profileUsername = "brian-s21",
                normalize = normalize
            )
        )
    }

    @Test
    fun `a name with nothing to fold is unaffected`() {
        assertEquals(
            "adam-42",
            helloCardUsername(
                recordUsername = "adam-42",
                profileUsername = "adam-42",
                normalize = normalize
            )
        )
    }

    @Test
    fun `no profile yet falls back to the record`() {
        assertEquals(
            "br1an-s21",
            helloCardUsername(
                recordUsername = "br1an-s21",
                profileUsername = null,
                normalize = normalize
            )
        )
        assertEquals(
            "br1an-s21",
            helloCardUsername(
                recordUsername = "br1an-s21",
                profileUsername = "",
                normalize = normalize
            )
        )
    }

    /**
     * The dual-creation case: this card greets the INSTANT secondary while
     * the freshly-seeded profile still carries the CONTESTED primary. A
     * profile naming a different DPNS entry must never win.
     */
    @Test
    fun `a profile holding a different name never wins`() {
        assertEquals(
            "a11ce-2",
            helloCardUsername(
                recordUsername = "a11ce-2",
                profileUsername = "contested11",
                normalize = normalize
            )
        )
    }

    @Test
    fun `no record username yields no greeting name`() {
        assertNull(
            helloCardUsername(
                recordUsername = null,
                profileUsername = "brian-s21",
                normalize = normalize
            )
        )
    }
}
