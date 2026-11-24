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

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import org.dash.wallet.common.crypto.bip39.Bip39
import org.dash.wallet.common.crypto.bip39.Bip39Wordlist
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.text.Normalizer

/**
 * [MnemonicCodeExt.checkExact] over the wordlists the app ships.
 *
 * The restore screen used to accept "medaille" for "médaille" and build the wallet from the words
 * as typed: a different seed to dashj, and to the SDK a phrase that "does not match any supported
 * BIP-39 wordlist", so the wallet never bound and stayed empty. A phrase now has to be exactly
 * valid in one wordlist, as on iOS and in the SDK.
 */
class MnemonicCodeExtTest {
    private val wordlistDir = File("assets/bip39-wordlists")
    private val wordlistFiles = wordlistDir.list()!!.sorted()
    private var storedWordlistPath = ENGLISH_PATH
    private val context = mockk<Context>()
    private lateinit var previousInstance: MnemonicCode

    @Before
    fun setUp() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getString(any(), any()) } answers { storedWordlistPath }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            storedWordlistPath = secondArg()
            editor
        }
        val assets = mockk<AssetManager>()
        every { assets.list(any()) } returns wordlistFiles.toTypedArray()
        every { assets.open(any()) } answers { File("assets", firstArg<String>()).inputStream() }
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.assets } returns assets

        previousInstance = MnemonicCode.INSTANCE
        MnemonicCode.INSTANCE = mnemonicCode("english.txt")
    }

    @After
    fun tearDown() {
        MnemonicCode.INSTANCE = previousInstance
    }

    private fun mnemonicCode(fileName: String) =
        MnemonicCodeExt(File(wordlistDir, fileName).inputStream(), null, "bip39-wordlists/$fileName")

    private fun wordlist(fileName: String) = File(wordlistDir, fileName).inputStream().use { Bip39Wordlist.load(it) }

    /** What a keyboard without the accents produces: the base letters only. */
    private fun withoutAccents(word: String) =
        Normalizer.normalize(word, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    /** A valid phrase from [fileName]'s wordlist with at least one accented word. */
    private fun accentedPhrase(fileName: String): List<String> {
        val wordlist = wordlist(fileName)
        return (0..255).asSequence()
            .map { Bip39.toMnemonic(ByteArray(16) { i -> (it * 31 + i * 7).toByte() }, wordlist) }
            .first { words -> words.any { it != withoutAccents(it) } }
    }

    private fun nfc(words: List<String>) = words.map { Normalizer.normalize(it, Normalizer.Form.NFC) }

    @Test
    fun `an English phrase is accepted in any case, and an added accent is rejected`() {
        val prefix = List(11) { "theme" }
        val words = prefix + Bip39Wordlist.ENGLISH.words.first { Bip39.isValid(prefix + it) }
        val english = MnemonicCodeExt.getInstance()

        assertEquals(words, english.checkExact(context, words))
        assertEquals(words, english.checkExact(context, listOf("Theme", "THEME") + words.drop(2)))
        assertSame(english, MnemonicCodeExt.getInstance())

        // "thème" is a French word and "théme" is no word: neither is the English "theme".
        assertThrows(MnemonicException.MnemonicChecksumException::class.java) {
            english.checkExact(context, listOf("th\u00e8me") + words.drop(1))
        }
        val thrown = assertThrows(MnemonicException.MnemonicWordException::class.java) {
            english.checkExact(context, listOf("th\u00e9me") + words.drop(1))
        }
        assertEquals("th\u00e9me", thrown.badWord)
        assertEquals(ENGLISH_PATH, storedWordlistPath)
    }

    @Test
    fun `a French phrase is accepted with its accents in either Unicode form, and has one seed`() {
        val words = accentedPhrase("french.txt")
        val typed = nfc(words)
        assertNotEquals(words, typed)
        val english = MnemonicCodeExt.getInstance()

        assertEquals(words, english.checkExact(context, typed))
        // The language that validated the phrase becomes the app's wordlist.
        assertEquals("bip39-wordlists/french.txt", storedWordlistPath)
        assertNotEquals(english, MnemonicCodeExt.getInstance())
        assertEquals(words, MnemonicCodeExt.getInstance().checkExact(context, words))
        // dashj derives the seed from the text: the keyboard's form is a different wallet, which
        // is why the wallet is built from what this returns.
        assertFalse(Bip39.toSeed(typed).contentEquals(Bip39.toSeed(words)))
    }

    @Test
    fun `a French phrase typed without its accents is rejected, naming a word`() {
        val words = accentedPhrase("french.txt")
        val typed = words.map(::withoutAccents)
        val unknownWords = typed.filter { word -> wordlistFiles.none { wordlist(it).exactIndexOf(word) >= 0 } }
        assertTrue(unknownWords.isNotEmpty())

        val thrown = assertThrows(MnemonicException.MnemonicWordException::class.java) {
            MnemonicCodeExt.getInstance().checkExact(context, typed)
        }
        assertEquals(unknownWords.first(), thrown.badWord)
        assertEquals(ENGLISH_PATH, storedWordlistPath)
    }

    /** Eleven "caméra" and "ozone" is valid French; read loosely it is also valid English. */
    @Test
    fun `a phrase is taken in the language it is exactly valid in`() {
        val french = List(11) { "came\u0301ra" } + "ozone"
        val english = List(11) { "camera" } + "ozone"
        assertTrue(Bip39.isValid(french, wordlist("french.txt")))
        assertTrue(Bip39.isValid(french, Bip39Wordlist.ENGLISH))

        assertEquals(english, MnemonicCodeExt.getInstance().checkExact(context, english))
        assertEquals(ENGLISH_PATH, storedWordlistPath)
        assertEquals(french, MnemonicCodeExt.getInstance().checkExact(context, nfc(french)))
        assertEquals("bip39-wordlists/french.txt", storedWordlistPath)
        // And back, from the French instance.
        assertEquals(english, MnemonicCodeExt.getInstance().checkExact(context, english))
        assertEquals(ENGLISH_PATH, storedWordlistPath)
    }

    @Test
    fun `a phrase no wordlist validates is rejected whichever wordlist is current`() {
        val badChecksum = "abandon ".repeat(12).trim().split(" ")
        for (current in listOf("english.txt", wordlistFiles.last())) {
            storedWordlistPath = "bip39-wordlists/$current"
            MnemonicCode.INSTANCE = mnemonicCode(current)
            assertThrows(MnemonicException.MnemonicChecksumException::class.java) {
                MnemonicCodeExt.getInstance().checkExact(context, badChecksum)
            }
        }
        assertThrows(MnemonicException.MnemonicLengthException::class.java) {
            MnemonicCodeExt.getInstance().checkExact(context, badChecksum.take(11))
        }
    }

    /** A reset clears the saved wordlist, back to English, without reloading the instance in memory. */
    @Test
    fun `after a reset in the same process, the saved wordlist does not hide a language`() {
        val english = List(11) { "camera" } + "ozone"
        MnemonicCode.INSTANCE = mnemonicCode("french.txt")
        storedWordlistPath = ENGLISH_PATH

        assertEquals(english, MnemonicCodeExt.getInstance().checkExact(context, english))
        assertEquals(ENGLISH_PATH, storedWordlistPath)

        // And a phrase the loaded instance validates saves that instance's language again.
        val french = accentedPhrase("french.txt")
        MnemonicCode.INSTANCE = mnemonicCode("french.txt")
        storedWordlistPath = ENGLISH_PATH
        assertEquals(french, MnemonicCodeExt.getInstance().checkExact(context, french))
        assertEquals("bip39-wordlists/french.txt", storedWordlistPath)
    }

    /** Forgot-PIN looks a phrase up before the wallet has authenticated it. */
    @Test
    fun `a read-only lookup changes neither the saved wordlist nor the instance`() {
        val french = accentedPhrase("french.txt")
        val english = MnemonicCodeExt.getInstance()

        assertEquals(french, english.checkExact(context, nfc(french), selectWordlist = false))
        assertEquals(ENGLISH_PATH, storedWordlistPath)
        assertSame(english, MnemonicCodeExt.getInstance())

        MnemonicCode.INSTANCE = mnemonicCode("french.txt")
        assertEquals(french, MnemonicCodeExt.getInstance().checkExact(context, french, selectWordlist = false))
        assertEquals(ENGLISH_PATH, storedWordlistPath)
    }

    /**
     * The SDK matches a phrase against its wordlists after NFKD normalization, so the spelling
     * [MnemonicCodeExt.checkExact] returns has to be NFKD already for dashj, which does not
     * normalize, to derive the same seed from it.
     */
    @Test
    fun `every shipped wordlist spells its words in NFKD`() {
        for (fileName in wordlistFiles) {
            for (word in wordlist(fileName).words) {
                assertEquals(fileName, word, Bip39Wordlist.normalize(word))
            }
        }
    }

    private companion object {
        const val ENGLISH_PATH = "bip39-wordlists/english.txt"
    }
}
