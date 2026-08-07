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

import org.bouncycastle.crypto.digests.RIPEMD160Digest
import java.security.MessageDigest

/**
 * Dashj-free helpers over raw output-script bytes, mirroring the exact recognition and
 * construction rules of dashj's `ScriptPattern`/`ScriptBuilder` for the script shapes the
 * app deals with (P2PKH, P2SH, P2PK, OP_RETURN).
 */
object Scripts {
    private const val OP_DUP = 0x76
    private const val OP_HASH160 = 0xa9
    private const val OP_EQUAL = 0x87
    private const val OP_EQUALVERIFY = 0x88
    private const val OP_CHECKSIG = 0xac
    private const val OP_CHECKMULTISIG = 0xae
    private const val OP_RETURN = 0x6a
    private const val OP_PUSHDATA1 = 0x4c
    private const val OP_PUSHDATA2 = 0x4d
    private const val OP_PUSHDATA4 = 0x4e

    private fun at(script: ByteArray, index: Int): Int = script[index].toInt() and 0xFF

    /** One parsed script element: a plain opcode (data == null) or a data push. */
    private class Chunk(val opcode: Int, val data: ByteArray?)

    /**
     * Parses [script] into chunks exactly like dashj's `Script.parse`, or returns null where
     * dashj would throw a `ScriptException`: a direct push (opcode 1..75) must have that many
     * bytes remaining, OP_PUSHDATA1/2/4 must have their length bytes plus payload remaining.
     * Unknown non-push opcodes are fine. OP_0 yields an empty (not null) data array, matching
     * dashj.
     */
    private fun parseChunks(script: ByteArray): List<Chunk>? {
        val chunks = mutableListOf<Chunk>()
        var cursor = 0
        while (cursor < script.size) {
            val opcode = at(script, cursor)
            cursor++
            var dataToRead = -1
            when {
                opcode in 0 until OP_PUSHDATA1 -> dataToRead = opcode
                opcode == OP_PUSHDATA1 -> {
                    if (cursor + 1 > script.size) return null
                    dataToRead = at(script, cursor)
                    cursor++
                }
                opcode == OP_PUSHDATA2 -> {
                    if (cursor + 2 > script.size) return null
                    dataToRead = at(script, cursor) or (at(script, cursor + 1) shl 8)
                    cursor += 2
                }
                opcode == OP_PUSHDATA4 -> {
                    if (cursor + 4 > script.size) return null
                    dataToRead = at(script, cursor) or (at(script, cursor + 1) shl 8) or
                        (at(script, cursor + 2) shl 16) or (at(script, cursor + 3) shl 24)
                    cursor += 4
                }
            }
            if (dataToRead == -1) {
                chunks.add(Chunk(opcode, null))
            } else {
                if (dataToRead < 0 || dataToRead > script.size - cursor) return null
                chunks.add(Chunk(opcode, script.copyOfRange(cursor, cursor + dataToRead)))
                cursor += dataToRead
            }
        }
        return chunks
    }

    /**
     * Structural validation of a raw output script, accepting exactly what dashj's
     * `Script(byte[])` constructor parses without throwing: truncated pushes and PUSHDATA
     * length overruns are parse failures, unknown non-push opcodes are fine.
     */
    @JvmStatic
    fun isParseable(script: ByteArray): Boolean = parseChunks(script) != null

    /** Mirrors `ScriptPattern.isP2PKH`: OP_DUP OP_HASH160 &lt;20 bytes&gt; OP_EQUALVERIFY OP_CHECKSIG. */
    @JvmStatic
    fun isP2PKH(script: ByteArray): Boolean =
        script.size == 25 &&
            at(script, 0) == OP_DUP &&
            at(script, 1) == OP_HASH160 &&
            at(script, 2) == 20 &&
            at(script, 23) == OP_EQUALVERIFY &&
            at(script, 24) == OP_CHECKSIG

    /** Mirrors `ScriptPattern.isP2SH`: OP_HASH160 &lt;20 bytes&gt; OP_EQUAL. */
    @JvmStatic
    fun isP2SH(script: ByteArray): Boolean =
        script.size == 23 &&
            at(script, 0) == OP_HASH160 &&
            at(script, 1) == 20 &&
            at(script, 22) == OP_EQUAL

    /**
     * Mirrors `ScriptPattern.isP2PK`: exactly two chunks — any data push of more than one byte
     * (direct or PUSHDATA-encoded, like dashj) followed by OP_CHECKSIG.
     */
    @JvmStatic
    fun isP2PK(script: ByteArray): Boolean {
        val chunks = parseChunks(script) ?: return false
        if (chunks.size != 2) return false
        val pubKey = chunks[0]
        if (pubKey.opcode > OP_PUSHDATA4) return false // first chunk must be a data push
        val data = pubKey.data ?: return false
        if (data.size <= 1) return false
        return chunks[1].opcode == OP_CHECKSIG && chunks[1].data == null
    }

    /** Mirrors `ScriptPattern.isOpReturn`: first opcode is OP_RETURN. */
    @JvmStatic
    fun isOpReturn(script: ByteArray): Boolean = script.isNotEmpty() && at(script, 0) == OP_RETURN

    /** Rough mirror of `ScriptPattern.isSentToMultisig`: last opcode is OP_CHECKMULTISIG. */
    @JvmStatic
    fun isMultisig(script: ByteArray): Boolean = script.isNotEmpty() && at(script, script.size - 1) == OP_CHECKMULTISIG

    /** The 20-byte hash of a P2PKH script (mirrors `ScriptPattern.extractHashFromP2PKH`). */
    @JvmStatic
    fun extractHashFromP2PKH(script: ByteArray): ByteArray = script.copyOfRange(3, 23)

    /** The 20-byte hash of a P2SH script (mirrors `ScriptPattern.extractHashFromP2SH`). */
    @JvmStatic
    fun extractHashFromP2SH(script: ByteArray): ByteArray = script.copyOfRange(2, 22)

    /** The pushed public key of a P2PK script (mirrors `ScriptPattern.extractKeyFromP2PK`: chunk 0's data). */
    @JvmStatic
    fun extractKeyFromP2PK(script: ByteArray): ByteArray =
        requireNotNull(parseChunks(script)?.firstOrNull()?.data) { "not a P2PK script" }

    /**
     * Destination address of this script on [network], mirroring
     * `Script.getToAddress(params, forcePayToPubKey)`; null where the dashj original would
     * throw a `ScriptException` (unrecognized script shape).
     */
    @JvmStatic
    @JvmOverloads
    fun addressOf(script: ByteArray, network: AddressNetwork, forcePayToPubKey: Boolean = false): String? = when {
        isP2PKH(script) -> AddressUtils.encode(network.addressHeader, extractHashFromP2PKH(script))
        isP2SH(script) -> AddressUtils.encode(network.p2shHeader, extractHashFromP2SH(script))
        forcePayToPubKey && isP2PK(script) ->
            AddressUtils.encode(network.addressHeader, hash160(extractKeyFromP2PK(script)))
        else -> null
    }

    /**
     * Builds the output script paying to base58 [address] (P2PKH or P2SH by version byte),
     * mirroring `ScriptBuilder.createOutputScript(address)`. The version byte must belong
     * to [network] when given.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(AddressFormatException::class)
    fun outputScriptForAddress(address: String, network: AddressNetwork? = null): ByteArray {
        val decoded = AddressUtils.decode(address)
        if (network != null && !network.acceptsVersion(decoded.version)) {
            throw AddressFormatException.WrongNetwork(decoded.version)
        }
        val resolved = network ?: AddressNetwork.fromDashAddress(address)
        return when (decoded.version) {
            resolved.p2shHeader -> p2shScript(decoded.hash160)
            else -> p2pkhScript(decoded.hash160)
        }
    }

    /** OP_DUP OP_HASH160 &lt;hash&gt; OP_EQUALVERIFY OP_CHECKSIG. */
    @JvmStatic
    fun p2pkhScript(hash160: ByteArray): ByteArray {
        require(hash160.size == 20)
        val script = ByteArray(25)
        script[0] = OP_DUP.toByte()
        script[1] = OP_HASH160.toByte()
        script[2] = 20
        hash160.copyInto(script, 3)
        script[23] = OP_EQUALVERIFY.toByte()
        script[24] = OP_CHECKSIG.toByte()
        return script
    }

    /** OP_HASH160 &lt;hash&gt; OP_EQUAL. */
    @JvmStatic
    fun p2shScript(hash160: ByteArray): ByteArray {
        require(hash160.size == 20)
        val script = ByteArray(23)
        script[0] = OP_HASH160.toByte()
        script[1] = 20
        hash160.copyInto(script, 2)
        script[22] = OP_EQUAL.toByte()
        return script
    }

    /** OP_RETURN &lt;data push&gt;, mirroring `ScriptBuilder.createOpReturnScript(data)` incl. its 80-byte limit. */
    @JvmStatic
    fun opReturnScript(data: ByteArray): ByteArray {
        require(data.size <= 80) { "data is too long: ${data.size}" }
        return byteArrayOf(OP_RETURN.toByte()) + pushData(data)
    }

    /** Shortest-possible data push, mirroring `ScriptBuilder.data(data)` chunk encoding. */
    private fun pushData(data: ByteArray): ByteArray = when {
        data.isEmpty() -> byteArrayOf(0) // OP_0
        data.size < OP_PUSHDATA1 -> byteArrayOf(data.size.toByte()) + data
        data.size <= 0xFF -> byteArrayOf(OP_PUSHDATA1.toByte(), data.size.toByte()) + data
        data.size <= 0xFFFF ->
            byteArrayOf(OP_PUSHDATA2.toByte(), (data.size and 0xFF).toByte(), (data.size shr 8).toByte()) + data
        else -> byteArrayOf(
            OP_PUSHDATA4.toByte(),
            (data.size and 0xFF).toByte(),
            ((data.size shr 8) and 0xFF).toByte(),
            ((data.size shr 16) and 0xFF).toByte(),
            ((data.size ushr 24) and 0xFF).toByte()
        ) + data
    }

    /**
     * The data payload of the second script chunk (mirrors reading `script.chunks[1].data`),
     * or null if the script doesn't parse, has no second chunk, or the second chunk is a plain
     * opcode. An OP_0 second chunk yields an empty (not null) array, exactly like dashj.
     */
    @JvmStatic
    fun secondChunkData(script: ByteArray): ByteArray? {
        val chunks = parseChunks(script) ?: return null
        if (chunks.size < 2) return null
        return chunks[1].data
    }

    /** SHA-256 followed by RIPEMD-160, as used for address derivation (mirrors `Utils.sha256hash160`). */
    @JvmStatic
    fun hash160(input: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(input)
        val digest = RIPEMD160Digest()
        digest.update(sha256, 0, sha256.size)
        val out = ByteArray(20)
        digest.doFinal(out, 0)
        return out
    }
}
