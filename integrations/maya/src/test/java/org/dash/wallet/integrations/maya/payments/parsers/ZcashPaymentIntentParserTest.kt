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

class ZcashPaymentIntentParserTest {
    private val parser = ZcashPaymentIntentParser()
    private val transparent = "t1K79TgQbqu74d6rBmsMu2oFEXEwAmdYiT7"

    /** The swap memo carried in the intent's OP_RETURN output: `=:ASSET:destinationAddress`. */
    private fun memoOf(intent: PaymentIntent): String {
        val script = intent.outputs!![0].scriptData
        // Layout per Scripts.opReturnScript: OP_RETURN + single-byte length push + memo
        // bytes (every memo in these tests is well under the 76-byte direct-push cap).
        return String(script.copyOfRange(2, script.size))
    }

    @Test
    fun uriInput_preservesTransparentCase() = runBlocking {
        assertEquals("=:z:$transparent", memoOf(parser.parse("zcash:$transparent")))
        assertEquals("=:z:$transparent", memoOf(parser.parse("ZCASH:$transparent")))
    }

    @Test
    fun uriWithInvalidPayload_rejected() {
        // A valid scheme prefix must not bypass address validation.
        assertThrows(PaymentIntentParserException::class.java) {
            runBlocking { parser.parse("zcash:notAnAddress") }
        }
        // An uppercased t-address is corrupt Base58 — rejected, not lowercased into a
        // different address.
        assertThrows(PaymentIntentParserException::class.java) {
            runBlocking { parser.parse("zcash:${transparent.uppercase()}") }
        }
    }
}
