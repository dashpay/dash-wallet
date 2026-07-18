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

package org.dash.wallet.common.payments.parsers

import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.payments.bip70.PaymentProtocol
import org.dash.wallet.common.payments.bip70.PaymentProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Structural script helpers: parity with the dashj `Script`/`ScriptPattern`/`ScriptBuilder`
 * behaviors they replaced.
 */
class ScriptsTest {

    /** OP_DUP OP_HASH160 <20 bytes> OP_EQUALVERIFY OP_CHECKSIG. */
    private val validP2PKH = Scripts.p2pkhScript(ByteArray(20) { it.toByte() })

    /** Truncated P2PKH: 0x76 0xa9 0x14 (push 20) but only 5 bytes of payload follow. */
    private val truncatedP2PKH = byteArrayOf(
        0x76.toByte(), 0xa9.toByte(), 0x14, 1, 2, 3, 4, 5
    )

    // ----- FIX 2: structure validation (mirrors what `new Script(bytes)` accepted) -----

    @Test
    fun isParseable_validP2PKH_true() {
        assertTrue(Scripts.isParseable(validP2PKH))
    }

    @Test
    fun isParseable_truncatedP2PKH_false() {
        assertFalse(Scripts.isParseable(truncatedP2PKH))
    }

    @Test
    fun isParseable_pushDataTruncations_false() {
        // OP_PUSHDATA1 with no length byte
        assertFalse(Scripts.isParseable(byteArrayOf(0x4c)))
        // OP_PUSHDATA1 claiming 10 bytes but only 2 present
        assertFalse(Scripts.isParseable(byteArrayOf(0x4c, 10, 1, 2)))
        // OP_PUSHDATA2 with only one length byte
        assertFalse(Scripts.isParseable(byteArrayOf(0x4d, 5)))
        // OP_PUSHDATA4 claiming more than remains
        assertFalse(Scripts.isParseable(byteArrayOf(0x4e, 4, 0, 0, 0, 1)))
    }

    @Test
    fun isParseable_unknownOpcodesAndCompletePushes_true() {
        // OP_RETURN + complete direct push
        assertTrue(Scripts.isParseable(byteArrayOf(0x6a, 3, 1, 2, 3)))
        // unknown/reserved opcodes are fine as long as pushes are complete
        assertTrue(Scripts.isParseable(byteArrayOf(0xba.toByte(), 0x50, 0x61)))
        // complete OP_PUSHDATA1
        assertTrue(Scripts.isParseable(byteArrayOf(0x4c, 2, 7, 8)))
    }

    @Test
    fun outputValueOf_truncatedScript_throwsInvalidOutputsAtParseTime() {
        try {
            PaymentIntent.Output.valueOf(PaymentProtocol.Output(Coin.COIN, truncatedP2PKH))
            fail("expected InvalidOutputs for truncated script")
        } catch (e: PaymentProtocolException.InvalidOutputs) {
            assertTrue(e.message!!.startsWith("unparseable script in output: "))
        }
    }

    @Test
    fun outputValueOf_validScript_accepted() {
        val output = PaymentIntent.Output.valueOf(PaymentProtocol.Output(Coin.COIN, validP2PKH))
        assertArrayEquals(validP2PKH, output.scriptData)
        assertEquals(Coin.COIN, output.amount)
    }

    // ----- FIX 7: OP_RETURN 80-byte limit (ScriptBuilder.createOpReturnScript) -----

    @Test
    fun opReturnScript_80Bytes_allowed() {
        val script = Scripts.opReturnScript(ByteArray(80))
        assertTrue(Scripts.isOpReturn(script))
    }

    @Test(expected = IllegalArgumentException::class)
    fun opReturnScript_81Bytes_throws() {
        Scripts.opReturnScript(ByteArray(81))
    }

    // ----- FIX 10: isP2PK parity with dashj ScriptPattern.isP2PK (loose) -----

    @Test
    fun isP2PK_directPushes_match() {
        val key33 = ByteArray(33) { 2 }
        val key65 = ByteArray(65) { 4 }
        assertTrue(Scripts.isP2PK(byteArrayOf(33) + key33 + 0xac.toByte()))
        assertTrue(Scripts.isP2PK(byteArrayOf(65) + key65 + 0xac.toByte()))
        // dashj accepts ANY data push longer than 1 byte, not just 33/65
        assertTrue(Scripts.isP2PK(byteArrayOf(10) + ByteArray(10) + 0xac.toByte()))
    }

    @Test
    fun isP2PK_pushData1Encoded_matches() {
        val key33 = ByteArray(33) { 2 }
        val script = byteArrayOf(0x4c, 33) + key33 + 0xac.toByte()
        assertTrue(Scripts.isP2PK(script))
        assertArrayEquals(key33, Scripts.extractKeyFromP2PK(script))
    }

    @Test
    fun isP2PK_rejects_nonP2PK() {
        // push of a single byte: dashj requires data.length > 1
        assertFalse(Scripts.isP2PK(byteArrayOf(1, 42, 0xac.toByte())))
        // no OP_CHECKSIG
        assertFalse(Scripts.isP2PK(byteArrayOf(33) + ByteArray(33) + 0xae.toByte()))
        // more than two chunks
        assertFalse(Scripts.isP2PK(byteArrayOf(2, 1, 2, 2, 3, 4) + 0xac.toByte()))
        // first chunk an opcode, not a push
        assertFalse(Scripts.isP2PK(byteArrayOf(0x76, 0xac.toByte())))
        // standard P2PKH is not P2PK
        assertFalse(Scripts.isP2PK(validP2PKH))
        // truncated script
        assertFalse(Scripts.isP2PK(truncatedP2PKH))
    }

    @Test
    fun extractKeyFromP2PK_directPush() {
        val key = ByteArray(33) { 7 }
        assertArrayEquals(key, Scripts.extractKeyFromP2PK(byteArrayOf(33) + key + 0xac.toByte()))
    }

    // ----- FIX 10: secondChunkData OP_0 parity (dashj chunk data is an empty array) -----

    @Test
    fun secondChunkData_op0_yieldsEmptyArrayNotNull() {
        val script = byteArrayOf(0x6a, 0x00) // OP_RETURN OP_0
        val data = Scripts.secondChunkData(script)
        assertNotNull(data)
        assertEquals(0, data!!.size)
    }

    @Test
    fun secondChunkData_regularPush() {
        val payload = byteArrayOf(1, 2, 3)
        assertArrayEquals(payload, Scripts.secondChunkData(byteArrayOf(0x6a, 3) + payload))
    }

    @Test
    fun secondChunkData_plainOpcodeOrUnparseable_null() {
        // second chunk is a plain opcode (OP_CHECKSIG)
        assertNull(Scripts.secondChunkData(byteArrayOf(0x6a, 0xac.toByte())))
        // single chunk only
        assertNull(Scripts.secondChunkData(byteArrayOf(0x6a)))
        // truncated script
        assertNull(Scripts.secondChunkData(truncatedP2PKH))
    }
}
