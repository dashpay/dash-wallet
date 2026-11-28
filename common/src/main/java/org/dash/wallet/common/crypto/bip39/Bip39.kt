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

package org.dash.wallet.common.crypto.bip39

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Self-contained BIP39 implementation, a byte-faithful port of `org.bitcoinj.crypto.MnemonicCode`
 * (dashj 22.0.3) with no dashj dependency:
 *
 * - [check] / [toEntropy]: word membership (collator-based, see [Bip39Wordlist.indexOf]) plus
 *   checksum validation, throwing [Bip39Exception] subtypes that mirror `MnemonicException` 1:1;
 * - [toMnemonic]: entropy → words;
 * - [toSeed]: PBKDF2-HMAC-SHA512, 2048 iterations, 64-byte seed, password = words joined with a
 *   single space, salt = `"mnemonic" + passphrase`, both UTF-8 — exactly dashj's
 *   `MnemonicCode.toSeed` / `PBKDF2SHA512.derive`.
 */
object Bip39 {

    /** dashj `MnemonicCode.PBKDF2_ROUNDS`. */
    const val PBKDF2_ROUNDS = 2048

    /** Seed length in bytes produced by [toSeed]. */
    const val SEED_LENGTH = 64

    /**
     * Validates the mnemonic (membership + checksum) against [wordlist].
     * Mirror of `MnemonicCode.check`.
     *
     * @throws Bip39Exception.LengthException word count is zero or not a multiple of 3
     * @throws Bip39Exception.WordException a word is not in the wordlist
     * @throws Bip39Exception.ChecksumException the checksum bits do not match
     */
    @JvmStatic
    @Throws(Bip39Exception::class)
    fun check(words: List<String>, wordlist: Bip39Wordlist = Bip39Wordlist.ENGLISH) {
        toEntropy(words, wordlist)
    }

    /** True when [check] passes. */
    @JvmStatic
    fun isValid(words: List<String>, wordlist: Bip39Wordlist = Bip39Wordlist.ENGLISH): Boolean {
        return try {
            check(words, wordlist)
            true
        } catch (e: Bip39Exception) {
            false
        }
    }

    /**
     * Converts a mnemonic back to its entropy, validating the checksum.
     * Line-for-line port of `MnemonicCode.toEntropy` (dashj 22.0.3).
     */
    @JvmStatic
    @Throws(Bip39Exception::class)
    fun toEntropy(words: List<String>, wordlist: Bip39Wordlist = Bip39Wordlist.ENGLISH): ByteArray {
        if (words.size % 3 > 0) {
            throw Bip39Exception.LengthException("Word list size must be multiple of three words.")
        }
        if (words.isEmpty()) {
            throw Bip39Exception.LengthException("Word list is empty.")
        }

        // Look up all the words in the list and construct the concatenation of the original entropy and the checksum.
        val concatLenBits = words.size * 11
        val concatBits = BooleanArray(concatLenBits)
        var wordindex = 0
        for (word in words) {
            // Find the word's index in the wordlist (collator-based, like dashj's search()).
            val ndx = wordlist.indexOf(word)
            if (ndx < 0) {
                throw Bip39Exception.WordException(word)
            }
            // Set the next 11 bits to the value of the index.
            for (ii in 0 until 11) {
                concatBits[wordindex * 11 + ii] = (ndx and (1 shl (10 - ii))) != 0
            }
            ++wordindex
        }

        val checksumLengthBits = concatLenBits / 33
        val entropyLengthBits = concatLenBits - checksumLengthBits

        // Extract original entropy as bytes.
        val entropy = ByteArray(entropyLengthBits / 8)
        for (ii in entropy.indices) {
            for (jj in 0 until 8) {
                if (concatBits[ii * 8 + jj]) {
                    entropy[ii] = (entropy[ii].toInt() or (1 shl (7 - jj))).toByte()
                }
            }
        }

        // Take the digest of the entropy.
        val hash = sha256(entropy)
        val hashBits = bytesToBits(hash)

        // Check all the checksum bits.
        for (ii in 0 until checksumLengthBits) {
            if (concatBits[entropyLengthBits + ii] != hashBits[ii]) {
                throw Bip39Exception.ChecksumException()
            }
        }

        return entropy
    }

    /**
     * Converts entropy to a mnemonic. Port of `MnemonicCode.toMnemonic` (dashj 22.0.3).
     *
     * @throws Bip39Exception.LengthException entropy is empty or not a multiple of 32 bits
     */
    @JvmStatic
    @Throws(Bip39Exception::class)
    fun toMnemonic(entropy: ByteArray, wordlist: Bip39Wordlist = Bip39Wordlist.ENGLISH): List<String> {
        if (entropy.size % 4 > 0) {
            throw Bip39Exception.LengthException("Entropy length not multiple of 32 bits.")
        }
        if (entropy.isEmpty()) {
            throw Bip39Exception.LengthException("Entropy is empty.")
        }

        // We take initial entropy of ENT bits and compute its checksum by taking first ENT / 32 bits of its SHA256 hash.
        val hash = sha256(entropy)
        val hashBits = bytesToBits(hash)
        val entropyBits = bytesToBits(entropy)
        val checksumLengthBits = entropyBits.size / 32

        // We append these bits to the end of the initial entropy.
        val concatBits = BooleanArray(entropyBits.size + checksumLengthBits)
        System.arraycopy(entropyBits, 0, concatBits, 0, entropyBits.size)
        System.arraycopy(hashBits, 0, concatBits, entropyBits.size, checksumLengthBits)

        // Next we take these concatenated bits and split them into groups of 11 bits. Each group encodes a number
        // from 0-2047 which is a position in a wordlist.
        val words = ArrayList<String>()
        val nwords = concatBits.size / 11
        for (i in 0 until nwords) {
            var index = 0
            for (j in 0 until 11) {
                index = index shl 1
                if (concatBits[i * 11 + j]) {
                    index = index or 0x1
                }
            }
            words.add(wordlist.wordAt(index))
        }

        return words
    }

    /**
     * Converts a mnemonic to the 64-byte BIP39 seed. Mirror of `MnemonicCode.toSeed`:
     * no wordlist involvement and no normalization — password is the words joined by a single
     * space, salt is `"mnemonic" + passphrase`, PBKDF2-HMAC-SHA512 with 2048 iterations.
     */
    @JvmStatic
    @JvmOverloads
    fun toSeed(words: List<String>, passphrase: String = ""): ByteArray {
        val pass = words.joinToString(" ")
        val salt = "mnemonic$passphrase"
        return pbkdf2HmacSha512(
            pass.toByteArray(StandardCharsets.UTF_8),
            salt.toByteArray(StandardCharsets.UTF_8),
            PBKDF2_ROUNDS,
            SEED_LENGTH
        )
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    private fun bytesToBits(data: ByteArray): BooleanArray {
        val bits = BooleanArray(data.size * 8)
        for (i in data.indices) {
            for (j in 0 until 8) {
                bits[i * 8 + j] = (data[i].toInt() and (1 shl (7 - j))) != 0
            }
        }
        return bits
    }

    /** Standard PBKDF2 (RFC 2898) with HMAC-SHA512, equivalent to dashj's `PBKDF2SHA512.derive`. */
    private fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hLen = mac.macLength // 64
        val blocks = (dkLen + hLen - 1) / hLen
        val derived = ByteArray(blocks * hLen)
        for (block in 1..blocks) {
            // U1 = PRF(P, S || INT(block))
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte()
                )
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) {
                    t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, derived, (block - 1) * hLen, hLen)
        }
        return derived.copyOf(dkLen)
    }
}
