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
import org.junit.Assert.assertEquals
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

    // ── The three-way decision (review finding on #1564) ────────────────────

    @Test
    fun action_unreadableVoteState_defersInsteadOfSkipping() {
        // The regression this pins: folding an unreadable read into "skip" protects the
        // 0.2 DASH prefund but only defers registration IF the name actually landed. If
        // it did not, nothing registers it, the contested walks find no contender, and
        // the worker falls through to the "missing domain document" branch, which clears
        // `restoring` and sends the user off to choose another name. A transient DAPI
        // failure would strand the restoration where no retry reaches it.
        assertEquals(
            ContestedReregistrationAction.DEFER_UNREADABLE,
            contestedReregistrationAction("test-contested-1000", null)
        )
    }

    @Test
    fun action_knownContender_skipsTheDuplicate() {
        assertEquals(
            ContestedReregistrationAction.SKIP_ALREADY_CONTENDING,
            contestedReregistrationAction("test-contested-1000", true)
        )
    }

    @Test
    fun action_knownNonContender_registers() {
        assertEquals(
            ContestedReregistrationAction.REGISTER,
            contestedReregistrationAction("test-contested-1000", false)
        )
    }

    @Test
    fun action_uncontestableLabel_registersWithoutConsultingTheVoteState() {
        // 20 characters — past the contestable ceiling, so no vote poll exists and a
        // failed read is irrelevant. It must not become a deferral loop.
        val uncontestable = "abcdefghijklmnopqrst"
        assertEquals(
            ContestedReregistrationAction.REGISTER,
            contestedReregistrationAction(uncontestable, null)
        )
        assertEquals(
            ContestedReregistrationAction.REGISTER,
            contestedReregistrationAction(uncontestable, false)
        )
    }

    @Test
    fun action_noRequestedLabel_registers() {
        // Nothing to guard; the historic name-less restore path still applies.
        assertEquals(ContestedReregistrationAction.REGISTER, contestedReregistrationAction(null, null))
        assertEquals(ContestedReregistrationAction.REGISTER, contestedReregistrationAction("", null))
        assertEquals(ContestedReregistrationAction.REGISTER, contestedReregistrationAction("   ", null))
    }

    @Test
    fun action_neverSkipsOnEvidenceItDoesNotHave() {
        // The two-sided bar, restated over the new tri-state: a skip must be backed by a
        // positive contender answer, and only a positive answer may produce one.
        val label = "test-contested-1000"
        val skipping = listOf<Boolean?>(null, true, false)
            .filter { contestedReregistrationAction(label, it) == ContestedReregistrationAction.SKIP_ALREADY_CONTENDING }
        assertEquals(listOf<Boolean?>(true), skipping)
    }

    // ── The fall-through after a confirmed contender (review finding on #1564) ──

    @Test
    fun missingName_afterAConfirmedContender_defersInsteadOfParking() {
        // The sibling of the unreadable-guard case. Here the guard SUCCEEDED and saw this
        // identity's contender document, so registration was correctly skipped. If the
        // recovery walks then fail to read the name back, that is a failed read, not an
        // absent name — getVoteContenders collapses an exception into an empty map, so the
        // walks cannot tell the two apart. Parking on that evidence clears `restoring`,
        // shows the name as unavailable and routes the user to pick a different one,
        // discarding a contested name whose 0.2 DASH prefund is already spent.
        assertEquals(
            MissingNameOutcome.DEFER_CONFIRMED_CONTENDER,
            missingNameOutcome(confirmedOwnContender = true)
        )
    }

    @Test
    fun missingName_withNoConfirmedContender_keepsTheHistoricAskForANewNamePath() {
        // A genuine device restore of a name-less identity: nothing was ever confirmed,
        // so asking the user for a new username remains right. This must NOT become a
        // deferral loop.
        assertEquals(
            MissingNameOutcome.PARK_ASK_FOR_NEW_NAME,
            missingNameOutcome(confirmedOwnContender = false)
        )
    }

    @Test
    fun missingName_parkingRequiresPositiveAbsenceEvidence() {
        // Restated as the invariant that matters: the destructive branch is reachable
        // only when nothing was confirmed.
        val parking = listOf(true, false)
            .filter { missingNameOutcome(it) == MissingNameOutcome.PARK_ASK_FOR_NEW_NAME }
        assertEquals(listOf(false), parking)
    }
}
