package de.schildbach.wallet.util

import android.content.Context
import android.content.SharedPreferences
import com.google.common.base.Stopwatch
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import org.dash.wallet.common.crypto.bip39.Bip39
import org.dash.wallet.common.crypto.bip39.Bip39Exception
import org.dash.wallet.common.crypto.bip39.Bip39Wordlist
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream

/***
 * Extends MnemonicCode class with non-English wordlists support.
 *
 * Local word validation (membership + checksum) is served by the self-contained [Bip39] port;
 * this class still extends dashj's [MnemonicCode] because the global [MnemonicCode.INSTANCE]
 * feeds dashj-internal seed handling (actual wallet restore) until kill-list Step D removes it.
 */
class MnemonicCodeExt(wordstream: InputStream, wordListDigest: String?) : MnemonicCode(wordstream, wordListDigest) {

    /** The same words dashj loaded, wrapped for Bip39 validation. */
    private val bip39Wordlist: Bip39Wordlist by lazy { Bip39Wordlist.of(wordList) }

    companion object {

        private const val SHARED_PREFS_NAME = "mnemonic_code_prefs"
        private const val SHARED_PREFS_WORDLIST_KEY = "wordlist_path"
        private const val ASSETS_PATH = "bip39-wordlists"
        private const val DEFAULT_WORDLIST_PATH = "$ASSETS_PATH/english.txt"
        private val log = LoggerFactory.getLogger(MnemonicCodeExt::class.java)

        @JvmStatic
        fun getInstance(): MnemonicCodeExt {
            return INSTANCE as MnemonicCodeExt
        }

        @JvmStatic
        fun initMnemonicCode(context: Context) {
            try {
                val watch = Stopwatch.createStarted()
                val wordlistPath = getWordlistPath(context)
                INSTANCE = MnemonicCodeExt(context.assets.open(wordlistPath), null)
                watch.stop()
                log.info("BIP39 wordlist loaded from: '{}', took {}", wordlistPath, watch)
            } catch (x: IOException) {
                throw RuntimeException(x)
            }
        }

        private fun getWordlistPath(context: Context): String {
            return loadSharedPrefs(context).getString(SHARED_PREFS_WORDLIST_KEY, DEFAULT_WORDLIST_PATH)!!
        }

        private fun saveWordlistPath(context: Context, customPath: String) {
            loadSharedPrefs(context).edit().putString(SHARED_PREFS_WORDLIST_KEY, customPath).apply()
        }

        @JvmStatic
        fun clearWordlistPath(context: Context) {
            loadSharedPrefs(context).edit().remove(SHARED_PREFS_WORDLIST_KEY).apply()
        }

        private fun loadSharedPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Validates a recovery phrase the user typed and returns its words as the wordlist spells
     * them. Every word must be exactly an entry of one wordlist, this instance's or another
     * language's, and the checksum must hold in that wordlist: the same rule as iOS and the SDK.
     * The language that validates the phrase becomes the app's wordlist.
     *
     * dashj's own lookup ignores case and accents, but it derives the seed from the text it is
     * given and the SDK accepts only exact entries. A phrase accepted loosely ("medaille" for
     * "médaille") restored a different wallet in dashj and none at all in the SDK.
     *
     * @throws MnemonicException.MnemonicLengthException not 12 or 24 words
     * @throws MnemonicException.MnemonicWordException a word is in no wordlist
     * @throws MnemonicException.MnemonicChecksumException every word is known, but no wordlist
     *   validates the phrase
     */
    @Throws(MnemonicException::class)
    fun checkExact(context: Context, words: List<String>): List<String> {
        if (words.size != 12 && words.size != 24) {
            throw MnemonicException.MnemonicLengthException("Word list size must be 12 or 24")
        }
        val known = BooleanArray(words.size)
        exactWordsOrNull(words, known)?.let { return it }

        val currentWordlistPath = getWordlistPath(context)
        for (wordlistFileName in context.assets.list(ASSETS_PATH).orEmpty()) {
            val wordlistPath = "$ASSETS_PATH/$wordlistFileName"
            if (wordlistPath == currentWordlistPath) {
                continue
            }
            val mnemonicCode = MnemonicCodeExt(context.assets.open(wordlistPath), null)
            val exactWords = mnemonicCode.exactWordsOrNull(words, known) ?: continue
            saveWordlistPath(context, wordlistPath)
            INSTANCE = mnemonicCode
            return exactWords
        }
        val unknownWord = known.indexOfFirst { !it }
        throw if (unknownWord >= 0) {
            MnemonicException.MnemonicWordException(words[unknownWord])
        } else {
            MnemonicException.MnemonicChecksumException()
        }
    }

    /**
     * [words] as this instance's wordlist spells them when it validates them exactly, else null.
     * Marks in [known] each word that is an entry of this wordlist.
     */
    private fun exactWordsOrNull(words: List<String>, known: BooleanArray): List<String>? {
        words.forEachIndexed { index, word ->
            if (bip39Wordlist.exactIndexOf(word) >= 0) {
                known[index] = true
            }
        }
        return try {
            Bip39.checkExact(words, bip39Wordlist)
        } catch (x: Bip39Exception) {
            null
        }
    }
}
