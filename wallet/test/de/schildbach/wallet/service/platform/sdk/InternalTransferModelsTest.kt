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

    @Test
    fun `the full 4x4 matrix - every pair of two different balances is supported`() {
        for (from in SendBalance.entries) {
            for (to in SendBalance.entries) {
                val expected = if (from == to) InternalTransferSupport.SAME_BALANCE else InternalTransferSupport.SUPPORTED
                assertEquals("$from → $to", expected, internalTransferSupport(from, to, true, true))
            }
        }
        assertEquals(InternalTransferSupport.SUPPORTED, internalTransferSupport(IDENTITY, SHIELDED, true, true))
    }

    @Test
    fun `shielded off disables every shielded pair, unknown identity every identity pair`() {
        assertEquals(InternalTransferSupport.SHIELDED_OFF, internalTransferSupport(CORE, SHIELDED, false, true))
        assertEquals(InternalTransferSupport.SHIELDED_OFF, internalTransferSupport(SHIELDED, IDENTITY, false, true))
        assertEquals(InternalTransferSupport.NO_IDENTITY, internalTransferSupport(CORE, IDENTITY, true, false))
        assertEquals(InternalTransferSupport.NO_IDENTITY, internalTransferSupport(IDENTITY, PLATFORM, true, false))
        assertEquals(InternalTransferSupport.SUPPORTED, internalTransferSupport(IDENTITY, CORE, false, true))
        // Identity → Shielded needs both.
        assertEquals(InternalTransferSupport.SHIELDED_OFF, internalTransferSupport(IDENTITY, SHIELDED, false, true))
        assertEquals(InternalTransferSupport.NO_IDENTITY, internalTransferSupport(IDENTITY, SHIELDED, true, false))
        // Platform → Core and Core → Platform need neither the shielded feature nor an identity.
        assertEquals(InternalTransferSupport.SUPPORTED, internalTransferSupport(CORE, PLATFORM, false, false))
        assertEquals(InternalTransferSupport.SUPPORTED, internalTransferSupport(PLATFORM, CORE, false, false))
    }

    @Test
    fun `identity is listed only for a user known to have one`() {
        assertEquals(listOf(CORE, SHIELDED, PLATFORM, IDENTITY), pickerBalances(true))
        assertEquals(listOf(CORE, SHIELDED, PLATFORM), pickerBalances(false))
        assertEquals(listOf(CORE, SHIELDED, PLATFORM), pickerBalances(null))
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
        assertTrue(hasShieldedLeg(SHIELDED, CORE))
        assertTrue(hasShieldedLeg(PLATFORM, SHIELDED))
        assertFalse(hasShieldedLeg(IDENTITY, PLATFORM))
    }
}
