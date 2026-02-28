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
 * Migrates existing encrypted data from legacy shared-IV format to modern embedded-IV format
 *
 * Legacy format issues:
 * - Used shared IV for all encryptions (security vulnerability in GCM)
 * - IV stored separately in SharedPreferences (corruption risk)
 * - Data stored directly under key alias (no prefix)
 *
 * Modern format benefits:
 * - Unique IV per encryption (proper GCM usage)
 * - IV embedded in encrypted data (no separate storage)
 * - Data stored with "primary_" prefix (supports dual-fallback system)
 * - Self-healing capabilities via DualFallbackEncryptionProvider
 */
class ModernEncryptionMigration(
    private val securityPrefs: SharedPreferences,
    private val dualFallbackProvider: DualFallbackEncryptionProvider,
) {

    companion object {
        private val log = LoggerFactory.getLogger(ModernEncryptionMigration::class.java)

        private const val MIGRATION_COMPLETE_KEY = "modern_encryption_migration_complete"
        private const val LEGACY_ENCRYPTION_IV_KEY = "encryption_iv"

        // Old transformation for legacy decryption
        private const val LEGACY_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }

    val keyStore = dualFallbackProvider.keyStore

    /**
     * Migrate existing KeyStore-only encrypted data with shared IV to modern system
     * This runs once on app upgrade
     */
    fun migrateToModernEncryption() {
        if (securityPrefs.getBoolean(MIGRATION_COMPLETE_KEY, false)) {
            log.info("Modern encryption migration already complete")
            return
        }

        try {
            log.info("Starting modern encryption migration (legacy shared-IV to embedded-IV)")

            // Backup legacy IV before migration
            backupLegacyIV()

            // Find all keys that need migration
            val keysToMigrate = findLegacyEncryptedKeys()

            if (keysToMigrate.isEmpty()) {
                log.info("No existing keys to migrate (clean install or already migrated)")
            } else {
                log.info("Found {} keys to migrate: {}", keysToMigrate.size, keysToMigrate)

                // Try to decrypt existing data with legacy method and re-encrypt with modern format
                var migratedCount = 0
                for (keyAlias in keysToMigrate) {
                    if (migrateKey(keyAlias)) {
                        migratedCount++
                    }
                }

                log.info("Successfully migrated {}/{} keys to modern encryption system",
                    migratedCount, keysToMigrate.size)
            }

            // Mark migration complete
            securityPrefs.edit {
                putBoolean(MIGRATION_COMPLETE_KEY, true)
            }

            log.info("Modern encryption migration completed successfully")

        } catch (e: Exception) {
            log.error("Modern encryption migration failed - will retry on next app start", e)
            // Don't mark as complete so it retries
        }
    }

    /**
     * Find all legacy encrypted keys that need migration
     *
     * Returns list of key aliases that:
     * - Have encrypted data in SharedPreferences (no "primary_" prefix)
     * - Have a corresponding key in KeyStore
     * - Are not internal migration/system keys
     */
    private fun findLegacyEncryptedKeys(): List<String> {
        val legacyKeys = mutableListOf<String>()

        try {
            // Get all keys from SharedPreferences
            val allPrefs = securityPrefs.all

            for ((key, value) in allPrefs) {
                // Skip if not a string (encrypted data is stored as Base64 string)
                if (value !is String) continue

                // Skip if already in modern format (has primary_, fallback_, or dual_fallback prefix)
                if (key.startsWith("primary_") ||
                    key.startsWith("fallback_") ||
                    key.startsWith("dual_fallback_") ||
                    key == MIGRATION_COMPLETE_KEY ||
                    key == LEGACY_ENCRYPTION_IV_KEY ||
                    key == DualFallbackEncryptionProvider.KEYSTORE_HEALTHY_KEY) {
                    continue
                }

                // Check if this key has a corresponding KeyStore entry
                if (keyStore.containsAlias(key)) {
                    log.debug("Found legacy encrypted key: {}", key)
                    legacyKeys.add(key)
                }
            }

        } catch (e: Exception) {
            log.error("Error finding legacy encrypted keys", e)
        }

        return legacyKeys
    }

    /**
     * Migrate a single key from legacy format to modern format
     */
    private fun migrateKey(keyAlias: String): Boolean {
        try {
            // Check if there's existing encrypted data in old format (no prefix)
            val legacyEncryptedData = securityPrefs.getString(keyAlias, null)
            if (legacyEncryptedData.isNullOrEmpty()) {
                log.debug("No legacy data found for {}", keyAlias)
                return false
            }

            // Check if this data is already in new format (has primary_ prefix)
            if (securityPrefs.contains("primary_$keyAlias")) {
                log.info("Key {} already in modern format", keyAlias)
                // Clean up old data if new format exists
                securityPrefs.edit { remove(keyAlias) }
                return false
            }

            log.info("Migrating legacy data for {}", keyAlias)

            // Decrypt with legacy method
            val plaintext = try {
                decryptLegacyData(keyAlias, legacyEncryptedData)
            } catch (e: Exception) {
                log.error("Failed to decrypt legacy data for {}: {}", keyAlias, e.message, e)
                // If we can't decrypt it, we can't migrate it
                // Leave it for manual recovery
                return false
            }

            // Re-encrypt with dual-fallback provider (modern format with embedded IV)
            dualFallbackProvider.encrypt(keyAlias, plaintext)

            // Remove old data after successful migration
            securityPrefs.edit { remove(keyAlias) }

            log.info("Successfully migrated {} to modern encryption", keyAlias)
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
            ?: throw SecurityGuardException("Cannot migrate: KeyStore key not found for $keyAlias")

        // Decrypt using legacy method (shared IV)
        val cipher = Cipher.getInstance(LEGACY_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, legacyIv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(encryptedData)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    /**
     * Get legacy shared IV from SharedPreferences or backup files
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
     * Backup legacy IV to files before migration
     * This ensures we can recover if migration fails
     */
    private fun backupLegacyIV() {
        try {
            val ivStr = securityPrefs.getString(LEGACY_ENCRYPTION_IV_KEY, null)
            if (ivStr != null) {
                val backupDir = SecurityFileUtils.createBackupDir(WalletApplication.getInstance().filesDir)

                // Create both backup files
                val backupFile1 = java.io.File(
                    backupDir,
                    ModernEncryptionProvider.BACKUP_FILE_PREFIX + SecurityFileUtils.BACKUP_FILE_SUFFIX
                )
                val backupFile2 = java.io.File(
                    backupDir,
                    ModernEncryptionProvider.BACKUP_FILE_PREFIX + SecurityFileUtils.BACKUP2_FILE_SUFFIX
                )

                SecurityFileUtils.writeToFile(backupFile1, ivStr)
                SecurityFileUtils.writeToFile(backupFile2, ivStr)

                log.info("Legacy IV backed up to files")
            }
        } catch (e: Exception) {
            log.error("Failed to backup legacy IV", e)
        }
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

    /**
     * Check if migration is complete
     */
    fun isMigrationComplete(): Boolean {
        return securityPrefs.getBoolean(MIGRATION_COMPLETE_KEY, false)
    }

    /**
     * Check if legacy data exists that needs migration
     */
    fun hasLegacyData(): Boolean {
        val hasLegacyIV = securityPrefs.contains(LEGACY_ENCRYPTION_IV_KEY)
        val hasLegacyPassword = securityPrefs.contains(SecurityGuard.WALLET_PASSWORD_KEY_ALIAS) &&
                !securityPrefs.contains("primary_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}")
        val hasLegacyPin = securityPrefs.contains(SecurityGuard.UI_PIN_KEY_ALIAS) &&
                !securityPrefs.contains("primary_${SecurityGuard.UI_PIN_KEY_ALIAS}")

        return hasLegacyIV || hasLegacyPassword || hasLegacyPin
    }

    /**
     * Reset migration status (for testing or troubleshooting)
     */
    fun resetMigrationStatus() {
        securityPrefs.edit() {
            remove(MIGRATION_COMPLETE_KEY)
        }
        log.info("Modern encryption migration status reset")
    }
}