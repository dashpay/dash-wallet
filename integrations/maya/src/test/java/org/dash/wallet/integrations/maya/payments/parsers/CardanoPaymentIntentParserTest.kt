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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dash.wallet.integrations.maya.payments.parsers

import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.payments.parsers.PaymentIntentParserException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CardanoPaymentIntentParserTest {
    private val parser = CardanoPaymentIntentParser()
    private val byron = "Ae2tdPwUPEZ4YjgvykNpoFeYUxoyhNj2kg8KfKWN2FizsSpLUPv68MpTVDo"

    /** The swap memo carried in the intent's OP_RETURN output: `=:ASSET:destinationAddress`. */
    private fun memoOf(intent: PaymentIntent): String {
        val chunks = intent.outputs!![0].script.chunks
        return String(chunks[1].data!!)
    }

    @Test
    fun uriInput_preservesByronCase() = runBlocking {
        assertEquals("=:ADA.ADA:$byron", memoOf(parser.parse("cardano:$byron")))
        assertEquals("=:ADA.ADA:$byron", memoOf(parser.parse("CARDANO:$byron")))
    }

    @Test
    fun uriWithInvalidPayload_rejected() {
        // A valid scheme prefix must not bypass address validation.
        assertThrows(PaymentIntentParserException::class.java) {
            runBlocking { parser.parse("cardano:notAnAddress") }
        }
        // An uppercased Byron address is corrupt Base58 — rejected, not "repaired".
        assertThrows(PaymentIntentParserException::class.java) {
            runBlocking { parser.parse("cardano:${byron.uppercase()}") }
        }
    }
}
