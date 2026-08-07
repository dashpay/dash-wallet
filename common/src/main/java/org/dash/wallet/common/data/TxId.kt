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

package org.dash.wallet.common.data

/**
 * A 32-byte hash id (transaction id, icon id, …), dashj-free.
 *
 * Mirrors the semantics of `org.bitcoinj.core.Sha256Hash` exactly — same wrapped byte order,
 * same hex `toString`, same `hashCode` (last four bytes) — so Room BLOB persistence and any
 * hash-keyed containers behave identically to the previous Sha256Hash-typed fields.
 */
class TxId(bytes: ByteArray) : Comparable<TxId> {

    val bytes: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == LENGTH) { "wrong hash length: " + bytes.size }
    }

    companion object {
        const val LENGTH = 32

        @JvmField
        val ZERO_HASH = TxId(ByteArray(LENGTH))

        /** Mirrors `Sha256Hash.wrap(bytes)`. */
        @JvmStatic
        fun wrap(bytes: ByteArray) = TxId(bytes)

        /** Mirrors `Sha256Hash.wrap(hexString)`. */
        @JvmStatic
        fun wrap(hex: String): TxId {
            require(hex.length == LENGTH * 2) { "not a 32-byte hex string: $hex" }
            return TxId(ByteArray(LENGTH) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
        }

        /** Mirrors `Sha256Hash.of(contents)`: single SHA-256 of the input. */
        @JvmStatic
        fun of(contents: ByteArray): TxId =
            TxId(java.security.MessageDigest.getInstance("SHA-256").digest(contents))

        /** Mirrors `Sha256Hash.of(file)`: single SHA-256 of the file's contents. */
        @JvmStatic
        fun of(file: java.io.File): TxId = of(file.readBytes())
    }

    /** Hex representation, exactly `Sha256Hash.toString()`. */
    override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }

    /** Base58 representation, exactly `Sha256Hash.toStringBase58()`. */
    fun toStringBase58(): String = org.dash.wallet.common.payments.parsers.Base58.encode(bytes)

    override fun equals(other: Any?): Boolean = other is TxId && bytes.contentEquals(other.bytes)

    /** Mirrors `Sha256Hash.hashCode()`: an int from the last four bytes. */
    override fun hashCode(): Int =
        (bytes[LENGTH - 4].toInt() and 0xFF shl 24) or
            (bytes[LENGTH - 3].toInt() and 0xFF shl 16) or
            (bytes[LENGTH - 2].toInt() and 0xFF shl 8) or
            (bytes[LENGTH - 1].toInt() and 0xFF)

    /** Mirrors `Sha256Hash.compareTo`: unsigned comparison from the last byte backwards. */
    override fun compareTo(other: TxId): Int {
        for (i in LENGTH - 1 downTo 0) {
            val thisByte = bytes[i].toInt() and 0xFF
            val otherByte = other.bytes[i].toInt() and 0xFF
            if (thisByte > otherByte) return 1
            if (thisByte < otherByte) return -1
        }
        return 0
    }
}
