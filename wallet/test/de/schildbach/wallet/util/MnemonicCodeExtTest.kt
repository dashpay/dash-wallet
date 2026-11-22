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
 * [MnemonicCodeExt.canonicalize] over the wordlists the app ships.
 *
 * A restore accepted "medaille" for "médaille" (and a French keyboard's "thème" for the English
 * "theme") and built the wallet from the words as typed: a different seed to dashj, and to the SDK
 * a phrase that "does not match any supported BIP-39 wordlist", so the wallet never bound and
 * stayed empty.
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

    private fun mnemonicCode(fileName: String) = MnemonicCodeExt(File(wordlistDir, fileName).inputStream(), null)

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

    @Test
    fun `an English phrase typed with capitals and an autocorrected accent comes back as the wordlist spells it`() {
        // "theme" is an English word; a French keyboard turns it into the French word "thème".
        val prefix = List(11) { "theme" }
        val words = prefix + Bip39Wordlist.ENGLISH.words.first { Bip39.isValid(prefix + it) }
        val typed = listOf("Thème", "THEME", "thème") + words.drop(3)

        assertEquals(words, MnemonicCodeExt.getInstance().canonicalize(context, typed))
        assertEquals(ENGLISH_PATH, storedWordlistPath)
    }

    @Test
    fun `a French phrase typed without its accents comes back with them, and only then has the phrase's seed`() {
        val words = accentedPhrase("french.txt")
        val typed = words.map(::withoutAccents)
        assertNotEquals(words, typed)

        val english = MnemonicCodeExt.getInstance()
        val canonical = english.canonicalize(context, typed)

        assertEquals(words, canonical)
        assertTrue(Bip39.toSeed(canonical).contentEquals(Bip39.toSeed(words)))
        assertFalse(Bip39.toSeed(typed).contentEquals(Bip39.toSeed(words)))
        // The language that validated the phrase becomes the app's wordlist, as check() does.
        assertEquals("bip39-wordlists/french.txt", storedWordlistPath)
        assertNotEquals(english, MnemonicCodeExt.getInstance())
    }

    @Test
    fun `a phrase in the current wordlist does not replace the instance`() {
        val english = MnemonicCodeExt.getInstance()
        val words = "abandon ".repeat(11).plus("about").split(" ")

        assertEquals(words, english.canonicalize(context, words))
        assertSame(english, MnemonicCodeExt.getInstance())
    }

    @Test
    fun `a phrase no wordlist validates is rejected whichever wordlist is current`() {
        val badChecksum = "abandon ".repeat(12).trim().split(" ")
        // The last wordlist being the current one used to end the search without an error.
        for (current in listOf("english.txt", wordlistFiles.last())) {
            storedWordlistPath = "bip39-wordlists/$current"
            MnemonicCode.INSTANCE = mnemonicCode(current)
            assertThrows(MnemonicException::class.java) {
                MnemonicCodeExt.getInstance().canonicalize(context, badChecksum)
            }
            assertThrows(MnemonicException::class.java) {
                MnemonicCodeExt.getInstance().check(context, badChecksum)
            }
        }
        assertThrows(MnemonicException.MnemonicLengthException::class.java) {
            MnemonicCodeExt.getInstance().canonicalize(context, badChecksum.take(11))
        }
    }

    /**
     * The SDK matches a phrase against its wordlists after NFKD normalization, so the spelling
     * [MnemonicCodeExt.canonicalize] returns has to be NFKD already for dashj, which does not
     * normalize, to derive the same seed from it.
     */
    @Test
    fun `every shipped wordlist spells its words in NFKD`() {
        for (fileName in wordlistFiles) {
            for (word in wordlist(fileName).words) {
                assertTrue("$fileName: $word", Normalizer.isNormalized(word, Normalizer.Form.NFKD))
            }
        }
    }

    private companion object {
        const val ENGLISH_PATH = "bip39-wordlists/english.txt"
    }
}
