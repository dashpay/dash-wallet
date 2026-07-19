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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Standalone tests of the self-contained BIP39 port. The exhaustive differential tests against
 * dashj's `MnemonicCode` live in the wallet module (`Bip39DashjParityTest`), where dashj is on
 * the test classpath.
 */
class Bip39Test {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }

    // --- wordlist ---

    @Test
    fun englishWordlist_loadsAndMatchesDigest() {
        val wordlist = Bip39Wordlist.ENGLISH
        assertEquals(2048, wordlist.size)
        assertEquals("abandon", wordlist.wordAt(0))
        assertEquals("zoo", wordlist.wordAt(2047))
    }

    @Test
    fun wordLookup_isCaseInsensitive_likeDashjCollator() {
        val wordlist = Bip39Wordlist.ENGLISH
        assertEquals(0, wordlist.indexOf("abandon"))
        assertEquals(0, wordlist.indexOf("Abandon"))
        assertEquals(0, wordlist.indexOf("ABANDON"))
        assertEquals(2047, wordlist.indexOf("ZOO"))
        assertTrue(wordlist.contains("yellow"))
        assertFalse(wordlist.contains("yello"))
        assertFalse(wordlist.contains("abandonn"))
        assertFalse(wordlist.contains(""))
    }

    @Test
    fun load_rejectsWrongWordCount() {
        assertThrows(IllegalArgumentException::class.java) {
            Bip39Wordlist.load("one\ntwo\nthree".byteInputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            Bip39Wordlist.of(listOf("one", "two"))
        }
    }

    @Test
    fun load_rejectsWrongDigest() {
        val allWords = Bip39Wordlist.ENGLISH.words.joinToString("\n")
        // correct digest passes
        Bip39Wordlist.load(allWords.byteInputStream(), Bip39Wordlist.ENGLISH_WORDS_DIGEST)
        // wrong digest fails
        assertThrows(IllegalArgumentException::class.java) {
            Bip39Wordlist.load(allWords.byteInputStream(), "00".repeat(32))
        }
    }

    // --- official BIP39 vector #1 (Trezor test vectors, passphrase "TREZOR") ---

    @Test
    fun officialVector1_mnemonicAndSeed() {
        val entropy = ByteArray(16) // 00000000000000000000000000000000
        val words = Bip39.toMnemonic(entropy)
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            words.joinToString(" ")
        )
        Bip39.check(words)
        assertArrayEquals(entropy, Bip39.toEntropy(words))
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c9" +
                "2f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            hex(Bip39.toSeed(words, "TREZOR"))
        )
    }

    @Test
    fun officialVector1_emptyPassphraseSeed() {
        // The well-known BIP32 test seed for "abandon ... about" with an empty passphrase.
        val words = Bip39.toMnemonic(ByteArray(16))
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206d" +
                "ec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            hex(Bip39.toSeed(words))
        )
    }

    // --- validation errors ---

    @Test
    fun check_rejectsBadChecksum() {
        // 12x "abandon" is 11 valid words + wrong checksum word
        val words = List(12) { "abandon" }
        assertThrows(Bip39Exception.ChecksumException::class.java) { Bip39.check(words) }
        assertFalse(Bip39.isValid(words))
    }

    @Test
    fun check_rejectsUnknownWord() {
        val words = Bip39.toMnemonic(ByteArray(16)).toMutableList()
        words[3] = "grumble"
        val e = assertThrows(Bip39Exception.WordException::class.java) { Bip39.check(words) }
        assertEquals("grumble", e.badWord)
    }

    @Test
    fun check_rejectsBadLength() {
        assertThrows(Bip39Exception.LengthException::class.java) { Bip39.check(emptyList()) }
        assertThrows(Bip39Exception.LengthException::class.java) { Bip39.check(List(11) { "abandon" }) }
        assertThrows(Bip39Exception.LengthException::class.java) { Bip39.check(List(13) { "abandon" }) }
    }

    @Test
    fun check_acceptsUppercaseWords_likeDashjCollator() {
        val words = Bip39.toMnemonic(ByteArray(16)).map { it.uppercase() }
        Bip39.check(words) // must not throw — dashj's collator-based lookup accepts these too
        assertArrayEquals(ByteArray(16), Bip39.toEntropy(words))
    }

    @Test
    fun toMnemonic_rejectsBadEntropy() {
        assertThrows(Bip39Exception.LengthException::class.java) { Bip39.toMnemonic(ByteArray(0)) }
        assertThrows(Bip39Exception.LengthException::class.java) { Bip39.toMnemonic(ByteArray(15)) }
    }

    // --- roundtrips ---

    @Test
    fun entropyRoundtrip_allStandardSizes() {
        val random = Random(42)
        for (size in intArrayOf(16, 20, 24, 28, 32)) {
            repeat(20) {
                val entropy = ByteArray(size).also { random.nextBytes(it) }
                val words = Bip39.toMnemonic(entropy)
                assertEquals((size * 8 + size * 8 / 32) / 11, words.size)
                Bip39.check(words)
                assertArrayEquals(entropy, Bip39.toEntropy(words))
            }
        }
    }

    @Test
    fun toSeed_is64Bytes_andPassphraseSensitive() {
        val words = Bip39.toMnemonic(unhex("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f"))
        val seedA = Bip39.toSeed(words)
        val seedB = Bip39.toSeed(words, "x")
        assertEquals(64, seedA.size)
        assertEquals(64, seedB.size)
        assertFalse(seedA.contentEquals(seedB))
    }
}
