package de.schildbach.wallet.util

import android.content.Context
import android.content.SharedPreferences
import com.google.common.base.Stopwatch
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream

/***
 * Extends MnemonicCode class with non-English wordlists support
 */
class MnemonicCodeExt(wordstream: InputStream, wordListDigest: String?) : MnemonicCode(wordstream, wordListDigest) {

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
        if (words.size != 12) {
            throw MnemonicException.MnemonicLengthException("Word list size must be 12")
        }
        try {
            check(words)
        } catch (x: MnemonicException) {
            checkNonEnglish(context, words, x)
        }
    }

    private fun checkNonEnglish(context: Context, words: List<String>, initialException: MnemonicException) {
        val currentWordlistPath = getWordlistPath(context)
        val wordlistFiles = context.assets.list(ASSETS_PATH)
        for (wordlistFileName in wordlistFiles!!) {
            val wordlistPath = "$ASSETS_PATH/$wordlistFileName"
            if (wordlistPath == currentWordlistPath) {
                continue
            }
            val mnemonicCode = MnemonicCodeExt(context.assets.open(wordlistPath), null)
            try {
                mnemonicCode.check(words)
                saveWordlistPath(context, wordlistPath)
                INSTANCE = mnemonicCode
                break
            } catch (x: MnemonicException) {
                if (wordlistFileName == wordlistFiles.last()) {
                    // re-throw the original exception if checking failed for wordlists in all languages
                    throw initialException
                }
            }
        }
    }
}