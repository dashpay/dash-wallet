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
package org.dash.wallet.integrations.maya.payments

import com.google.common.io.BaseEncoding
import org.bitcoinj.core.Base58
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.payments.parsers.Bech32
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Low-level encoders backing [MayaCryptoCurrency.getNewExampleAddress]. Each helper turns a
 * payload derived from the per-session [MayaCryptoCurrency.sessionExampleKey] into a format-valid
 * address string, with a correct checksum whenever the address format defines one (Base58Check,
 * bech32/bech32m, CRC16). The addresses are indicative only — the session key is never persisted
 * and funds must never be sent to them.
 */
internal object AddressGenerator {
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Deterministically expand [key]'s public key into [count] bytes for chains whose address
     * payload is not a 20-byte HASH160 (EVM's 20-byte keccak hash, 32-byte account hashes on
     * Solana/NEAR/SUI/TON, Cardano's 28-byte credentials, …). [context] is a per-chain tag so
     * different chains never share the same-looking payload: SHA256(pubkey ‖ context ‖ counter)
     * blocks are concatenated until [count] bytes are available.
     */
    fun deriveBytes(key: ECKey, context: String, count: Int): ByteArray {
        val out = ByteArrayOutputStream(count)
        var counter = 0
        while (out.size() < count) {
            out.write(Sha256Hash.hash(key.pubKey + context.toByteArray() + byteArrayOf(counter.toByte())))
            counter++
        }
        return out.toByteArray().copyOf(count)
    }

    /**
     * Base58Check with an arbitrary-length version prefix. Zcash transparent addresses need a
     * two-byte prefix (0x1CB8 -> "t1"), which [Base58.encodeChecked]'s single int version can't
     * express; single-byte prefixes can use [Base58.encodeChecked] directly.
     */
    fun base58Check(prefix: ByteArray, payload: ByteArray): String {
        val data = prefix + payload
        val checksum = Sha256Hash.hashTwice(data).copyOfRange(0, 4)
        return Base58.encode(data + checksum)
    }

    /**
     * Bech32/bech32m encoding of [payload] (raw 8-bit bytes) under [hrp] — the Cosmos-style
     * account encoding used by Kujira/THORChain/Maya (bech32 of a 20-byte key hash), Cardano
     * (bech32 of header + credentials) and Radix (bech32m of entity byte + hash).
     */
    fun bech32(hrp: String, payload: ByteArray, encoding: Bech32.Encoding = Bech32.Encoding.BECH32): String =
        Bech32.encode(encoding, hrp, toFiveBit(payload))

    /**
     * Segwit v0 (BIP-173) address for a 20-byte witness program under an arbitrary [hrp] — for
     * chains such as Litecoin where no [org.bitcoinj.core.NetworkParameters] exists in this app,
     * so [org.dash.wallet.common.payments.parsers.SegwitAddress] can't be used.
     */
    fun segwitV0(hrp: String, program: ByteArray): String =
        Bech32.encode(Bech32.Encoding.BECH32, hrp, byteArrayOf(0) + toFiveBit(program))

    // XRP's Base58 dictionary — same scheme as Bitcoin's Base58Check but a permuted alphabet
    // (account addresses start with 'r' because it is the alphabet's zero character).
    private const val RIPPLE_ALPHABET = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"

    /** XRP classic address: version 0x00 + 20-byte account id + 4-byte checksum, ripple Base58. */
    fun rippleBase58Check(accountId: ByteArray): String {
        val payload = byteArrayOf(0) + accountId
        val data = payload + Sha256Hash.hashTwice(payload).copyOfRange(0, 4)
        var num = BigInteger(1, data)
        val base = BigInteger.valueOf(58)
        val sb = StringBuilder()
        while (num.signum() > 0) {
            val divRem = num.divideAndRemainder(base)
            sb.append(RIPPLE_ALPHABET[divRem[1].toInt()])
            num = divRem[0]
        }
        data.takeWhile { it == 0.toByte() }.forEach { _ -> sb.append(RIPPLE_ALPHABET[0]) }
        return sb.reverse().toString()
    }

    /**
     * TON user-friendly address: tag 0x11 (bounceable, mainnet-safe) + workchain 0 + 32-byte
     * account hash + CRC16-XMODEM, base64url — 48 chars starting "EQ".
     */
    fun tonFriendly(accountHash: ByteArray): String {
        val data = byteArrayOf(0x11, 0x00) + accountHash
        val crc = crc16Xmodem(data)
        return BaseEncoding.base64Url().encode(data + byteArrayOf((crc shr 8).toByte(), crc.toByte()))
    }

    private fun crc16Xmodem(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** 8-bit -> 5-bit regrouping with padding (BIP-173 convertbits), as bech32 encoding expects. */
    private fun toFiveBit(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(64)
        var acc = 0
        var bits = 0
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.write((acc ushr bits) and 0x1F)
            }
        }
        if (bits > 0) out.write((acc shl (5 - bits)) and 0x1F)
        return out.toByteArray()
    }
}
