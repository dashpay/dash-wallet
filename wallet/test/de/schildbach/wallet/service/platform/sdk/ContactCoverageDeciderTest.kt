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

package de.schildbach.wallet.service.platform.sdk

import de.schildbach.wallet.service.platform.sdk.ContactCoverageDecider.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The §17 uncovered-contact-chain rule.
 *
 * The case that motivated the rewrite is [liveHeightAloneGivesOppositeVerdictsForIdenticalCoverage]:
 * on emulator-5556, 2026-09-18, one wallet reported OK at bind and DEBT
 * after a full rescan from genesis, with the same contacts and the same
 * coverage. Only the cursor had moved.
 */
class ContactCoverageDeciderTest {

    private val floor = 1_534_919L
    private val tip = 1_556_528L

    @Test
    fun `accounts registered above the contact floor are a debt`() {
        assertEquals(
            Verdict.DEBT,
            ContactCoverageDecider.decide(syncedHeight = tip, floor = floor, registeredAt = tip)
        )
    }

    @Test
    fun `accounts registered below the contact floor are covered`() {
        // The ordering the SDK bring-up is designed to produce: receival
        // accounts registered before startSpv, so the scan that follows
        // covers them from its first block.
        assertEquals(
            Verdict.COVERED,
            ContactCoverageDecider.decide(syncedHeight = tip, floor = floor, registeredAt = 0L)
        )
    }

    @Test
    fun `registration exactly at the floor is covered, not a debt`() {
        assertEquals(
            Verdict.COVERED,
            ContactCoverageDecider.decide(syncedHeight = tip, floor = floor, registeredAt = floor)
        )
    }

    @Test
    fun `a scan still below the floor is covered by the sweep ahead`() {
        assertEquals(
            Verdict.SWEEP_AHEAD_COVERS,
            ContactCoverageDecider.decide(syncedHeight = 0L, floor = floor, registeredAt = tip)
        )
    }

    @Test
    fun `no registration on record refuses to guess`() {
        // A wallet upgraded from a build that did not record the ordering.
        // The live height is above the floor, which the old comparison read
        // as debt; it is in fact no evidence at all.
        assertEquals(
            Verdict.NOT_DETERMINABLE,
            ContactCoverageDecider.decide(syncedHeight = tip, floor = floor, registeredAt = null)
        )
    }

    @Test
    fun `unknown synced height or no received contacts is not determinable`() {
        assertEquals(
            Verdict.NOT_DETERMINABLE,
            ContactCoverageDecider.decide(syncedHeight = null, floor = floor, registeredAt = 0L)
        )
        assertEquals(
            Verdict.NOT_DETERMINABLE,
            ContactCoverageDecider.decide(syncedHeight = tip, floor = null, registeredAt = 0L)
        )
    }

    /**
     * THE REGRESSION. Bind and post-rescan on one wallet whose coverage never
     * changed: the accounts were registered at scan height 0 (the rescan had
     * been armed to rewind the watermark), and the sweep then walked the whole
     * chain with them watched.
     *
     * The old rule was `syncedHeight > floor`, which is 0 > 1534919 = OK at
     * bind and 1556528 > 1534919 = DEBT at the end. Both readings describe
     * the same covered wallet, so at least one had to be wrong.
     */
    @Test
    fun liveHeightAloneGivesOppositeVerdictsForIdenticalCoverage() {
        val registeredAt = 0L

        val atBind = ContactCoverageDecider.decide(
            syncedHeight = 0L, floor = floor, registeredAt = registeredAt
        )
        val afterRescan = ContactCoverageDecider.decide(
            syncedHeight = tip, floor = floor, registeredAt = registeredAt
        )

        // Neither reading accuses a wallet whose chains were registered
        // before the scan reached them.
        assertEquals(Verdict.SWEEP_AHEAD_COVERS, atBind)
        assertEquals(Verdict.COVERED, afterRescan)

        // And the old rule's disagreement is gone: finishing a sync no
        // longer changes the verdict.
        assertEquals(
            afterRescan,
            ContactCoverageDecider.decide(
                syncedHeight = tip + 10_000, floor = floor, registeredAt = registeredAt
            )
        )
    }

    /**
     * The genuine §17 shape, which must still be caught: a wallet already
     * synced to tip that discovers a contact afterwards and registers its
     * receival account there.
     */
    @Test
    fun `a late registration on an already-synced wallet is still caught`() {
        assertEquals(
            Verdict.DEBT,
            ContactCoverageDecider.decide(
                syncedHeight = tip, floor = floor, registeredAt = tip - 1
            )
        )
    }
}
