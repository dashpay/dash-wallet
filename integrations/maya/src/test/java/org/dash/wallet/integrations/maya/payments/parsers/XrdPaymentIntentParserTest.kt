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

class XrdPaymentIntentParserTest {
    private val parser = XrdPaymentIntentParser()

    // Pattern-valid fixture (the XRD parser has no checksum validation): account_rdx1 + 54
    // bech32-alphabet chars, within the parser's 50-65 range.
    private val address = "account_rdx1qpzry9x8gf2tvdw0s3jn54khce6mua7lqpzry9x8gf2tvdw0s3jn54"

    /** The swap memo carried in the intent's OP_RETURN output: `=:ASSET:destinationAddress`. */
    private fun memoOf(intent: PaymentIntent): String {
        val script = intent.outputs!![0].scriptData
        // Layout per Scripts.opReturnScript: OP_RETURN + single-byte length push + memo
        // bytes (every memo in these tests is well under the 76-byte direct-push cap).
        return String(script.copyOfRange(2, script.size))
    }

    @Test
    fun uriInput_normalizedToCanonicalLowercase() = runBlocking {
        assertEquals("=:x:$address", memoOf(parser.parse("radix:$address")))
        assertEquals("=:x:$address", memoOf(parser.parse("RADIX:${address.uppercase()}")))
    }

    @Test
    fun uriWithInvalidPayload_rejected() {
        // A valid scheme prefix must not bypass address validation.
        assertThrows(PaymentIntentParserException::class.java) {
            runBlocking { parser.parse("radix:notAnAddress") }
        }
        // Mixed case is invalid per BIP-173 even after a valid prefix.
        assertThrows(PaymentIntentParserException::class.java) {
            runBlocking { parser.parse("radix:account_rdx1QPZRY9x8gf2tvdw0s3jn54khce6mua7lqpzry9x8gf2tvdw0s3jn54") }
        }
    }
}
