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
package de.schildbach.wallet.ui.invite

import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the confirm dialog's invite-creation failure
 * classifier ([classifyInviteCreationFailure]) and retry gate
 * ([inviteRetryAllowed]) — host-JVM, no Android/native deps.
 *
 * What this pins: the confirm → authorize → create cycle must always
 * TERMINATE. Observed live: a contested 0.25 invite was rejected by the
 * SDK's 0.05 invitation-amount cap on every attempt ("Invalid identity
 * data: invitation amount 25000000 exceeds the cap 5000000 duffs"), the
 * dialog collapsed it into a generic error with the buttons re-enabled,
 * and every retry re-ran the full confirm/authorize cycle just to fail
 * identically — an unbounded loop with no way to tell why.
 */
class InviteCreationFailureTest {

    // ── The observed amount-cap rejection (deterministic → never retry) ──

    @Test
    fun amountCapRejection_isRejected_andBlocksRetryImmediately() {
        // The exact live shape: classifyBroadcastFailure matched the FFI's
        // "Invalid identity data" message and produced this NotBroadcast.
        val result = SdkWriteResult.NotBroadcast(
            "pre-broadcast identity-key validation failure",
            RuntimeException(
                "Invalid identity data: invitation amount 25000000 exceeds the cap 5000000 duffs"
            )
        )
        val kind = classifyInviteCreationFailure(result)
        assertEquals(InviteCreationFailureKind.REJECTED, kind)
        // Deterministic: retrying reruns the same validation with the same
        // inputs — blocked from the very first failure.
        assertFalse(inviteRetryAllowed(kind, failedAttempts = 1))
    }

    @Test
    fun typedPreBroadcastValidation_isRejected() {
        // classifyBroadcastFailure's typed-error reason shape
        // ("pre-broadcast validation failure: InvalidParameter").
        val kind = classifyInviteCreationFailure(
            SdkWriteResult.NotBroadcast("pre-broadcast validation failure: InvalidParameter")
        )
        assertEquals(InviteCreationFailureKind.REJECTED, kind)
    }

    // ── Insufficient funds (actionable, bounded retry) ───────────────────

    @Test
    fun insufficientFunds_isClassified_fromReasonOrCauseChain() {
        // classifyBroadcastFailure's own reason string…
        assertEquals(
            InviteCreationFailureKind.INSUFFICIENT_FUNDS,
            classifyInviteCreationFailure(
                SdkWriteResult.NotBroadcast(
                    "pre-broadcast build failure (insufficient funds / coin selection)"
                )
            )
        )
        // …and a generic reason whose CAUSE chain carries the FFI message.
        assertEquals(
            InviteCreationFailureKind.INSUFFICIENT_FUNDS,
            classifyInviteCreationFailure(
                SdkWriteResult.NotBroadcast(
                    "funding failed",
                    RuntimeException(
                        "wrapper",
                        RuntimeException("Coin selection error: Insufficient funds: available 1449, required 25000000")
                    )
                )
            )
        )
    }

    @Test
    fun insufficientFunds_allowsBoundedRetry() {
        val kind = InviteCreationFailureKind.INSUFFICIENT_FUNDS
        assertTrue(inviteRetryAllowed(kind, failedAttempts = 1))
        assertTrue(inviteRetryAllowed(kind, failedAttempts = MAX_INVITE_CREATE_ATTEMPTS - 1))
        assertFalse(inviteRetryAllowed(kind, failedAttempts = MAX_INVITE_CREATE_ATTEMPTS))
    }

    // ── Ambiguous (may have landed → never retry) ────────────────────────

    @Test
    fun ambiguousOutcome_isPossiblyCreated_andNeverRetried() {
        // The no-double-broadcast contract: a second attempt could fund a
        // duplicate voucher. Blocked from the very first failure.
        val kind = classifyInviteCreationFailure(
            SdkWriteResult.Ambiguous(RuntimeException("timeout"))
        )
        assertEquals(InviteCreationFailureKind.POSSIBLY_CREATED, kind)
        assertFalse(inviteRetryAllowed(kind, failedAttempts = 1))
    }

    // ── Everything else (transient → bounded retry) ──────────────────────

    @Test
    fun unknownFailures_areUnreachable_withABoundedRetryBudget() {
        for (reason in listOf(
            "SDK bootstrap/bind lookup failed",
            "app wallet not bound to the SDK",
            "cutover state read failed",
            "signing failure (pre-broadcast): Keystore auth window expired"
        )) {
            val kind = classifyInviteCreationFailure(SdkWriteResult.NotBroadcast(reason))
            assertEquals(reason, InviteCreationFailureKind.UNREACHABLE, kind)
            assertTrue(reason, inviteRetryAllowed(kind, failedAttempts = 1))
            assertFalse(reason, inviteRetryAllowed(kind, failedAttempts = MAX_INVITE_CREATE_ATTEMPTS))
        }
    }

    @Test
    fun thisSessionImpossibilities_areRejected_notEndlesslyRetried() {
        // Gates that cannot change within the dialog's lifetime: retrying
        // burns auth prompts for a guaranteed-identical failure.
        for (reason in listOf("flag off", "cutover not committed", "no local user profile")) {
            assertEquals(
                reason,
                InviteCreationFailureKind.REJECTED,
                classifyInviteCreationFailure(SdkWriteResult.NotBroadcast(reason))
            )
        }
    }
}
