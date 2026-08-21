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

package org.dash.wallet.common.money

/**
 * Conversions for transaction ids represented as hex strings (`Sha256Hash.toString()`),
 * for feature/integration modules that must not depend on dashj. Encodings are exactly
 * the ones the wallet uses.
 */
object TxIds {

    /** Hex representation of the all-zero tx id (mirrors `Sha256Hash.ZERO_HASH.toString()`). */
    const val ZERO_HASH_HEX: String = "0000000000000000000000000000000000000000000000000000000000000000"

    /** Converts a hex tx id to its raw bytes (mirrors `Sha256Hash.wrap(hex).bytes`) — e.g. for Room BLOB queries. */
    fun toBytes(txIdHex: String): ByteArray {
        require(txIdHex.length == 64) { "not a 32-byte hex string: " + txIdHex }
        return ByteArray(32) { i -> txIdHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Converts a hex tx id to its base58 representation (mirrors `Sha256Hash.toStringBase58()`). */
    fun toBase58(txIdHex: String): String =
        org.dash.wallet.common.payments.parsers.Base58.encode(toBytes(txIdHex))
}
