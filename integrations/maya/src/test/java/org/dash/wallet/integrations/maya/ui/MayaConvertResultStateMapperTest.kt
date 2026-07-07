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

package org.dash.wallet.integrations.maya.ui

import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.model.MayaResultType
import org.dash.wallet.integrations.maya.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MayaConvertResultStateMapperTest {

    private val sendToWalletError = "Problem transferring to your wallet"

    private fun buildSpec(
        type: TransactionType,
        isSuccess: Boolean,
        errorMessage: String? = null
    ) = MayaConvertResultStateMapper.buildResultSpec(
        type = type,
        isSuccess = isSuccess,
        errorMessage = errorMessage,
        sendToWalletError = sendToWalletError,
        conversionSource = "DASH",
        conversionDestination = "BTC"
    )

    // ── Success states ──────────────────────────────────────────────────────────

    @Test
    fun buyDashSuccess_isDepositSuccess() {
        val spec = buildSpec(TransactionType.BuyDash, isSuccess = true)

        assertEquals(MayaResultType.DEPOSIT_SUCCESS, spec.resultType)
        assertTrue(spec.isSuccess)
        assertEquals(R.string.purchase_successful, spec.titleRes)
        assertEquals(R.string.maya_it_could_take_up_to_2_3_minutes, spec.messageRes)
        assertNull(spec.messageText)
        assertFalse(spec.showContactSupport)
        assertEquals(R.string.button_close, spec.buttonTextRes)
    }

    @Test
    fun buySwapSuccess_isConversionSuccess_withSourceAndDestination() {
        val spec = buildSpec(TransactionType.BuySwap, isSuccess = true)

        assertEquals(MayaResultType.CONVERSION_SUCCESS, spec.resultType)
        assertEquals(R.string.conversion_successful, spec.titleRes)
        assertEquals(R.string.maya_it_could_take_up_to_5_minutes, spec.messageRes)
        assertEquals(listOf("DASH", "BTC"), spec.messageArgs)
        assertFalse(spec.showContactSupport)
        assertEquals(R.string.button_close, spec.buttonTextRes)
    }

    @Test
    fun sellSwapSuccess_isConversionSuccess() {
        val spec = buildSpec(TransactionType.SellSwap, isSuccess = true)

        assertEquals(MayaResultType.CONVERSION_SUCCESS, spec.resultType)
        assertEquals(R.string.conversion_successful, spec.titleRes)
    }

    @Test
    fun transferDashSuccess_isTransferSuccess() {
        val spec = buildSpec(TransactionType.TransferDash, isSuccess = true)

        assertEquals(MayaResultType.TRANSFER_DASH_SUCCESS, spec.resultType)
        assertEquals(R.string.transfer_dash_successful, spec.titleRes)
        assertEquals(R.string.maya_it_could_take_up_to_10_minutes, spec.messageRes)
        assertEquals(R.string.button_close, spec.buttonTextRes)
    }

    // ── Error states ────────────────────────────────────────────────────────────

    @Test
    fun buyDashError_withoutMessage_usesGenericCopy() {
        val spec = buildSpec(TransactionType.BuyDash, isSuccess = false, errorMessage = null)

        assertEquals(MayaResultType.DEPOSIT_ERROR, spec.resultType)
        assertFalse(spec.isSuccess)
        assertEquals(R.string.transfer_failed, spec.titleRes)
        assertEquals(R.string.transfer_failed_msg, spec.messageRes)
        assertNull(spec.messageText)
        assertTrue(spec.showContactSupport)
        assertEquals(R.string.button_retry, spec.buttonTextRes)
    }

    @Test
    fun buyDashError_withSendToWalletError_showsMessageVerbatim() {
        val message = "$sendToWalletError: not enough funds"
        val spec = buildSpec(TransactionType.BuyDash, isSuccess = false, errorMessage = message)

        assertEquals(message, spec.messageText)
    }

    @Test
    fun buyDashError_withUnrelatedMessage_usesGenericCopy() {
        val spec = buildSpec(TransactionType.BuyDash, isSuccess = false, errorMessage = "backend exploded")

        assertNull(spec.messageText)
        assertEquals(R.string.transfer_failed_msg, spec.messageRes)
    }

    @Test
    fun buySwapError_isTransferDashError_showingBackendMessage() {
        val spec = buildSpec(TransactionType.BuySwap, isSuccess = false, errorMessage = "route halted")

        assertEquals(MayaResultType.TRANSFER_DASH_ERROR, spec.resultType)
        assertEquals(R.string.transfer_failed, spec.titleRes)
        assertEquals("route halted", spec.messageText)
        assertTrue(spec.showContactSupport)
        assertEquals(R.string.button_retry, spec.buttonTextRes)
    }

    @Test
    fun transferDashError_withEmptyMessage_usesGenericCopy() {
        val spec = buildSpec(TransactionType.TransferDash, isSuccess = false, errorMessage = "")

        assertEquals(MayaResultType.TRANSFER_DASH_ERROR, spec.resultType)
        assertNull(spec.messageText)
        assertEquals(R.string.transfer_dash_failed_msg, spec.messageRes)
    }

    @Test
    fun sellSwapError_isSwapError_withConversionFailedTitle() {
        val spec = buildSpec(TransactionType.SellSwap, isSuccess = false, errorMessage = "no route")

        assertEquals(MayaResultType.SWAP_ERROR, spec.resultType)
        assertEquals(R.string.conversion_failed, spec.titleRes)
        assertEquals("no route", spec.messageText)
        assertTrue(spec.showContactSupport)
        assertEquals(R.string.button_retry, spec.buttonTextRes)
    }

    @Test
    fun sellSwapError_withEmptyMessage_usesGenericCopy() {
        val spec = buildSpec(TransactionType.SellSwap, isSuccess = false, errorMessage = "")

        assertNull(spec.messageText)
        assertEquals(R.string.transfer_failed_msg, spec.messageRes)
    }

    // ── Explorer link classification ───────────────────────────────────────────

    @Test
    fun explorer_emptyOrNullRoute_isMaya() {
        val fromNull = MayaConvertResultStateMapper.explorerFor(null, null, null)
        val fromBlank = MayaConvertResultStateMapper.explorerFor("  ", null, null)

        assertEquals(R.string.maya_explorer_description_maya, fromNull?.descriptionRes)
        assertEquals(R.string.maya_explorer_description_maya, fromBlank?.descriptionRes)
    }

    @Test
    fun explorer_mayaRoute_withTxid_linksToUppercasedTx() {
        val spec = MayaConvertResultStateMapper.explorerFor("MAYAChain", "abc123def", null)

        assertEquals(R.string.maya_explorer_view_maya, spec?.linkTextRes)
        assertEquals(R.string.maya_explorer_tx_url_maya, spec?.urlRes)
        assertEquals("ABC123DEF", spec?.urlArg)
    }

    @Test
    fun explorer_mayaRoute_withoutTxid_fallsBackToHomePage() {
        val spec = MayaConvertResultStateMapper.explorerFor("maya", "  ", null)

        assertEquals(R.string.maya_explorer_url_maya, spec?.urlRes)
        assertNull(spec?.urlArg)
    }

    @Test
    fun explorer_nearRoute_withDepositAddress_linksToTransaction() {
        val spec = MayaConvertResultStateMapper.explorerFor("NEAR Intents", "ignored-txid", "deposit.near")

        assertEquals(R.string.maya_explorer_description_near, spec?.descriptionRes)
        assertEquals(R.string.maya_explorer_view_near, spec?.linkTextRes)
        assertEquals(R.string.maya_explorer_tx_url_near, spec?.urlRes)
        assertEquals("deposit.near", spec?.urlArg)
    }

    @Test
    fun explorer_nearRoute_withoutDepositAddress_fallsBackToHomePage() {
        val spec = MayaConvertResultStateMapper.explorerFor("near-intents", null, "")

        assertEquals(R.string.maya_explorer_url_near, spec?.urlRes)
        assertNull(spec?.urlArg)
    }

    @Test
    fun explorer_routeContainingMayaWins_overNear() {
        // Classification mirrors the order preview: MAYA is checked first.
        val spec = MayaConvertResultStateMapper.explorerFor("MAYA via NEAR", "tx", "addr")

        assertEquals(R.string.maya_explorer_description_maya, spec?.descriptionRes)
    }

    @Test
    fun explorer_unknownRoute_showsNothing() {
        assertNull(MayaConvertResultStateMapper.explorerFor("THORChain", "tx", "addr"))
    }
}
