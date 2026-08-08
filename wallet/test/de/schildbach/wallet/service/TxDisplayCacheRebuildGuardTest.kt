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

package de.schildbach.wallet.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for [dashjRebuildWouldEraseHistory] — the pure core of
 * [TxDisplayCacheService.forceRebuildTransactionCache]'s refusal.
 *
 * Post-cutover the dashj wallet is HELD with zero transactions while the SDK
 * feeds both caches, so a "refresh" that wipes them and re-populates from that
 * wallet erases the user's entire visible history for good.
 */
class TxDisplayCacheRebuildGuardTest {

    @Test
    fun postCutoverRebuildOnAHeldDashjWalletIsRefused() {
        assertTrue(dashjRebuildWouldEraseHistory(cutoverCommitted = true, dashjTxCount = 0))
    }

    @Test
    fun preCutoverRebuildIsAllowedEvenWithAnEmptyWallet() {
        // Nothing to erase and nothing to rebuild — but the refusal must be
        // scoped to the cutover, not to emptiness, or a fresh pre-cutover
        // wallet would stop rebuilding as it syncs.
        assertFalse(dashjRebuildWouldEraseHistory(cutoverCommitted = false, dashjTxCount = 0))
    }

    @Test
    fun postCutoverRebuildIsAllowedWhileDashjStillHoldsTransactions() {
        // The window between the cutover commit and the dashj wallet being
        // emptied: dashj can still rebuild what it holds, so the refusal must
        // not rest on the cutover flag alone.
        assertFalse(dashjRebuildWouldEraseHistory(cutoverCommitted = true, dashjTxCount = 28_291))
    }

    @Test
    fun preCutoverRebuildIsAllowedWithAPopulatedWallet() {
        assertFalse(dashjRebuildWouldEraseHistory(cutoverCommitted = false, dashjTxCount = 12))
    }
}
