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

class Bech32PaymentIntentParserTest {
    private val parser = RunePaymentIntentProcessor() // RUNE / thor: / THOR.RUNE
    private val address = "thor166n4w5039meulfa3p6ydg60ve6ueac7tlt0jws"

    /** The swap memo carried in the intent's OP_RETURN output: `=:ASSET:destinationAddress`. */
    private fun memoOf(intent: PaymentIntent): String {
        val script = intent.outputs!![0].scriptData
        // Layout per Scripts.opReturnScript: OP_RETURN + single-byte length push + memo
        // bytes (every memo in these tests is well under the 76-byte direct-push cap).
        return String(script.copyOfRange(2, script.size))
    }

    @Test
    fun bareAddress_buildsMemo() = runBlocking {
        assertEquals("=:THOR.RUNE:$address", memoOf(parser.parse(address)))
    }

    @Test
    fun uriInput_stripsSchemeIncludingColon() = runBlocking {
        assertEquals("=:THOR.RUNE:$address", memoOf(parser.parse("thor:$address")))
        assertEquals("=:THOR.RUNE:$address", memoOf(parser.parse("THOR:$address")))
        assertEquals("=:THOR.RUNE:$address", memoOf(parser.parse("RUNE:$address")))
    }

    @Test
    fun uppercaseAddress_normalizedToCanonicalLowercase() = runBlocking {
        assertEquals("=:THOR.RUNE:$address", memoOf(parser.parse(address.uppercase())))
        assertEquals("=:THOR.RUNE:$address", memoOf(parser.parse("THOR:${address.uppercase()}")))
    }

    @Test
    fun uriWithInvalidPayload_rejected() {
        // A valid scheme prefix must not bypass address validation.
        assertThrows(PaymentIntentParserException::class.java) {
            runBlocking { parser.parse("thor:notAnAddress") }
        }
        assertThrows(PaymentIntentParserException::class.java) {
            runBlocking { parser.parse("RUNE:$address-junk") }
        }
        // Mixed case is invalid per BIP-173 even after a valid prefix.
        assertThrows(PaymentIntentParserException::class.java) {
            runBlocking { parser.parse("thor:thor166N4W5039MEULFA3P6YDG60VE6UEAC7TLT0JWS") }
        }
    }
}
