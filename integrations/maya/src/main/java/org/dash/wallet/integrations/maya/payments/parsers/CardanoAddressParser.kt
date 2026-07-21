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

import org.bitcoinj.core.Base58
import org.dash.wallet.common.payments.parsers.AddressParser
import org.dash.wallet.common.payments.parsers.Bech32
import java.util.zip.CRC32

/**
 * Cardano address parser — Shelley Bech32 addresses (HRP `addr`, length ~103)
 * or legacy Byron Base58 (`Ae2tdPwUPEZ...` / `DdzFFzCqr...`).
 *
 * Both forms are checksummed and both checksums are verified: the Shelley bech32 checksum
 * (Cardano exceeds BIP-173's 90-char cap, hence the explicit max length) and Byron's
 * CBOR-wrapped CRC32 — so a single-character typo is rejected client-side.
 */
class CardanoAddressParser : AddressParser(
    "(addr1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{53,98})|((Ae2|DdzFF)[1-9A-HJ-NP-Za-km-z]{50,110})",
    null
) {
    companion object {
        // "addr1" (5) + up to 98 data chars allowed by the pattern above.
        private const val MAX_SHELLEY_LENGTH = 110
    }

    override fun verifyAddress(addressCandidate: String) {
        if (addressCandidate.startsWith("addr1")) {
            val decoded = Bech32.decode(addressCandidate, MAX_SHELLEY_LENGTH)
            require(decoded.encoding == Bech32.Encoding.BECH32 && decoded.hrp == "addr") {
                "not a mainnet Shelley address"
            }
        } else {
            verifyByron(addressCandidate)
        }
    }

    /**
     * Byron addresses are Base58 of the CBOR structure `[ tag24(bytes payload), uint crc32 ]`;
     * the CRC32 is computed over the raw payload bytes. Parse just enough CBOR to extract the
     * two elements and verify the CRC.
     */
    private fun verifyByron(address: String) {
        val bytes = Base58.decode(address)
        // 0x82 = array(2), 0xD8 0x18 = tag(24) wrapping the payload byte string.
        require(
            bytes.size > 8 && bytes[0] == 0x82.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0x18.toByte()
        ) { "not a Byron address structure" }
        var i = 3
        val payloadHeader = bytes[i].toInt() and 0xFF
        val payloadLength: Int
        when {
            payloadHeader in 0x40..0x57 -> {
                payloadLength = payloadHeader - 0x40
                i += 1
            }
            payloadHeader == 0x58 -> {
                payloadLength = bytes[i + 1].toInt() and 0xFF
                i += 2
            }
            payloadHeader == 0x59 -> {
                payloadLength = ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
                i += 3
            }
            else -> throw IllegalArgumentException("unexpected Byron payload header")
        }
        require(i + payloadLength < bytes.size) { "Byron payload overruns the address" }
        val payload = bytes.copyOfRange(i, i + payloadLength)
        i += payloadLength
        val crcHeader = bytes[i].toInt() and 0xFF
        // The CRC argument bytes must fit inside the buffer before the multi-byte reads below.
        val crcArgLength = when (crcHeader) {
            0x18 -> 1
            0x19 -> 2
            0x1A -> 4
            else -> 0
        }
        require(i + crcArgLength < bytes.size) { "Byron CRC overruns the address" }
        val crc: Long
        when {
            crcHeader < 0x18 -> {
                crc = crcHeader.toLong()
                i += 1
            }
            crcHeader == 0x18 -> {
                crc = bytes[i + 1].toLong() and 0xFF
                i += 2
            }
            crcHeader == 0x19 -> {
                crc = (((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)).toLong()
                i += 3
            }
            crcHeader == 0x1A -> {
                crc = ((bytes[i + 1].toLong() and 0xFF) shl 24) or
                    ((bytes[i + 2].toLong() and 0xFF) shl 16) or
                    ((bytes[i + 3].toLong() and 0xFF) shl 8) or
                    (bytes[i + 4].toLong() and 0xFF)
                i += 5
            }
            else -> throw IllegalArgumentException("unexpected Byron CRC header")
        }
        require(i == bytes.size) { "trailing bytes after Byron CRC" }
        require(CRC32().apply { update(payload) }.value == crc) { "invalid Byron address CRC" }
    }
}
