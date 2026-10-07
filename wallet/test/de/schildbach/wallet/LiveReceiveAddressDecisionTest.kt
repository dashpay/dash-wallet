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

import java.util.function.BooleanSupplier
import java.util.function.Supplier
import org.bitcoinj.core.Address
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.services.ReceiveAddressUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-receive decision in [WalletApplication] — `currentReceiveAddressLive`
 * and `freshReceiveAddressLive`.
 *
 * Post-cutover the dashj chain is HELD, so both fallbacks ([WalletApplication
 * .currentReceiveAddress], [WalletApplication.freshReceiveAddress]) serve a
 * pointer frozen wherever the restore left it — the already-paid address SR-03
 * is about. The rule pinned here is therefore not just "throws on null" but that
 * the post-cutover arm never reaches the fallback at all, and that ownership is
 * consulted only when the engine had no answer (it is a potentially blocking
 * read, and the happy path must not pay for it).
 */
class LiveReceiveAddressDecisionTest {

    private val params = TestNet3Params.get()
    private val engine = Address.fromBase58(params, "ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U")
    private val frozenDashj = Address.fromBase58(params, "yM9uCfkYnDbBwfHiSSQ4sNDLiKQRhMTeYH")

    @Test
    fun engineAnswerIsServedWithoutConsultingOwnershipOrDashj() {
        var ownershipAsked = false
        var fallbackUsed = false

        val result = WalletApplication.decideLiveReceiveAddress(
            engine,
            BooleanSupplier { ownershipAsked = true; true },
            "current",
            Supplier { fallbackUsed = true; frozenDashj }
        )

        assertEquals(engine, result)
        assertFalse("the happy path must not pay for an ownership read", ownershipAsked)
        assertFalse(fallbackUsed)
    }

    @Test
    fun postCutover_noEngineAnswerThrowsAndNeverServesTheHeldChain() {
        var fallbackUsed = false
        val thrown = try {
            WalletApplication.decideLiveReceiveAddress(
                null,
                BooleanSupplier { true },
                "current",
                Supplier { fallbackUsed = true; frozenDashj }
            )
            false
        } catch (e: ReceiveAddressUnavailableException) {
            true
        }

        assertTrue("an unavailable engine address must fail closed", thrown)
        assertFalse(
            "failing must not touch the held dashj chain — that address is the defect",
            fallbackUsed
        )
    }

    @Test
    fun preCutover_noEngineAnswerFallsBackToDashj() {
        val result = WalletApplication.decideLiveReceiveAddress(
            null,
            BooleanSupplier { false },
            "fresh",
            Supplier { frozenDashj }
        )

        assertEquals(frozenDashj, result)
    }
}
