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

import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the contested RE-REGISTRATION guard in
 * [RestoreIdentityWorker] — [contestedReregistrationCheckApplies] /
 * [isOwnIdentityAContender].
 *
 * Pins the live defect (testnet 12000000, emulator, 2026-09-10). Identity
 * `7oct2Gqw…` was funded 0.25 DASH and successfully registered the contested name
 * `test-contested-1000`; the explorer shows the name plus exactly 20,000,000,000
 * credits (0.20 DASH) gone to the vote poll's prefunded specialized balance beyond
 * gas. `recoverUsernames` still reported "none", because a contested label is not
 * awarded in the DPNS unique index until the vote concludes — so the worker
 * re-registered and the FFI rejected the duplicate with
 * "Insufficient identity balance 4680452100 required 20000100000".
 *
 * The correctness bar is two-sided:
 * - a name this identity ALREADY contends for must be detected, so the duplicate
 *   0.2 DASH prefund is never paid;
 * - a name it does NOT contend for (and any non-contestable name) must still be
 *   registered exactly as before — the guard must not strand a genuine create.
 */
class ContestedReregistrationGuardTest {

    private fun id(seed: Byte) = Identifier.from(ByteArray(32) { seed })

    private val ownId = id(1)
    private val otherId = id(2)

    // ---- contestedReregistrationCheckApplies -------------------------------

    @Test
    fun `a contestable label is worth the vote-state query`() {
        // 19 chars, only [a-zA-Z01-] — the live failing name.
        assertTrue(contestedReregistrationCheckApplies("test-contested-1000"))
    }

    @Test
    fun `a non-contestable label is not queried at all`() {
        // Carries a 9, so it can never be in a vote poll; historic path unchanged.
        assertFalse(contestedReregistrationCheckApplies("test-contested-09"))
    }

    @Test
    fun `an over-long label is not contestable so it is not queried`() {
        // 20 chars — one past the 3..19 contestable window.
        assertFalse(contestedReregistrationCheckApplies("test-cointested-1000"))
    }

    @Test
    fun `a missing or blank label is never queried`() {
        assertFalse(contestedReregistrationCheckApplies(null))
        assertFalse(contestedReregistrationCheckApplies(""))
        assertFalse(contestedReregistrationCheckApplies("   "))
    }

    // ---- isOwnIdentityAContender -------------------------------------------

    @Test
    fun `our own contender document is detected so the duplicate is skipped`() {
        assertTrue(isOwnIdentityAContender(listOf(otherId, ownId), ownId))
    }

    @Test
    fun `a vote poll we are not in still registers`() {
        // Someone else contends for the name; this identity never requested it.
        assertFalse(isOwnIdentityAContender(listOf(otherId), ownId))
    }

    @Test
    fun `an empty contender set still registers`() {
        // The genuine fresh-create case: nothing on chain yet.
        assertFalse(isOwnIdentityAContender(emptyList(), ownId))
    }

    @Test
    fun `the sole contender being us is detected`() {
        // The live case: uncontested-so-far vote poll with only our document.
        assertTrue(isOwnIdentityAContender(listOf(ownId), ownId))
    }
}
