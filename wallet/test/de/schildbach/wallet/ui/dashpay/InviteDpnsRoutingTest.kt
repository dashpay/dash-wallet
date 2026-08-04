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

import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.username.UsernameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the SDK invite-DPNS routing decisions in
 * [de.schildbach.wallet.ui.dashpay.CreateIdentityService.registerUsername],
 * pinned to the RESUMED-record shapes observed live on the S22 (11.10.50):
 * an SDK-claimed shielded invite with a contested primary + instant
 * secondary whose record resumed at PREORDER_SECONDARY_REGISTERING, took
 * the legacy dashj preorder path on the SECONDARY branch ("signer callback
 * returned 0"), and then crashed the next resume on an uninitialized
 * wrapper uniqueId. The routing must engage for EVERY username type, skip
 * only what is already registered, and resolve the identity id from
 * persisted state when the in-flight claim value is gone.
 */
class InviteDpnsRoutingTest {

    private val onChainId = "FhB6GWvVvrgB4Th4rCKQPUPCeeVsEXCwwqSqNYfXecEq"

    // ── State quartets: the mapping the state machine marches through ────

    @Test
    fun usernameRegistrationStates_mapPerType() {
        val primary = usernameRegistrationStates(UsernameType.Primary)
        assertEquals(IdentityCreationState.PREORDER_REGISTERING, primary.preorderRegistering)
        assertEquals(IdentityCreationState.PREORDER_REGISTERED, primary.preorderRegistered)
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, primary.domainRegistering)
        assertEquals(IdentityCreationState.USERNAME_REGISTERED, primary.domainRegistered)

        val secondary = usernameRegistrationStates(UsernameType.Secondary)
        assertEquals(IdentityCreationState.PREORDER_SECONDARY_REGISTERING, secondary.preorderRegistering)
        assertEquals(IdentityCreationState.PREORDER_SECONDARY_REGISTERED, secondary.preorderRegistered)
        assertEquals(IdentityCreationState.USERNAME_SECONDARY_REGISTERING, secondary.domainRegistering)
        assertEquals(IdentityCreationState.USERNAME_SECONDARY_REGISTERED, secondary.domainRegistered)
    }

    /**
     * The dual-name state machine runs the SECONDARY (instant) stages before
     * the PRIMARY stages — the per-name skip rule depends on this ordering,
     * so pin it against enum reordering.
     */
    @Test
    fun secondaryStages_orderedBeforePrimaryStages() {
        assertTrue(
            IdentityCreationState.USERNAME_SECONDARY_REGISTERED <
                IdentityCreationState.PREORDER_REGISTERING
        )
        assertTrue(
            IdentityCreationState.IDENTITY_REGISTERED <
                IdentityCreationState.PREORDER_SECONDARY_REGISTERING
        )
    }

    // ── Resumed-record shapes ────────────────────────────────────────────

    /**
     * THE S22 RECORD: resumed at PREORDER_SECONDARY_REGISTERING (with a
     * stamped error) — BOTH names still pending. The secondary must
     * register (this is the branch the primary-only scoping sent into the
     * legacy dashj signer), and the primary after it.
     */
    @Test
    fun resumedRecord_bothPending_registersBoth() {
        val state = IdentityCreationState.PREORDER_SECONDARY_REGISTERING
        assertTrue(sdkInviteDpnsShouldRegister(state, UsernameType.Secondary))
        assertTrue(sdkInviteDpnsShouldRegister(state, UsernameType.Primary))
    }

    /** Fresh claim: identity just registered, nothing preordered yet. */
    @Test
    fun freshClaim_identityRegistered_registersBoth() {
        val state = IdentityCreationState.IDENTITY_REGISTERED
        assertTrue(sdkInviteDpnsShouldRegister(state, UsernameType.Secondary))
        assertTrue(sdkInviteDpnsShouldRegister(state, UsernameType.Primary))
    }

    /**
     * Secondary landed, primary pending: every primary-stage resume state
     * must SKIP the secondary (no re-register, no state regression) and
     * still register the primary.
     */
    @Test
    fun resumedRecord_primaryPending_skipsSecondary_registersPrimary() {
        for (state in listOf(
            IdentityCreationState.USERNAME_SECONDARY_REGISTERED,
            IdentityCreationState.PREORDER_REGISTERING,
            IdentityCreationState.PREORDER_REGISTERED,
            IdentityCreationState.USERNAME_REGISTERING
        )) {
            assertFalse(
                "secondary must be skipped at $state",
                sdkInviteDpnsShouldRegister(state, UsernameType.Secondary)
            )
            assertTrue(
                "primary must register at $state",
                sdkInviteDpnsShouldRegister(state, UsernameType.Primary)
            )
        }
    }

    /**
     * Secondary pending mid-stage: a crash between the secondary's
     * registering and registered stamps resumes INSIDE the secondary
     * stages — it must re-register the secondary (idempotent retry), and
     * the primary stays due after it.
     */
    @Test
    fun resumedRecord_secondaryPendingMidStage_reRegistersSecondary() {
        for (state in listOf(
            IdentityCreationState.PREORDER_SECONDARY_REGISTERING,
            IdentityCreationState.PREORDER_SECONDARY_REGISTERED,
            IdentityCreationState.USERNAME_SECONDARY_REGISTERING
        )) {
            assertTrue(
                "secondary must register at $state",
                sdkInviteDpnsShouldRegister(state, UsernameType.Secondary)
            )
        }
    }

    /** Both registered (and every later stage): nothing re-registers. */
    @Test
    fun resumedRecord_bothRegistered_skipsBoth() {
        for (state in listOf(
            IdentityCreationState.USERNAME_REGISTERED,
            IdentityCreationState.DASHPAY_PROFILE_CREATING,
            IdentityCreationState.REQUESTED_NAME_CHECKED,
            IdentityCreationState.VOTING,
            IdentityCreationState.DONE
        )) {
            assertFalse(sdkInviteDpnsShouldRegister(state, UsernameType.Secondary))
            assertFalse(sdkInviteDpnsShouldRegister(state, UsernameType.Primary))
        }
    }

    // ── Identity-id resolution on resume ─────────────────────────────────

    @Test
    fun identityId_inFlightClaimWins_whenPresent() {
        assertEquals(
            "inflight",
            resolveSdkInviteIdentityId("inflight", onChainId, "wrapper")
        )
    }

    /**
     * THE RESUME CASE: after an interruption the in-flight claim id is gone
     * (it is process-local) — the persisted record's identityId is
     * authoritative, and no wrapper state is needed at all.
     */
    @Test
    fun identityId_persistedRecordWins_onResume() {
        assertEquals(onChainId, resolveSdkInviteIdentityId(null, onChainId, null))
        assertEquals(onChainId, resolveSdkInviteIdentityId(null, onChainId, "wrapper"))
    }

    @Test
    fun identityId_wrapperIsLastResort_andNullNeverCrashes() {
        assertEquals("wrapper", resolveSdkInviteIdentityId(null, null, "wrapper"))
        // All sources empty → null: the caller fails retryably, never with
        // an UninitializedPropertyAccessException.
        assertNull(resolveSdkInviteIdentityId(null, null, null))
    }
}
