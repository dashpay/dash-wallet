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

import org.dashj.platform.dpp.errors.concensus.basic.identity.InvalidInstantAssetLockProofException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host-JVM tests for [identityRetryStatusHint] — the classifier that maps
 * the two KNOWN transient identity-registration failure shapes (live
 * incident, testnet S21) to home-tile status copy. Unknown errors must
 * classify to null: an unrecognized failure never changes the hint.
 */
class IdentityRetryStatusHintTest {

    // ── CORE_HEIGHT_LAG ─────────────────────────────────────────────────────

    @Test
    fun consensusCoreHeightMessage_classifiesAsCoreHeightLag() {
        // Verbatim from the live incident log.
        val t = Exception(
            "Protocol error: Asset Lock proof core chain height 1515032 is higher than " +
                "the current consensus core height 1515031"
        )
        assertEquals(RetryStatusHint.CORE_HEIGHT_LAG, identityRetryStatusHint(t))
    }

    @Test
    fun consensusCoreHeightMessage_wrappedAsACause_isStillClassified() {
        val t = RuntimeException(
            "state transition broadcast failed",
            IllegalStateException(
                "Asset Lock proof core chain height 1515032 is higher than the current " +
                    "consensus core height 1515031"
            )
        )
        assertEquals(RetryStatusHint.CORE_HEIGHT_LAG, identityRetryStatusHint(t))
    }

    // ── WAITING_FOR_ISLOCK ──────────────────────────────────────────────────

    @Test
    fun invalidInstantAssetLockProofException_classifiesAsWaitingForIsLock() {
        // dashj throws exactly this when the funding tx has no IS lock yet
        // (message renders as "Invalid instant lock proof: instantLock == null").
        val t = InvalidInstantAssetLockProofException("instantLock == null")
        assertEquals(RetryStatusHint.WAITING_FOR_ISLOCK, identityRetryStatusHint(t))
    }

    @Test
    fun nullInstantLockMessage_onAForeignExceptionType_isStillClassified() {
        val t = Exception("Invalid instant lock proof: instantLock == null")
        assertEquals(RetryStatusHint.WAITING_FOR_ISLOCK, identityRetryStatusHint(t))
    }

    @Test
    fun invalidInstantAssetLockProofException_asACause_isStillClassified() {
        val t = RuntimeException(
            "registerIdentity failed",
            InvalidInstantAssetLockProofException("instantLock == null")
        )
        assertEquals(RetryStatusHint.WAITING_FOR_ISLOCK, identityRetryStatusHint(t))
    }

    // ── Unknown errors: no hint ─────────────────────────────────────────────

    @Test
    fun unknownError_classifiesToNull() {
        assertNull(identityRetryStatusHint(Exception("Identity creation failed")))
    }

    @Test
    fun nullMessage_classifiesToNull() {
        assertNull(identityRetryStatusHint(RuntimeException(null as String?)))
    }

    @Test
    fun unrelatedKnownLookingError_classifiesToNull() {
        // Similar-sounding but distinct dashj error shapes must not match.
        assertNull(
            identityRetryStatusHint(
                Exception("Asset Lock transaction abcd1234 is not found")
            )
        )
    }

    @Test
    fun selfReferencingCauseChain_terminates() {
        // Defensive: cyclic cause chains must not spin the classifier.
        val a = RuntimeException("outer")
        val b = RuntimeException("inner", a)
        a.initCause(b)
        assertNull(identityRetryStatusHint(a))
    }
}
