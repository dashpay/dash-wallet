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
package de.schildbach.wallet.ui.send

import de.schildbach.wallet.payments.SendEngineNotSyncedException
import de.schildbach.wallet.payments.SendNotSdkRoutableException
import de.schildbach.wallet.payments.SendSignerLockedException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [classifySendFailure] — the failure-dialog mapping behind
 * [SendCoinsFragment.showFailureDialog]. The on-device bug (S21, 11.10.44):
 * a fully-synced self-send failed for ROUTABILITY, but the dialog blamed
 * syncing ("wallet is not fully synced") because EVERY IllegalStateException
 * was mapped to the not-synced hint. The mapping must be type-precise.
 */
class SendFailureClassificationTest {

    @Test
    fun engineNotSynced_mapsToTheNotSyncedHint() {
        assertEquals(
            SendFailureKind.NOT_SYNCED,
            classifySendFailure(SendEngineNotSyncedException("L1 funding gate closed: scan behind tip"))
        )
    }

    @Test
    fun notSdkRoutable_mapsToNotSupported_neverNotSynced() {
        // The 11.10.44 misdiagnosis, pinned: a routability failure must get
        // its own honest copy, not the sync excuse.
        assertEquals(
            SendFailureKind.NOT_SUPPORTED,
            classifySendFailure(SendNotSdkRoutableException("cutover committed: this send is not SDK-routable"))
        )
    }

    @Test
    fun signerLocked_mapsToItsOwnUnlockCopy_notGenericInternal() {
        // SendSignerLockedException IS an IllegalStateException — the
        // type-precise arm must win over the generic-internal fallthrough so
        // a locked Keystore reads as "unlock and try again", never as a hard
        // payment failure (dashpay/platform#4256).
        assertEquals(
            SendFailureKind.SIGNER_LOCKED,
            classifySendFailure(SendSignerLockedException("SDK signer locked — retry after unlock"))
        )
    }

    @Test
    fun anyOtherIllegalState_isGenericInternal_notNotSyncedAndNeverVerbatim() {
        // Internal machinery text must neither be blamed on syncing nor
        // shown verbatim to the user.
        assertEquals(
            SendFailureKind.GENERIC_INTERNAL,
            classifySendFailure(IllegalStateException("some internal invariant broke"))
        )
    }

    @Test
    fun nonIllegalState_keepsTheOriginalVerbatimRendering() {
        assertEquals(
            SendFailureKind.VERBATIM,
            classifySendFailure(RuntimeException("dashj-path failure text"))
        )
    }
}
