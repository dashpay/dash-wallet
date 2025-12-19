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
import de.schildbach.wallet.WalletApplication
import org.dash.wallet.common.util.security.SecurityFileUtils
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Migrates existing encrypted data from old format to hybrid encryption system
 *
 * Old format issues:
 * - Used shared IV for all encryptions (security vulnerability)
 * - IV stored separately in SharedPreferences (corruption risk)
 * - No fallback encryption (unrecoverable if KeyStore corrupts)
 *
 * New format benefits:
 * - Unique IV per encryption (proper GCM usage)
 * - IV embedded in encrypted data (no separate storage)
 * - Dual encryption (KeyStore + seed-based fallback)
 * - Self-healing when KeyStore recovers
 */
class HybridEncryptionMigration(
    private val securityPrefs: SharedPreferences,
    private val hybridProvider: HybridEncryptionProvider,
    private val keyStore: KeyStore
) {

    companion object {
        private val log = LoggerFactory.getLogger(HybridEncryptionMigration::class.java)

        private const val MIGRATION_V2_COMPLETE = "hybrid_encryption_v2_migration_complete"
        private const val LEGACY_ENCRYPTION_IV_KEY = "encryption_iv"

        // Old transformation for legacy decryption
        private const val LEGACY_TRANSFORMATION = "AES/GCM/NoPadding"
    }

    /**
     * Migrate existing KeyStore-only encrypted data to hybrid system
     * This runs once on app upgrade
     */
    fun migrateToHybridSystem() {
        if (securityPrefs.getBoolean(MIGRATION_V2_COMPLETE, false)) {
            log.info("Hybrid migration v2 already complete")
            return
        }

        try {
            log.info("Starting hybrid encryption migration v2")

            // Try to decrypt existing data with legacy method and re-encrypt with hybrid
            val passwordMigrated = migrateKey(SecurityGuard.WALLET_PASSWORD_KEY_ALIAS)
            val pinMigrated = migrateKey(SecurityGuard.UI_PIN_KEY_ALIAS)

            if (passwordMigrated || pinMigrated) {
                log.info("Successfully migrated {} keys to hybrid system",
                    (if (passwordMigrated) 1 else 0) + (if (pinMigrated) 1 else 0))
            } else {
                log.info("No existing keys to migrate (clean install or already migrated)")
            }

            // Mark migration complete
            securityPrefs.edit {
                putBoolean(MIGRATION_V2_COMPLETE, true)
            }

            log.info("Hybrid migration v2 completed successfully")

        } catch (e: Exception) {
            log.error("Hybrid migration v2 failed - will retry on next app start", e)
            // Don't mark as complete so it retries
        }
    }

    /**
     * Migrate a single key from legacy format to hybrid format
     */
    private fun migrateKey(keyAlias: String): Boolean {
        try {
            // Check if there's existing encrypted data in old format
            val legacyEncryptedData = securityPrefs.getString(keyAlias, null)
            if (legacyEncryptedData.isNullOrEmpty()) {
                log.debug("No legacy data found for {}", keyAlias)
                return false
            }

            // Check if this data is already in new format (has primary_ or fallback_ prefix)
            if (securityPrefs.contains("primary_$keyAlias") ||
                securityPrefs.contains("fallback_$keyAlias")) {
                log.info("Key {} already in new format", keyAlias)
                return false
            }

            log.info("Migrating legacy data for {}", keyAlias)

            // Decrypt with legacy method
            val plaintext = try {
                decryptLegacyData(keyAlias, legacyEncryptedData)
            } catch (e: Exception) {
                log.error("Failed to decrypt legacy data for {}: {}", keyAlias, e.message)
                // If we can't decrypt it, we can't migrate it
                // Leave it for manual recovery
                return false
            }

            // Re-encrypt with hybrid provider
            hybridProvider.encrypt(keyAlias, plaintext)

            // Remove old data after successful migration
            securityPrefs.edit().remove(keyAlias).apply()

            log.info("Successfully migrated {} to hybrid encryption", keyAlias)
            return true

        } catch (e: Exception) {
            log.error("Migration failed for {}", keyAlias, e)
            return false
        }
    }

    /**
     * Decrypt data using legacy shared-IV method
     * This is needed to migrate old encrypted data
     */
    private fun decryptLegacyData(keyAlias: String, encryptedDataStr: String): String {
        // Get legacy shared IV
        val legacyIv = getLegacyIV()
            ?: throw SecurityGuardException("Cannot migrate: legacy IV not found")

        // Decode encrypted data
        val encryptedData = Base64.decode(encryptedDataStr, Base64.NO_WRAP)

        // Get KeyStore key
        val secretKey = getKeyStoreKey(keyAlias)
            ?: throw SecurityGuardException("Cannot migrate: KeyStore key not found")

        // Decrypt using legacy method (shared IV)
        val cipher = Cipher.getInstance(LEGACY_TRANSFORMATION)
        val spec = GCMParameterSpec(128, legacyIv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(encryptedData)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    /**
     * Get legacy shared IV from backups
     */
    private fun getLegacyIV(): ByteArray? {
        // Try primary SharedPreferences
        var ivStr = securityPrefs.getString(LEGACY_ENCRYPTION_IV_KEY, null)

        if (ivStr != null) {
            log.debug("Retrieved legacy IV from SharedPreferences")
            return Base64.decode(ivStr, Base64.NO_WRAP)
        }

        // Try file backups
        val fileNames = listOf(
            ModernEncryptionProvider.BACKUP_FILE_PREFIX + SecurityFileUtils.BACKUP_FILE_SUFFIX,
            ModernEncryptionProvider.BACKUP_FILE_PREFIX + SecurityFileUtils.BACKUP2_FILE_SUFFIX
        )

        for (fileName in fileNames) {
            try {
                val backupFile = java.io.File(
                    SecurityFileUtils.createBackupDir(WalletApplication.getInstance().filesDir),
                    fileName
                )
                if (backupFile.exists()) {
                    ivStr = SecurityFileUtils.readFromFile(backupFile)
                    if (!ivStr.isNullOrEmpty()) {
                        log.debug("Retrieved legacy IV from file: {}", fileName)
                        return Base64.decode(ivStr, Base64.NO_WRAP)
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to read legacy IV from file: {}", fileName, e)
            }
        }

        log.warn("Legacy IV not found in any location")
        return null
    }

    /**
     * Get KeyStore key for migration
     */
    private fun getKeyStoreKey(alias: String): SecretKey? {
        return try {
            if (keyStore.containsAlias(alias)) {
                (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            } else {
                null
            }
        } catch (e: Exception) {
            log.error("Failed to get KeyStore key for {}", alias, e)
            null
        }
    }
}