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

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import org.dash.wallet.common.util.security.EncryptionProvider
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Dual-fallback encryption provider with three layers of protection
 *
 * Architecture:
 * - Primary: KeyStore (hardware-backed when available)
 * - Fallback #1: PIN-derived (for wallet password recovery)
 * - Fallback #2: Mnemonic-derived (nuclear option - recovers everything)
 *
 * Recovery paths:
 * 1. KeyStore works → Use primary (fast, secure)
 * 2. KeyStore fails + remember PIN → Use PIN-fallback (recover wallet password)
 * 3. KeyStore fails + forgot PIN → Use mnemonic-fallback (recover PIN + wallet password)
 */
class DualFallbackEncryptionProvider(
    private val primaryProvider: ModernEncryptionProvider,
    private val securityPrefs: SharedPreferences
) : EncryptionProvider {

    companion object {
        private val log = LoggerFactory.getLogger(DualFallbackEncryptionProvider::class.java)

        // Storage prefixes
        private const val PRIMARY_PREFIX = "primary_"
        private const val FALLBACK_PIN_PREFIX = "fallback_pin_"
        private const val FALLBACK_MNEMONIC_PREFIX = "fallback_mnemonic_"

        // Encryption settings
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        // Health tracking
        const val KEYSTORE_HEALTHY_KEY = "keystore_healthy"
    }

    private val pinProvider = PinBasedKeyProvider()
    private val mnemonicProvider = MnemonicBasedKeyProvider()

    /**
     * Encrypt with all applicable fallback systems
     */
    @Throws(GeneralSecurityException::class)
    override fun encrypt(keyAlias: String, textToEncrypt: String): ByteArray {
        // Always encrypt with primary (KeyStore)
        val primaryData = try {
            primaryProvider.encrypt(keyAlias, textToEncrypt)
                .also { savePrimaryData(keyAlias, it) }
        } catch (e: Exception) {
            log.error("Primary encryption failed for {}: {}", keyAlias, e.message)
            null
        }
        securityPrefs.edit {
            putBoolean(KEYSTORE_HEALTHY_KEY, true)
        }

        // Note: Fallback encryption will be added later via ensureFallbackEncryptions()
        // This is because PIN/mnemonic may not be available during initial encryption

        return primaryData ?: throw GeneralSecurityException("Primary encryption failed")
    }

    /**
     * Decrypt with fallback hierarchy
     */
    @Throws(GeneralSecurityException::class)
    override fun decrypt(keyAlias: String, encryptedData: ByteArray): String {
        // Check if testing mode is forcing KeyStore failure
        if (FallbackTestingUtils.isKeystoreForcedToFail()) {
            log.warn("⚠️ TESTING MODE: Forcing KeyStore failure for {}", keyAlias)
            throw SecurityGuardException("Forced KeyStore failure for testing")
        }

        // Try primary first
        val primaryData = loadPrimaryData(keyAlias)
        if (primaryData != null) {
            try {
                val decrypted = primaryProvider.decrypt(keyAlias, primaryData)
                log.debug("Primary decryption succeeded for {}", keyAlias)
                // securityPrefs.edit { putBoolean(KEYSTORE_HEALTHY_KEY, true) }
                return decrypted
            } catch (e: GeneralSecurityException) {
                if (isKeystoreCorruption(e)) {
                    log.error("KeyStore corruption detected for {}, trying fallbacks", keyAlias, e)
                    securityPrefs.edit { putBoolean(KEYSTORE_HEALTHY_KEY, false) }
                } else {
                    log.warn("Primary decryption failed for {}: {}", keyAlias, e.message, e)
                }
            }
        }

        // Primary failed - need user input for fallback decryption
        throw SecurityGuardException(
            "Primary decryption failed. User intervention required for fallback recovery."
        )
    }

    /**
     * Decrypt with PIN-based fallback (for wallet password only)
     */
    @Throws(GeneralSecurityException::class)
    fun decryptWithPin(keyAlias: String, pin: String): String {
        if (keyAlias != SecurityGuard.WALLET_PASSWORD_KEY_ALIAS) {
            throw GeneralSecurityException("PIN-based fallback only available for wallet password")
        }

        val fallbackData = loadPinFallbackData(keyAlias)
            ?: throw SecurityGuardException("No PIN-fallback data found for $keyAlias")

        val key = pinProvider.deriveKeyFromPin(pin, keyAlias)
        val plaintext = decryptWithKey(key, fallbackData)

        log.info("PIN-fallback decryption succeeded for {}", keyAlias)

        // Self-heal: re-encrypt with primary
        tryHealPrimary(keyAlias, plaintext)
        tryHealPrimary(SecurityGuard.UI_PIN_KEY_ALIAS, pin)
        return plaintext
    }

    /**
     * Decrypt with mnemonic-based fallback (for both PIN and wallet password)
     */
    @Throws(GeneralSecurityException::class)
    fun decryptWithMnemonic(keyAlias: String, mnemonicWords: List<String>): String {
        val fallbackData = loadMnemonicFallbackData(keyAlias)
            ?: throw SecurityGuardException("No mnemonic-fallback data found for $keyAlias")

        val key = mnemonicProvider.deriveKeyFromMnemonic(mnemonicWords, keyAlias)
        val plaintext = decryptWithKey(key, fallbackData)

        log.info("Mnemonic-fallback decryption succeeded for {}", keyAlias)

        // Self-heal: re-encrypt with primary
        tryHealPrimary(keyAlias, plaintext)

        return plaintext
    }

    /**
     * Add PIN-based fallback encryption for wallet password
     * Call this after user sets/enters PIN
     */
    fun ensurePinFallback(pin: String): Boolean {
        val keyAlias = SecurityGuard.WALLET_PASSWORD_KEY_ALIAS

        try {
            // Check if already exists
            if (loadPinFallbackData(keyAlias) != null) {
                log.debug("PIN-fallback already exists for {}", keyAlias)
                return true
            }

            // Get plaintext from primary
            val primaryData = loadPrimaryData(keyAlias) ?: return false
            val plaintext = primaryProvider.decrypt(keyAlias, primaryData)
//            if (plaintext != pin) {
//                throw IllegalStateException()
//            }

            // Encrypt with PIN-derived key
            val key = pinProvider.deriveKeyFromPin(pin, keyAlias)
            val encrypted = encryptWithKey(key, plaintext)
            savePinFallbackData(keyAlias, encrypted)

            log.info("Added PIN-fallback for {}", keyAlias)
            return true

        } catch (e: Exception) {
            log.error("Failed to add PIN-fallback for {}: ", keyAlias, e)
            return false
        }
    }

    /**
     * Add mnemonic-based fallback encryption for both PIN and wallet password
     * Call this after wallet is initialized
     */
    fun ensureMnemonicFallbacks(mnemonicWords: List<String>): Boolean {
        val keysToProtect = listOf(
            SecurityGuard.UI_PIN_KEY_ALIAS,
            SecurityGuard.WALLET_PASSWORD_KEY_ALIAS
        )

        var success = 0
        for (keyAlias in keysToProtect) {
            try {
                // Check if already exists
                if (loadMnemonicFallbackData(keyAlias) != null) {
                    log.debug("Mnemonic-fallback already exists for {}", keyAlias)
                    success++
                    continue
                }

                // Get plaintext from primary
                val primaryData = loadPrimaryData(keyAlias) ?: continue
                val plaintext = primaryProvider.decrypt(keyAlias, primaryData)

                // Encrypt with mnemonic-derived key
                val key = mnemonicProvider.deriveKeyFromMnemonic(mnemonicWords, keyAlias)
                val encrypted = encryptWithKey(key, plaintext)
                saveMnemonicFallbackData(keyAlias, encrypted)

                log.info("Added mnemonic-fallback for {}", keyAlias)
                success++

            } catch (e: Exception) {
                log.error("Failed to add mnemonic-fallback for {}: {}", keyAlias, e.message)
            }
        }

        return success == keysToProtect.size
    }

    @Throws(KeyStoreException::class)
    override fun deleteKey(keyAlias: String) {
        primaryProvider.deleteKey(keyAlias)

        securityPrefs.edit {
            remove(PRIMARY_PREFIX + keyAlias)
                .remove(FALLBACK_PIN_PREFIX + keyAlias)
                .remove(FALLBACK_MNEMONIC_PREFIX + keyAlias)
        }
    }

    // Storage methods
    private fun savePrimaryData(keyAlias: String, data: ByteArray) {
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        securityPrefs.edit().putString(PRIMARY_PREFIX + keyAlias, encoded).apply()
    }

    private fun savePinFallbackData(keyAlias: String, data: ByteArray) {
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        securityPrefs.edit().putString(FALLBACK_PIN_PREFIX + keyAlias, encoded).apply()
    }

    private fun saveMnemonicFallbackData(keyAlias: String, data: ByteArray) {
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        securityPrefs.edit().putString(FALLBACK_MNEMONIC_PREFIX + keyAlias, encoded).apply()
    }

    private fun loadPrimaryData(keyAlias: String): ByteArray? {
        val encoded = securityPrefs.getString(PRIMARY_PREFIX + keyAlias, null)
        return encoded?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    private fun loadPinFallbackData(keyAlias: String): ByteArray? {
        val encoded = securityPrefs.getString(FALLBACK_PIN_PREFIX + keyAlias, null)
        return encoded?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    private fun loadMnemonicFallbackData(keyAlias: String): ByteArray? {
        val encoded = securityPrefs.getString(FALLBACK_MNEMONIC_PREFIX + keyAlias, null)
        return encoded?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    // Encryption/Decryption helpers
    private fun encryptWithKey(key: javax.crypto.SecretKey, plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encryptedData = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        // Package: [IV_LENGTH][IV][ENCRYPTED_DATA]
        return ByteBuffer.allocate(4 + iv.size + encryptedData.size)
            .putInt(iv.size)
            .put(iv)
            .put(encryptedData)
            .array()
    }

    private fun decryptWithKey(key: javax.crypto.SecretKey, encryptedData: ByteArray): String {
        val buffer = ByteBuffer.wrap(encryptedData)

        val ivLength = buffer.int
        val iv = ByteArray(ivLength)
        buffer.get(iv)

        val encrypted = ByteArray(buffer.remaining())
        buffer.get(encrypted)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val decryptedBytes = cipher.doFinal(encrypted)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    private fun tryHealPrimary(keyAlias: String, plaintext: String) {
        try {
            val primaryData = primaryProvider.encrypt(keyAlias, plaintext)
            savePrimaryData(keyAlias, primaryData)
            securityPrefs.edit { putBoolean(KEYSTORE_HEALTHY_KEY, true) }
            log.info("Primary encryption healed for {}", keyAlias)
        } catch (e: Exception) {
            log.debug("Primary encryption still unhealthy for {}", keyAlias)
        }
    }

    private fun isKeystoreCorruption(e: Exception): Boolean {
        return e is AEADBadTagException ||
                e is BadPaddingException ||
                e.message?.contains("bad padding", ignoreCase = true) == true ||
                e.message?.contains("tag mismatch", ignoreCase = true) == true
    }

    fun isKeyStoreHealthy() = securityPrefs.getBoolean(KEYSTORE_HEALTHY_KEY, true)
    fun hasPinFallback(alias: String): Boolean {
        return securityPrefs.contains(FALLBACK_PIN_PREFIX + alias)
    }
    fun hasMnemonicFallback(alias: String) = securityPrefs.contains(FALLBACK_MNEMONIC_PREFIX + alias)
}