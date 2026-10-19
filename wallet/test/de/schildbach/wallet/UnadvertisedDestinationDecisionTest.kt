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

package de.schildbach.wallet

import org.bitcoinj.core.Address
import org.bitcoinj.params.TestNet3Params
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-transfer destination decision in [WalletApplication].
 *
 * This is the arm that shipped a defect once: the post-cutover fallback called
 * the OVERLAID `freshReceiveAddress()`, which on a warm cache returns the
 * advertised engine receive address — so a failing change-address read silently
 * paid the unshield to the address a payer had been handed. The rule pinned here
 * is therefore not just "throws on null" but that the post-cutover arm never
 * reaches the dashj supplier at all.
 */
class UnadvertisedDestinationDecisionTest {

    private val params = TestNet3Params.get()
    private val engineInternal = Address.fromBase58(params, "ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U")
    private val dashjFresh = Address.fromBase58(params, "yM9uCfkYnDbBwfHiSSQ4sNDLiKQRhMTeYH")

    @Test
    fun postCutover_servesTheEngineInternalAddress_withoutConsultingDashj() {
        var consulted = false
        val result = WalletApplication.decideUnadvertisedDestination(true, engineInternal) {
            consulted = true
            dashjFresh
        }
        assertEquals(engineInternal, result)
        assertFalse("the dashj fallback must not be consulted post-cutover", consulted)
    }

    @Test
    fun postCutover_unavailable_throwsAndNeverFallsBack() {
        var consulted = false
        val thrown = try {
            WalletApplication.decideUnadvertisedDestination(true, null) {
                consulted = true
                dashjFresh
            }
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue("an unavailable engine destination must fail, not fall back", thrown)
        assertFalse(
            "failing must not consult any receive fallback — that is how the leak shipped",
            consulted
        )
    }

    @Test
    fun preCutover_usesTheDashjFreshKey() {
        val result = WalletApplication.decideUnadvertisedDestination(false, null) { dashjFresh }
        assertEquals(dashjFresh, result)
    }
}
