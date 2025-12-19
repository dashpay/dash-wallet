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
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

/**
 * Hybrid encryption provider that combines KeyStore and password-based encryption
 *
 * Architecture:
 * - Primary: ModernEncryptionProvider (KeyStore-based, hardware-backed when available)
 * - Fallback: PasswordBasedEncryptionProvider (seed-derived, always recoverable)
 *
 * Every encryption operation saves BOTH versions for redundancy.
 * Decryption tries primary first, falls back to password-based if KeyStore fails.
 * Self-healing: Automatically re-encrypts with KeyStore after successful fallback recovery.
 *
 * This provides best-of-both-worlds:
 * - Performance and security of KeyStore when working
 * - Guaranteed recovery via seed phrase when KeyStore corrupts
 */
class HybridEncryptionProvider(
    private val primaryProvider: ModernEncryptionProvider,
    private val fallbackProvider: PasswordBasedEncryptionProvider,
    private val securityPrefs: SharedPreferences
) : EncryptionProvider {

    companion object {
        private val log = LoggerFactory.getLogger(HybridEncryptionProvider::class.java)

        // Storage keys
        private const val PRIMARY_PREFIX = "primary_"
        private const val FALLBACK_PREFIX = "fallback_"

        // Health tracking
        private const val KEYSTORE_HEALTHY_KEY = "keystore_healthy"
        private const val FALLBACK_ACTIVE_KEY = "fallback_active"
    }

    /**
     * Encrypt with BOTH systems for redundancy
     */
    @Throws(GeneralSecurityException::class)
    override fun encrypt(keyAlias: String, textToEncrypt: String): ByteArray {
        var primarySuccess = false
        var primaryData: ByteArray? = null

        // Try primary (KeyStore) encryption
        try {
            primaryData = primaryProvider.encrypt(keyAlias, textToEncrypt)
            savePrimaryData(keyAlias, primaryData)
            primarySuccess = true
            log.debug("Primary encryption succeeded for {}", keyAlias)
        } catch (e: Exception) {
            log.warn("Primary encryption failed for {}: {}", keyAlias, e.message)
            securityPrefs.edit().putBoolean(KEYSTORE_HEALTHY_KEY, false).apply()
        }

        // Try to encrypt with fallback system (if wallet seed is available)
        // If wallet isn't initialized yet, we'll add fallback encryption later via ensureFallbackEncryption()
        val fallbackData = tryFallbackEncryption(keyAlias, textToEncrypt, primarySuccess)

        // Update health status
        if (primarySuccess && fallbackData != null) {
            log.info("Dual encryption successful for {}", keyAlias)
            securityPrefs.edit {
                putBoolean(KEYSTORE_HEALTHY_KEY, true)
                putBoolean(FALLBACK_ACTIVE_KEY, false)
            }
        } else if (!primarySuccess && fallbackData != null) {
            log.warn("Only fallback encryption working for {}", keyAlias)
            securityPrefs.edit { putBoolean(FALLBACK_ACTIVE_KEY, true) }
        }

        // Return primary data if available, otherwise fallback
        return primaryData ?: fallbackData
            ?: throw GeneralSecurityException("All encryption systems failed")
    }

    /**
     * Decrypt with fallback hierarchy and self-healing
     */
    @Throws(GeneralSecurityException::class)
    override fun decrypt(keyAlias: String, encryptedData: ByteArray): String {
        // Check if we know KeyStore is broken
        val keystoreHealthy = securityPrefs.getBoolean(KEYSTORE_HEALTHY_KEY, true)
        val fallbackActive = securityPrefs.getBoolean(FALLBACK_ACTIVE_KEY, false)

        // If fallback is already active, skip primary attempt
        if (fallbackActive && !keystoreHealthy) {
            log.debug("Fallback mode active, using password-based decryption for {}", keyAlias)
            return decryptWithFallbackAndHeal(keyAlias)
        }

        // Try primary first (if we think it's healthy)
        if (keystoreHealthy) {
            try {
                val primaryData = loadPrimaryData(keyAlias)
                if (primaryData != null) {
                    val decrypted = primaryProvider.decrypt(keyAlias, primaryData)
                    log.debug("Primary decryption succeeded for {}", keyAlias)
                    return decrypted
                } else {
                    log.warn("No primary data found for {}, trying fallback", keyAlias)
                }
            } catch (e: GeneralSecurityException) {
                log.warn("Primary decryption failed for {}: {}", keyAlias, e.message)

                // Check if this is a KeyStore corruption issue
                if (isKeystoreCorruption(e)) {
                    log.error("KeyStore corruption detected for {}, switching to fallback mode", keyAlias)
                    securityPrefs.edit()
                        .putBoolean(KEYSTORE_HEALTHY_KEY, false)
                        .putBoolean(FALLBACK_ACTIVE_KEY, true)
                        .apply()
                } else {
                    // Other error, might be data corruption, still try fallback
                    log.error("Unexpected error during primary decryption", e)
                }
            }
        }

        // Primary failed or unhealthy, use fallback with self-healing
        return decryptWithFallbackAndHeal(keyAlias)
    }

    /**
     * Decrypt with fallback and attempt to heal primary encryption
     */
    private fun decryptWithFallbackAndHeal(keyAlias: String): String {
        // Try fallback decryption
        val fallbackData = loadFallbackData(keyAlias)
            ?: throw SecurityGuardException("No fallback data found for $keyAlias")

        val plaintext = try {
            fallbackProvider.decrypt(keyAlias, fallbackData)
        } catch (e: Exception) {
            log.error("Fallback decryption failed for {}", keyAlias, e)
            throw SecurityGuardException("All decryption methods failed for $keyAlias", e)
        }

        log.info("Fallback decryption succeeded for {}", keyAlias)

        // SELF-HEALING: Re-encrypt with primary to restore KeyStore encryption
        healPrimaryEncryption(keyAlias, plaintext)

        return plaintext
    }

    /**
     * Attempt to restore primary encryption if KeyStore recovers
     * This is the key self-healing mechanism
     */
    private fun healPrimaryEncryption(keyAlias: String, plaintext: String) {
        try {
            log.info("Attempting to heal primary encryption for {}", keyAlias)

            val primaryData = primaryProvider.encrypt(keyAlias, plaintext)
            savePrimaryData(keyAlias, primaryData)

            log.info("Primary encryption healed for {}", keyAlias)

            // Mark KeyStore as healthy again
            securityPrefs.edit()
                .putBoolean(KEYSTORE_HEALTHY_KEY, true)
                .putBoolean(FALLBACK_ACTIVE_KEY, false)
                .apply()

        } catch (e: Exception) {
            log.debug("Primary encryption still unhealthy for {}: {}", keyAlias, e.message)
            // Keep using fallback mode
        }
    }

    @Throws(KeyStoreException::class)
    override fun deleteKey(keyAlias: String) {
        // Delete from both systems
        try {
            primaryProvider.deleteKey(keyAlias)
        } catch (e: Exception) {
            log.warn("Failed to delete primary key for {}", keyAlias, e)
        }

        try {
            fallbackProvider.deleteKey(keyAlias)
        } catch (e: Exception) {
            log.warn("Failed to delete fallback key for {}", keyAlias, e)
        }

        // Clear stored data
        securityPrefs.edit()
            .remove(PRIMARY_PREFIX + keyAlias)
            .remove(FALLBACK_PREFIX + keyAlias)
            .apply()
    }

    /**
     * Check if exception indicates KeyStore corruption
     */
    private fun isKeystoreCorruption(e: Exception): Boolean {
        return e is AEADBadTagException ||
                e is BadPaddingException ||
                e.message?.contains("bad padding", ignoreCase = true) == true ||
                e.message?.contains("tag mismatch", ignoreCase = true) == true ||
                e.message?.contains("mac check", ignoreCase = true) == true
    }

    /**
     * Save primary encrypted data to SharedPreferences
     */
    private fun savePrimaryData(keyAlias: String, data: ByteArray) {
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        securityPrefs.edit().putString(PRIMARY_PREFIX + keyAlias, encoded).apply()
    }

    /**
     * Save fallback encrypted data to SharedPreferences
     */
    private fun saveFallbackData(keyAlias: String, data: ByteArray) {
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        securityPrefs.edit().putString(FALLBACK_PREFIX + keyAlias, encoded).apply()
    }

    /**
     * Load primary encrypted data from SharedPreferences
     */
    private fun loadPrimaryData(keyAlias: String): ByteArray? {
        val encoded = securityPrefs.getString(PRIMARY_PREFIX + keyAlias, null)
        return encoded?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    /**
     * Load fallback encrypted data from SharedPreferences
     */
    private fun loadFallbackData(keyAlias: String): ByteArray? {
        val encoded = securityPrefs.getString(FALLBACK_PREFIX + keyAlias, null)
        return encoded?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    /**
     * Try to encrypt with fallback system
     * Returns null if wallet seed is not available (instead of throwing)
     */
    private fun tryFallbackEncryption(keyAlias: String, textToEncrypt: String, primarySucceeded: Boolean): ByteArray? {
        return try {
            val data = fallbackProvider.encrypt(keyAlias, textToEncrypt)
            saveFallbackData(keyAlias, data)
            log.debug("Fallback encryption succeeded for {}", keyAlias)
            data
        } catch (e: Exception) {
            // Fallback encryption can fail if wallet seed is not yet initialized
            // This is acceptable as long as primary succeeded
            when {
                e.message?.contains("not available") == true || e.message?.contains("not initialized") == true -> {
                    log.warn("Fallback encryption skipped for {}: wallet seed not yet available (will retry later)", keyAlias)
                    if (!primarySucceeded) {
                        throw GeneralSecurityException("Primary encryption failed and fallback unavailable (wallet not initialized)", e)
                    }
                }
                else -> {
                    log.error("Fallback encryption failed for {}: {}", keyAlias, e.message)
                    if (!primarySucceeded) {
                        throw GeneralSecurityException("Both encryption systems failed", e)
                    }
                }
            }
            null
        }
    }

    /**
     * Ensure fallback encryption exists for a key
     * This should be called after wallet initialization to add fallback encryption
     * to keys that were encrypted before wallet was available
     *
     * @return true if fallback encryption was added or already exists, false if failed
     */
    fun ensureFallbackEncryption(keyAlias: String): Boolean {
        try {
            // Check if fallback already exists
            if (loadFallbackData(keyAlias) != null) {
                log.debug("Fallback encryption already exists for {}", keyAlias)
                return true
            }

            // Check if primary exists
            val primaryData = loadPrimaryData(keyAlias)
            if (primaryData == null) {
                log.debug("No primary data to create fallback for {}", keyAlias)
                return false
            }

            // Decrypt with primary to get plaintext
            val plaintext = try {
                primaryProvider.decrypt(keyAlias, primaryData)
            } catch (e: Exception) {
                log.warn("Cannot decrypt primary data to create fallback for {}: {}", keyAlias, e.message)
                return false
            }

            // Encrypt with fallback
            val fallbackData = tryFallbackEncryption(keyAlias, plaintext, true)
            if (fallbackData != null) {
                log.info("Successfully added fallback encryption for {}", keyAlias)
                return true
            } else {
                log.warn("Failed to add fallback encryption for {} (wallet still not available?)", keyAlias)
                return false
            }

        } catch (e: Exception) {
            log.error("Error ensuring fallback encryption for {}", keyAlias, e)
            return false
        }
    }

    /**
     * Ensure fallback encryption for all known encrypted keys
     * Call this after wallet initialization to upgrade existing encryptions to dual-encryption
     */
    fun ensureAllFallbackEncryptions() {
        val keysToUpgrade = listOf(
            SecurityGuard.WALLET_PASSWORD_KEY_ALIAS,
            SecurityGuard.UI_PIN_KEY_ALIAS
        )

        var upgraded = 0
        for (keyAlias in keysToUpgrade) {
            if (ensureFallbackEncryption(keyAlias)) {
                upgraded++
            }
        }

        if (upgraded > 0) {
            log.info("Upgraded {} keys to dual-encryption", upgraded)
        }
    }
}