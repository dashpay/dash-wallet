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
package de.schildbach.wallet.ui.dashpay.utils

import org.dashj.platform.sdk.platform.Names
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [preferDisplayLabel] — the single rule the wallet uses to keep a
 * DPNS name's human `label` on screen instead of the `normalizedLabel` dashj
 * hands back from `recoverUsernames()`.
 *
 * Uses the REAL `Names.normalizeString`, the same function DPNS derives
 * `normalizedLabel` with, so the homoglyph folding is exercised for real.
 */
class UsernameDisplayTest {

    private val normalize: (String) -> String = Names::normalizeString

    @Test
    fun `the display label wins when it names the same DPNS entry`() {
        assertEquals("brian-s21", preferDisplayLabel("brian-s21", "br1an-s21", normalize))
        assertEquals("Olio", preferDisplayLabel("Olio", "0110", normalize))
        assertEquals("WilliamOslo", preferDisplayLabel("WilliamOslo", "w1111am0s10", normalize))
    }

    @Test
    fun `a different name loses to the on-chain value`() {
        assertEquals("br1an-s21", preferDisplayLabel("someone-else", "br1an-s21", normalize))
    }

    @Test
    fun `a missing display candidate loses to the on-chain value`() {
        assertEquals("br1an-s21", preferDisplayLabel(null, "br1an-s21", normalize))
        assertEquals("br1an-s21", preferDisplayLabel("", "br1an-s21", normalize))
    }

    @Test
    fun `an identical pair is returned unchanged`() {
        assertEquals("br1an-s21", preferDisplayLabel("br1an-s21", "br1an-s21", normalize))
        assertEquals("adam-42", preferDisplayLabel("adam-42", "adam-42", normalize))
    }

    /**
     * The normalized form is idempotent, so a record already damaged by the
     * old clobber cannot be repaired from itself — it takes a second source
     * (the profile's `.label`). Pinned so the limitation stays visible.
     */
    @Test
    fun `an already-normalized record cannot repair itself`() {
        assertEquals("br1an-s21", preferDisplayLabel("br1an-s21", "br1an-s21", normalize))
        assertEquals("br1an-s21", normalize("br1an-s21"))
    }

    // ── recoveredPrimaryIsPendingDualSecondary (the Mo-972 adoption guard) ──

    @Test
    fun `dual instant secondary mis-adopted as primary is detected`() {
        // The live S21 shape: requested contested primary + registered instant
        // secondary; dashj recovered the secondary's NORMALIZED label as
        // "primary". Raw equality can never catch this (br1m0ztest3 != brimoztest3).
        assertEquals(
            true,
            recoveredPrimaryIsPendingDualSecondary(
                recordPrimary = "brimoztest",
                recordSecondary = "brimoztest3",
                recoveredPrimary = "br1m0ztest3",
                normalize = normalize
            )
        )
    }

    @Test
    fun `a contested primary that has WON adoption stands down`() {
        // Once the contest resolves and the name registers, the recovered
        // primary normalizes to the RECORD primary — normal adoption must run.
        assertEquals(
            false,
            recoveredPrimaryIsPendingDualSecondary(
                recordPrimary = "brimoztest",
                recordSecondary = "brimoztest3",
                recoveredPrimary = "br1m0ztest",
                normalize = normalize
            )
        )
    }

    @Test
    fun `single-name identities never trip the guard`() {
        assertEquals(
            false,
            recoveredPrimaryIsPendingDualSecondary(
                recordPrimary = "brimoztest2",
                recordSecondary = null,
                recoveredPrimary = "br1m0ztest2",
                normalize = normalize
            )
        )
    }

    @Test
    fun `a non-contestable requested replacement primary is kept too`() {
        // A name-only retry after a lost or locked vote (match master): the requested replacement
        // is the primary even when it is not contestable, and the registered instant secondary
        // dashj adopted as "primary" must not overwrite it.
        assertEquals(
            true,
            recoveredPrimaryIsPendingDualSecondary(
                recordPrimary = "somename22",
                recordSecondary = "somename23",
                recoveredPrimary = "s0mename23",
                normalize = normalize
            )
        )
    }

    @Test
    fun `a registered replacement primary keeps the record primary when dashj picks the secondary`() {
        // Both names are registered now; dashj may still call the old instant name "primary".
        assertEquals(
            true,
            recoveredPrimaryIsPendingDualSecondary(
                recordPrimary = "newname22",
                recordSecondary = "brimoztest3",
                recoveredPrimary = "br1m0ztest3",
                normalize = normalize
            )
        )
    }
}
