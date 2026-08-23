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

package org.dash.wallet.integrations.maya.swapkit

import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.swapkit.model.SwapKitProviderError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapKitErrorsTest {

    @Test
    fun bareCodeAndCodeWithDetail_mapToTheSameMessage() {
        assertEquals(R.string.dex_error_validation, SwapKitErrors.messageResFor("validation_error"))
        assertEquals(
            R.string.dex_error_validation,
            SwapKitErrors.messageResFor("validation_error: sellAmount must be a string")
        )
    }

    @Test
    fun unknownAndNullErrors_fallBackToGeneric() {
        assertEquals(R.string.dex_error_generic, SwapKitErrors.messageResFor(null))
        assertEquals(R.string.dex_error_generic, SwapKitErrors.messageResFor("somethingBrandNew"))
        // Prose with no leading code must not accidentally match a mapped code.
        assertEquals(
            R.string.dex_error_generic,
            SwapKitErrors.messageResFor("Sell asset amount too small for provider MAYACHAIN.")
        )
    }

    @Test
    fun sellAssetAmountTooSmall_isAnAmountTooLowErrorWithItsOwnMessage() {
        assertEquals(
            R.string.dex_error_amount_too_small,
            SwapKitErrors.messageResFor("sellAssetAmountTooSmall")
        )
        assertTrue(SwapKitErrors.isAmountTooLow("sellAssetAmountTooSmall"))
        assertTrue(
            SwapKitErrors.isAmountTooLow("sellAssetAmountTooSmall: Sell asset amount too small for provider MAYACHAIN.")
        )
    }

    /**
     * SwapKit doesn't enumerate the per-provider vocabulary, so below-minimum codes are
     * family-matched by suffix: any side/field prefix counts, and `AmountTooLow` reads the same
     * as `AmountTooSmall`.
     */
    @Test
    fun belowMinimumFamily_isMatchedBySuffixNotEnumerated() {
        assertTrue(SwapKitErrors.isAmountTooLow("buyAssetAmountTooSmall"))
        assertTrue(SwapKitErrors.isAmountTooLow("sellAmountTooLow: below the route minimum"))
        assertEquals(
            R.string.dex_error_amount_too_small,
            SwapKitErrors.messageResFor("buyAssetAmountTooSmall")
        )
        // "Amount" is required in the code, so an unrelated below-threshold code stays out.
        assertFalse(SwapKitErrors.isAmountTooLow("inboundFeeTooLow"))
    }

    @Test
    fun noRoutesFound_staysAnAmountTooLowError() {
        assertEquals(R.string.dex_error_no_route, SwapKitErrors.messageResFor("noRoutesFound"))
        assertTrue(SwapKitErrors.isAmountTooLow("noRoutesFound"))
    }

    @Test
    fun otherErrors_areNotAmountTooLow() {
        assertFalse(SwapKitErrors.isAmountTooLow(null))
        assertFalse(SwapKitErrors.isAmountTooLow(""))
        assertFalse(SwapKitErrors.isAmountTooLow("isSanctionedAddress"))
        // The prose alone must not be treated as too-low; only the code counts.
        assertFalse(SwapKitErrors.isAmountTooLow("Sell asset amount too small for provider MAYACHAIN."))
    }

    /**
     * The regression this guards: the aggregator used to pass a provider error's prose `message`,
     * which never matches a code, so a below-minimum amount showed the generic "something went
     * wrong" modal instead of the inline "enter a larger amount" hint.
     */
    @Test
    fun providerErrorMessage_leadsWithTheCodeSoTheMappingMatches() {
        val error = SwapKitProviderError(
            provider = "MAYACHAIN_STREAMING",
            errorCode = "sellAssetAmountTooSmall",
            message = "Sell asset amount too small for provider MAYACHAIN."
        )
        val rendered = SwapKitErrors.providerErrorMessage(error)

        assertEquals(
            "sellAssetAmountTooSmall: Sell asset amount too small for provider MAYACHAIN.",
            rendered
        )
        assertEquals(R.string.dex_error_amount_too_small, SwapKitErrors.messageResFor(rendered))
        assertTrue(SwapKitErrors.isAmountTooLow(rendered))
    }

    @Test
    fun providerErrorMessage_handlesPartialAndAbsentErrors() {
        assertNull(SwapKitErrors.providerErrorMessage(null))
        assertNull(SwapKitErrors.providerErrorMessage(SwapKitProviderError()))
        assertEquals(
            "noRoutesFound",
            SwapKitErrors.providerErrorMessage(SwapKitProviderError(errorCode = "noRoutesFound"))
        )
        assertEquals(
            "No route available",
            SwapKitErrors.providerErrorMessage(SwapKitProviderError(message = "No route available"))
        )
    }
}
