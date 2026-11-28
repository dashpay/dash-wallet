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

package de.schildbach.wallet.util

import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import org.dash.wallet.common.crypto.bip39.Bip39
import org.dash.wallet.common.crypto.bip39.Bip39Exception
import org.dash.wallet.common.crypto.bip39.Bip39Wordlist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.Random

/**
 * Differential tests: the self-contained [Bip39] port must behave byte-for-byte like dashj's
 * [MnemonicCode] (22.0.3), which is still on the wallet test classpath during Step D prep.
 */
class Bip39DashjParityTest {

    private val dashj = MnemonicCode() // dashj's bundled English list, digest-checked
    private val ours = Bip39Wordlist.ENGLISH

    /** The wallet ships per-language wordlists as assets; locate them on disk for JVM tests. */
    private fun wordlistAssetDir(): File {
        val candidates = listOf(File("assets/bip39-wordlists"), File("wallet/assets/bip39-wordlists"))
        return candidates.firstOrNull { it.isDirectory }
            ?: fail("bip39-wordlists asset dir not found from ${File(".").absolutePath}").let { throw AssertionError() }
    }

    // --- wordlist parity ---

    @Test
    fun englishWordlists_areIdentical() {
        assertEquals(dashj.wordList, ours.words)
    }

    @Test
    fun wordLookup_matchesDashjCollatorSearch() {
        // dashj resolves words with an English PRIMARY-strength collator: case- and
        // diacritics-insensitive. Probe edge cases through the public check() APIs.
        val base = dashj.toMnemonic(ByteArray(16) { 7 })
        val variants = listOf(
            base, // plain
            base.map { it.uppercase() }, // upper case
            base.mapIndexed { i, w -> if (i == 0) w.replaceFirstChar { c -> c.uppercase() } else w } // mixed case
        )
        for (words in variants) {
            dashj.check(words) // must not throw
            Bip39.check(words, ours) // must not throw
        }
    }

    // --- valid mnemonics: toMnemonic / check / toEntropy / toSeed parity ---

    @Test
    fun randomEntropies_fullParity() {
        val random = Random(1729)
        for (size in intArrayOf(16, 20, 24, 28, 32)) {
            repeat(25) {
                val entropy = ByteArray(size).also { random.nextBytes(it) }

                val dashjWords = dashj.toMnemonic(entropy)
                val ourWords = Bip39.toMnemonic(entropy, ours)
                assertEquals(dashjWords, ourWords)

                dashj.check(dashjWords)
                Bip39.check(ourWords, ours)

                assertArrayEquals(dashj.toEntropy(dashjWords), Bip39.toEntropy(ourWords, ours))
            }
        }
    }

    @Test
    fun seedDerivation_matchesDashj_includingPassphrases() {
        val random = Random(31337)
        val passphrases = listOf("", "TREZOR", "correct horse battery staple", "pässwörd✓ 空白")
        for (size in intArrayOf(16, 32)) {
            repeat(10) {
                val entropy = ByteArray(size).also { random.nextBytes(it) }
                val words = dashj.toMnemonic(entropy)
                for (passphrase in passphrases) {
                    assertArrayEquals(
                        "seed mismatch for passphrase '$passphrase'",
                        MnemonicCode.toSeed(words, passphrase),
                        Bip39.toSeed(words, passphrase)
                    )
                }
            }
        }
    }

    @Test
    fun officialVector1_bothAgreeOnKnownSeed() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split(" ")
        val expected = MnemonicCode.toSeed(words, "TREZOR")
        assertArrayEquals(expected, Bip39.toSeed(words, "TREZOR"))
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c9" +
                "2f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            expected.joinToString("") { "%02x".format(it) }
        )
    }

    // --- invalid mnemonics: exception parity ---

    private fun dashjError(words: List<String>): Class<out MnemonicException>? = try {
        dashj.check(words)
        null
    } catch (e: MnemonicException) {
        e.javaClass
    }

    private fun oursError(words: List<String>): Class<out Bip39Exception>? = try {
        Bip39.check(words, ours)
        null
    } catch (e: Bip39Exception) {
        e.javaClass
    }

    private fun assertSameOutcome(words: List<String>) {
        val expected = when (dashjError(words)) {
            null -> null
            MnemonicException.MnemonicLengthException::class.java -> Bip39Exception.LengthException::class.java
            MnemonicException.MnemonicWordException::class.java -> Bip39Exception.WordException::class.java
            MnemonicException.MnemonicChecksumException::class.java -> Bip39Exception.ChecksumException::class.java
            else -> fail("unexpected dashj exception for $words").let { null }
        }
        assertEquals("outcome mismatch for $words", expected, oursError(words))
    }

    @Test
    fun invalidMnemonics_sameExceptionTypes() {
        val valid = dashj.toMnemonic(ByteArray(16) { 42 }).toMutableList()

        assertSameOutcome(emptyList()) // empty
        assertSameOutcome(valid.subList(0, 11)) // not multiple of 3... (11 words)
        assertSameOutcome(valid.subList(0, 9)) // multiple of 3, checksum of truncation
        assertSameOutcome(valid + "abandon") // 13 words
        assertSameOutcome(List(12) { "abandon" }) // bad checksum
        assertSameOutcome(valid.toMutableList().also { it[5] = "notaword" }) // unknown word
        assertSameOutcome(valid.toMutableList().also { it[5] = "aband" }) // prefix of a word is not a word
        assertSameOutcome(valid.toMutableList().also { it[5] = it[5] + "x" }) // suffixed word
        assertSameOutcome(valid.reversed()) // valid words, broken checksum (almost surely)
        assertSameOutcome(valid.map { it.uppercase() }) // case-insensitive accept parity

        // word-swap tamper: same words, different order
        val swapped = valid.toMutableList().also {
            val t = it[0]
            it[0] = it[1]
            it[1] = t
        }
        assertSameOutcome(swapped)
    }

    @Test
    fun badWordReported_identically() {
        val valid = dashj.toMnemonic(ByteArray(16) { 3 }).toMutableList()
        valid[7] = "zzzz"
        val dashjBad = try {
            dashj.check(valid)
            null
        } catch (e: MnemonicException.MnemonicWordException) {
            e.badWord
        }
        val ourBad = try {
            Bip39.check(valid, ours)
            null
        } catch (e: Bip39Exception.WordException) {
            e.badWord
        }
        assertEquals("zzzz", dashjBad)
        assertEquals(dashjBad, ourBad)
    }

    // --- non-English wordlists (the app's multi-language restore support) ---

    @Test
    fun allShippedWordlists_fullParity() {
        val dir = wordlistAssetDir()
        val files = dir.listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }
        assertTrue("expected several wordlists, found ${files.size}", files.size >= 9)

        val random = Random(555)
        for (file in files) {
            val dashjCode = MnemonicCode(FileInputStream(file), null)
            val ourList = Bip39Wordlist.load(FileInputStream(file))
            assertEquals("wordlist mismatch: ${file.name}", dashjCode.wordList, ourList.words)

            repeat(5) {
                val entropy = ByteArray(16).also { random.nextBytes(it) }
                val words = dashjCode.toMnemonic(entropy)
                assertEquals(words, Bip39.toMnemonic(entropy, ourList))
                dashjCode.check(words)
                Bip39.check(words, ourList)
                assertArrayEquals(dashjCode.toEntropy(words), Bip39.toEntropy(words, ourList))
                // seeds are wordlist-independent, but must agree for non-ASCII words too
                assertArrayEquals(MnemonicCode.toSeed(words, ""), Bip39.toSeed(words, ""))
            }
        }
    }

    @Test
    fun diacritics_insensitiveLookup_parity_french() {
        val dir = wordlistAssetDir()
        val french = File(dir, "french.txt")
        assertTrue(french.isFile)
        val dashjCode = MnemonicCode(FileInputStream(french), null)
        val ourList = Bip39Wordlist.load(FileInputStream(french))

        val words = dashjCode.toMnemonic(ByteArray(16) { 99 })
        // Strip diacritics from every word: dashj's PRIMARY-strength collator still accepts these.
        val stripped = words.map {
            java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
        }
        dashjCode.check(stripped) // dashj accepts
        Bip39.check(stripped, ourList) // and so do we
        assertArrayEquals(dashjCode.toEntropy(stripped), Bip39.toEntropy(stripped, ourList))
    }

    @Test
    fun wordlistWrapping_ofDashjLoadedWords_behavesTheSame() {
        // MnemonicCodeExt builds a Bip39Wordlist from MnemonicCode.getWordList(); verify that
        // wrapper validates exactly like the stream-loaded one.
        val wrapped = Bip39Wordlist.of(dashj.wordList)
        val words = dashj.toMnemonic(ByteArray(32) { 11 })
        Bip39.check(words, wrapped)
        assertArrayEquals(Bip39.toEntropy(words, ours), Bip39.toEntropy(words, wrapped))
    }
}
