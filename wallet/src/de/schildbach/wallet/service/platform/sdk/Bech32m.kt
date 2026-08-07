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

package de.schildbach.wallet.service.platform.sdk

/**
 * Minimal bech32m (BIP-350) encoder/decoder — port of the SDK example
 * app's `Bech32m` util (itself a port of the Swift SDK's helper). Used by
 * [ShieldedBalanceService] to display the wallet's shielded (Orchard)
 * receive address and to detect/decode bech32m Orchard recipients; the
 * authoritative parse for a *send* still happens on the Rust side
 * (`OrchardAddress::from_raw_bytes`, `PlatformAddress::from_bech32m_string`).
 *
 * Pure Kotlin (no native, no Android) so the shielded address mapping is
 * unit-testable on the host JVM.
 */
internal object Bech32m {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /** BIP-350 bech32m checksum constant (bech32 uses 1). */
    private const val BECH32M_CONST = 0x2bc830a3

    /** A decoded bech32m string: human-readable part + 8-bit payload. */
    data class Decoded(val hrp: String, val data: ByteArray)

    /**
     * Decode a bech32m string to its HRP and 8-bit payload bytes, or null
     * when malformed (bad charset, checksum, padding, or HRP length).
     */
    fun decode(input: String): Decoded? {
        // BIP-350: a string must be entirely lowercase or entirely
        // uppercase; mixed case is invalid.
        if (input != input.lowercase() && input != input.uppercase()) return null
        val lower = input.lowercase()
        val sep = lower.lastIndexOf('1')
        if (sep < 1) return null
        val hrp = lower.substring(0, sep)
        val dataPart = lower.substring(sep + 1)
        if (hrp.length !in 1..83 || dataPart.length < 6) return null

        val values = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val v = CHARSET.indexOf(dataPart[i])
            if (v < 0) return null
            values[i] = v
        }
        if (polymod(hrpExpand(hrp) + values.toList()) != BECH32M_CONST) return null

        val payload = convertFrom5Bit(values.dropLast(6)) ?: return null
        return Decoded(hrp, payload)
    }

    /** Encode [data] under [hrp] as a bech32m string, or null when empty. */
    fun encode(hrp: String, data: ByteArray): String? {
        val values = convertTo5Bit(data)
        if (values.isEmpty()) return null
        val checksum = createChecksum(hrp, values)
        return buildString {
            append(hrp)
            append('1')
            (values + checksum).forEach { append(CHARSET[it]) }
        }
    }

    private fun convertTo5Bit(data: ByteArray): List<Int> {
        val result = mutableListOf<Int>()
        var acc = 0
        var bits = 0
        for (byte in data) {
            acc = (acc shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                result.add((acc shr bits) and 0x1f)
            }
        }
        if (bits > 0) result.add((acc shl (5 - bits)) and 0x1f)
        return result
    }

    private fun convertFrom5Bit(values: List<Int>): ByteArray? {
        val result = mutableListOf<Byte>()
        var acc = 0
        var bits = 0
        for (v in values) {
            if (v !in 0..31) return null
            acc = (acc shl 5) or v
            bits += 5
            while (bits >= 8) {
                bits -= 8
                result.add(((acc shr bits) and 0xff).toByte())
            }
        }
        // Reject over-long or non-zero padding, per BIP-350.
        if (bits > 4) return null
        if ((acc and ((1 shl bits) - 1)) != 0) return null
        return result.toByteArray()
    }

    private fun polymod(values: List<Int>): Int {
        val generator = intArrayOf(
            0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
        )
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if ((top shr i) and 1 != 0) chk = chk xor generator[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }

    private fun createChecksum(hrp: String, values: List<Int>): List<Int> {
        val mod = polymod(hrpExpand(hrp) + values + List(6) { 0 }) xor BECH32M_CONST
        return (0 until 6).map { (mod shr (5 * (5 - it))) and 31 }
    }
}
