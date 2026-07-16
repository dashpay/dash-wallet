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

import org.bitcoinj.core.Sha256Hash

/**
 * Conversions for transaction ids represented as hex strings ([org.bitcoinj.core.Sha256Hash.toString]),
 * for feature/integration modules that must not depend on dashj. Delegates to dashj internally so
 * encodings are exactly the ones the wallet uses.
 */
object TxIds {

    /** Hex representation of the all-zero tx id (mirrors [Sha256Hash.ZERO_HASH]`.toString()`). */
    val ZERO_HASH_HEX: String = Sha256Hash.ZERO_HASH.toString()

    /** Converts a hex tx id to its raw bytes (mirrors [Sha256Hash.wrap]`(hex).bytes`) — e.g. for Room BLOB queries. */
    fun toBytes(txIdHex: String): ByteArray = Sha256Hash.wrap(txIdHex).bytes

    /** Converts a hex tx id to its base58 representation (mirrors [Sha256Hash]`.toStringBase58()`). */
    fun toBase58(txIdHex: String): String = Sha256Hash.wrap(txIdHex).toStringBase58()
}
