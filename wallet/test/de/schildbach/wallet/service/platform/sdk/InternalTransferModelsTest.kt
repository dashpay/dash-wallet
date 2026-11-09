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

import de.schildbach.wallet.service.platform.sdk.SendBalance.CORE
import de.schildbach.wallet.service.platform.sdk.SendBalance.IDENTITY
import de.schildbach.wallet.service.platform.sdk.SendBalance.PLATFORM
import de.schildbach.wallet.service.platform.sdk.SendBalance.SHIELDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalTransferModelsTest {

    private val supported = setOf(
        CORE to SHIELDED, CORE to IDENTITY,
        SHIELDED to CORE, SHIELDED to PLATFORM, SHIELDED to IDENTITY,
        PLATFORM to CORE, PLATFORM to SHIELDED, PLATFORM to IDENTITY,
        IDENTITY to CORE, IDENTITY to PLATFORM
    )

    @Test
    fun `the full 4x4 matrix`() {
        for (from in SendBalance.entries) {
            for (to in SendBalance.entries) {
                val expected = when {
                    from == to -> InternalTransferSupport.SAME_BALANCE
                    (from to to) in supported -> InternalTransferSupport.SUPPORTED
                    else -> InternalTransferSupport.NOT_SUPPORTED_YET
                }
                assertEquals("$from → $to", expected, internalTransferSupport(from, to, true, true))
                assertEquals("$from → $to", (from to to) in supported, internalTransferPairSupported(from, to))
            }
        }
        // Exactly the two pairs the SDK cannot do yet are disabled.
        val notYet = SendBalance.entries.flatMap { f -> SendBalance.entries.map { f to it } }
            .filter { internalTransferSupport(it.first, it.second, true, true) == InternalTransferSupport.NOT_SUPPORTED_YET }
            .toSet()
        assertEquals(setOf(CORE to PLATFORM, IDENTITY to SHIELDED), notYet)
    }

    @Test
    fun `shielded off disables every shielded pair, unknown identity every identity pair`() {
        assertEquals(InternalTransferSupport.SHIELDED_OFF, internalTransferSupport(CORE, SHIELDED, false, true))
        assertEquals(InternalTransferSupport.SHIELDED_OFF, internalTransferSupport(SHIELDED, IDENTITY, false, true))
        assertEquals(InternalTransferSupport.NO_IDENTITY, internalTransferSupport(CORE, IDENTITY, true, false))
        assertEquals(InternalTransferSupport.NO_IDENTITY, internalTransferSupport(IDENTITY, PLATFORM, true, false))
        assertEquals(InternalTransferSupport.SUPPORTED, internalTransferSupport(IDENTITY, CORE, false, true))
        // The matrix wins over the flags: an unsupported pair is "not yet", whatever they say.
        assertEquals(InternalTransferSupport.NOT_SUPPORTED_YET, internalTransferSupport(CORE, PLATFORM, false, false))
        // Platform → Core needs neither the shielded feature nor an identity.
        assertEquals(InternalTransferSupport.SUPPORTED, internalTransferSupport(PLATFORM, CORE, false, false))
    }

    @Test
    fun `identity is listed only for a user known to have one`() {
        assertEquals(listOf(CORE, SHIELDED, PLATFORM, IDENTITY), internalTransferBalances(true))
        assertEquals(listOf(CORE, SHIELDED, PLATFORM), internalTransferBalances(false))
        assertEquals(listOf(CORE, SHIELDED, PLATFORM), internalTransferBalances(null))
    }

    @Test
    fun `swap exchanges From and To (AC3)`() {
        assertEquals(InternalTransferPair(SHIELDED, CORE), InternalTransferPair(CORE, SHIELDED).swapped())
        // The default with no shielded feature and no identity swaps to the one supported pair left.
        val swapped = defaultInternalTransferPair(false, false).swapped()
        assertEquals(InternalTransferPair(PLATFORM, CORE), swapped)
        assertEquals(InternalTransferSupport.SUPPORTED, internalTransferSupport(swapped.from, swapped.to, false, false))
    }

    @Test
    fun `picking the other side's balance swaps (AC4)`() {
        val pair = InternalTransferPair(CORE, SHIELDED)
        assertEquals(InternalTransferPair(SHIELDED, CORE), pair.withFrom(SHIELDED))
        assertEquals(InternalTransferPair(SHIELDED, CORE), pair.withTo(CORE))
        assertEquals(InternalTransferPair(PLATFORM, SHIELDED), pair.withFrom(PLATFORM))
        assertEquals(InternalTransferPair(CORE, IDENTITY), pair.withTo(IDENTITY))
        assertEquals(pair, pair.withFrom(CORE))
        assertEquals(pair, pair.withTo(SHIELDED))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `From and To are never the same`() {
        InternalTransferPair(CORE, CORE)
    }

    @Test
    fun `default pair`() {
        assertEquals(InternalTransferPair(CORE, SHIELDED), defaultInternalTransferPair(true, true))
        assertEquals(InternalTransferPair(CORE, IDENTITY), defaultInternalTransferPair(false, true))
        assertEquals(InternalTransferPair(CORE, PLATFORM), defaultInternalTransferPair(false, false))
    }

    @Test
    fun `shielded leg`() {
        assertTrue(internalTransferHasShieldedLeg(SHIELDED, CORE))
        assertTrue(internalTransferHasShieldedLeg(PLATFORM, SHIELDED))
        assertFalse(internalTransferHasShieldedLeg(IDENTITY, PLATFORM))
    }
}
