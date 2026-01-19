/*
 * Copyright 2025 Dash Core Group.
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

package de.schildbach.wallet.security

import org.bitcoinj.crypto.MnemonicCode
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Derives encryption keys from BIP39 mnemonic phrase
 *
 * This is used for wallet password fallback encryption, allowing recovery with just the mnemonic.
 * Works independently of whether the wallet is encrypted or not.
 *
 * Key features:
 * - Derives key from mnemonic words the user has written down
 * - Works even when wallet is encrypted (doesn't need seed bytes)
 * - Allows "Forgot PIN" recovery using mnemonic phrase
 */
class MnemonicBasedKeyProvider {

    companion object {
        private val log = LoggerFactory.getLogger(MnemonicBasedKeyProvider::class.java)

        private const val PBKDF2_ITERATIONS = 100000
        private const val KEY_LENGTH = 256
    }

    /**
     * Derive encryption key from mnemonic phrase
     *
     * @param mnemonicWords List of 12 or 24 BIP39 mnemonic words
     * @param keyAlias Identifier for the key being derived
     * @return SecretKey for encryption/decryption
     */
    @Throws(GeneralSecurityException::class)
    fun deriveKeyFromMnemonic(mnemonicWords: List<String>, keyAlias: String): SecretKey {
        if (mnemonicWords.size != 12 && mnemonicWords.size != 24) {
            throw GeneralSecurityException("Mnemonic must be 12 or 24 words")
        }

        try {
            // Validate mnemonic
            MnemonicCode.INSTANCE.check(mnemonicWords)
        } catch (e: Exception) {
            throw GeneralSecurityException("Invalid mnemonic phrase", e)
        }

        // Convert mnemonic to normalized string
        val mnemonicString = mnemonicWords.joinToString(" ").lowercase()

        // Use key alias as salt (deterministic, different for each key)
        val salt = "dash_wallet_$keyAlias".toByteArray(Charsets.UTF_8)

        // Derive key using PBKDF2
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(mnemonicString.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        val keyBytes = tmp.encoded

        log.info("Derived key from mnemonic for alias: {}", keyAlias)

        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Derive key from mnemonic phrase (space-separated string)
     */
    @Throws(GeneralSecurityException::class)
    fun deriveKeyFromMnemonic(mnemonicPhrase: String, keyAlias: String): SecretKey {
        val words = mnemonicPhrase.trim().split("\\s+".toRegex())
        return deriveKeyFromMnemonic(words, keyAlias)
    }
}