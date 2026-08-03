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

import com.google.common.io.BaseEncoding
import org.dash.wallet.common.payments.parsers.AddressParser
import org.dash.wallet.integrations.maya.payments.AddressGenerator

/**
 * TON address parser — user-friendly Base64URL-encoded address, 48 chars.
 * Also accepts the raw form `<workchain>:<256-bit-hex>`.
 *
 * The friendly form's CRC16-XMODEM checksum is verified, testnet-flagged addresses (tag bit
 * 0x80) are rejected, and the workchain must be 0 (basechain) or -1 (masterchain) — so a
 * single-character typo or a testnet address is rejected client-side. The raw form carries
 * no checksum; only its workchain is checked.
 */
class TonAddressParser : AddressParser("([A-Za-z0-9_-]{48})|(-?\\d+:[a-fA-F0-9]{64})", null) {
    override fun verifyAddress(addressCandidate: String) {
        if (addressCandidate.contains(':')) {
            val workchain = addressCandidate.substringBefore(':').toInt()
            require(workchain == 0 || workchain == -1) { "unknown TON workchain" }
            return
        }
        // Friendly form: tag(1) + workchain(1) + account hash(32) + CRC16(2).
        val bytes = BaseEncoding.base64Url().decode(addressCandidate)
        require(bytes.size == 36) { "TON address must decode to 36 bytes" }
        val tag = bytes[0].toInt() and 0xFF
        // 0x11 = bounceable, 0x51 = non-bounceable; 0x80 marks testnet-only addresses.
        require(tag == 0x11 || tag == 0x51) { "not a mainnet TON address tag" }
        val workchain = bytes[1].toInt()
        require(workchain == 0 || workchain == -1) { "unknown TON workchain" }
        val expected = AddressGenerator.crc16Xmodem(bytes.copyOfRange(0, 34))
        val actual = ((bytes[34].toInt() and 0xFF) shl 8) or (bytes[35].toInt() and 0xFF)
        require(expected == actual) { "invalid TON address checksum" }
    }
}
