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

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale

/**
 * A BIP39 wordlist with dashj-faithful word lookup.
 *
 * Self-contained port of the wordlist handling inside `org.bitcoinj.crypto.MnemonicCode`
 * (dashj 22.0.3), byte-for-byte replicating its semantics:
 *
 * - the stream is read line-by-line as UTF-8 and must contain exactly 2048 words;
 * - the optional digest is SHA-256 over the concatenated word bytes (no newlines), lowercase hex —
 *   the same check `MnemonicCode(InputStream, String)` performs;
 * - [indexOf] uses a linear scan with an English-locale [Collator] at PRIMARY strength, exactly like
 *   dashj's `MnemonicCode.search`, so lookups are case- and diacritics-insensitive
 *   (e.g. "Abandon" matches "abandon", "medaille" matches "médaille").
 */
class Bip39Wordlist private constructor(private val wordList: List<String>) {

    /** Same comparator dashj builds: English collator, PRIMARY strength (case/diacritics-insensitive). */
    private val diacriticsInsensitiveComparer: Collator = Collator.getInstance(Locale.ENGLISH).apply {
        strength = Collator.PRIMARY
    }

    val words: List<String>
        get() = wordList

    val size: Int
        get() = wordList.size

    fun wordAt(index: Int): String = wordList[index]

    /**
     * Index of [word] in this wordlist, or -1. Mirrors dashj's `MnemonicCode.search`:
     * linear scan using the English PRIMARY-strength collator.
     * (Collator is not thread-safe, hence the synchronization; result is unaffected.)
     */
    fun indexOf(word: String): Int {
        synchronized(diacriticsInsensitiveComparer) {
            for (i in wordList.indices) {
                if (diacriticsInsensitiveComparer.compare(wordList[i], word) == 0) {
                    return i
                }
            }
        }
        return -1
    }

    /** True when [word] is in this wordlist under the same matching rules as dashj's lookup. */
    fun contains(word: String): Boolean = indexOf(word) >= 0

    companion object {
        /**
         * SHA-256 (lowercase hex) of the concatenated English words — the exact digest constant
         * dashj's `MnemonicCode` uses (`BIP39_ENGLISH_SHA256`).
         */
        const val ENGLISH_WORDS_DIGEST = "ad90bf3beb7b0eb7e5acd74727dc0da96e0a280a258354e7293fb7e211ac03db"

        private const val ENGLISH_RESOURCE = "wordlist/english.txt"

        /**
         * The canonical BIP39 English wordlist, vendored from the dashj 22.0.3 jar resource
         * `org/bitcoinj/crypto/mnemonic/wordlist/english.txt` (file sha256
         * `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`, the canonical
         * BIP39 English list). Digest-checked on load.
         */
        @JvmStatic
        val ENGLISH: Bip39Wordlist by lazy {
            val stream = Bip39Wordlist::class.java.getResourceAsStream(ENGLISH_RESOURCE)
                ?: throw IOException("Missing resource: $ENGLISH_RESOURCE")
            load(stream, ENGLISH_WORDS_DIGEST)
        }

        /**
         * Loads a wordlist from [stream] (closing it), mirroring `MnemonicCode(InputStream, String)`.
         *
         * @param wordListDigest optional lowercase-hex SHA-256 over the concatenated word bytes
         * @throws IllegalArgumentException if the stream does not contain exactly 2048 words or the digest mismatches
         */
        @JvmStatic
        @JvmOverloads
        @Throws(IOException::class)
        fun load(stream: InputStream, wordListDigest: String? = null): Bip39Wordlist {
            val words = ArrayList<String>(2048)
            val md = MessageDigest.getInstance("SHA-256")
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                var word = reader.readLine()
                while (word != null) {
                    md.update(word.toByteArray(StandardCharsets.UTF_8))
                    words.add(word)
                    word = reader.readLine()
                }
            }
            require(words.size == 2048) { "input stream did not contain 2048 words" }
            if (wordListDigest != null) {
                val digest = md.digest().joinToString("") { "%02x".format(it) }
                require(digest == wordListDigest) { "wordlist digest mismatch" }
            }
            return Bip39Wordlist(words)
        }

        /** Wraps an already-loaded 2048-word list (e.g. dashj's `MnemonicCode.getWordList()`). */
        @JvmStatic
        fun of(words: List<String>): Bip39Wordlist {
            require(words.size == 2048) { "word list did not contain 2048 words" }
            return Bip39Wordlist(ArrayList(words))
        }
    }
}
