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

    /** The same words dashj loaded, wrapped for dashj-faithful Bip39 validation. */
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

    @Throws(MnemonicException::class)
    fun check(context: Context, words: List<String>) {
        validatingInstance(context, words)
    }

    /**
     * Validates [words] like [check] and returns them in the spelling of the wordlist that
     * validated them. [check] matches case- and diacritics-insensitively, but dashj derives the
     * seed from the text it is given and the SDK accepts only exact wordlist entries, so a restore
     * must build the wallet from these words and not from the words as typed.
     */
    @Throws(MnemonicException::class)
    fun canonicalize(context: Context, words: List<String>): List<String> {
        return validatingInstance(context, words).bip39Wordlist.canonicalize(words)
    }

    /** The instance whose wordlist validates [words]: this one, or another language's. */
    @Throws(MnemonicException::class)
    private fun validatingInstance(context: Context, words: List<String>): MnemonicCodeExt {
        if (words.size != 12 && words.size != 24) {
            throw MnemonicException.MnemonicLengthException("Word list size must be 12 or 24")
        }
        return try {
            checkWords(words)
            this
        } catch (x: MnemonicException) {
            checkNonEnglish(context, words, x)
        }
    }

    /**
     * Validates against this instance's wordlist using the self-contained [Bip39] port
     * (differential-tested identical to dashj's `MnemonicCode.check`), translating
     * [Bip39Exception] subtypes 1:1 to the [MnemonicException] types callers expect.
     */
    @Throws(MnemonicException::class)
    private fun checkWords(words: List<String>) {
        try {
            Bip39.check(words, bip39Wordlist)
        } catch (x: Bip39Exception.LengthException) {
            throw MnemonicException.MnemonicLengthException(x.message)
        } catch (x: Bip39Exception.WordException) {
            throw MnemonicException.MnemonicWordException(x.badWord)
        } catch (x: Bip39Exception.ChecksumException) {
            throw MnemonicException.MnemonicChecksumException()
        }
    }

    private fun checkNonEnglish(
        context: Context,
        words: List<String>,
        initialException: MnemonicException
    ): MnemonicCodeExt {
        val currentWordlistPath = getWordlistPath(context)
        for (wordlistFileName in context.assets.list(ASSETS_PATH).orEmpty()) {
            val wordlistPath = "$ASSETS_PATH/$wordlistFileName"
            if (wordlistPath == currentWordlistPath) {
                continue
            }
            val mnemonicCode = MnemonicCodeExt(context.assets.open(wordlistPath), null)
            try {
                mnemonicCode.checkWords(words)
            } catch (x: MnemonicException) {
                continue
            }
            saveWordlistPath(context, wordlistPath)
            INSTANCE = mnemonicCode
            return mnemonicCode
        }
        // No language validates the words: report the failure against the current wordlist.
        throw initialException
    }
}
