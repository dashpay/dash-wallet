/*
 * Copyright 2023 Dash Core Group.
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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import de.schildbach.wallet.WalletApplication
import org.dash.wallet.common.util.security.EncryptionProvider
import org.dash.wallet.common.util.security.SecurityFileUtils
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** This class provides GCM encryption via the KeyStore
 *  It uses a common IV
 */
class ModernEncryptionProvider(
    private val keyStore: KeyStore,
    private val securityPrefs: SharedPreferences
): EncryptionProvider {
    
    private var backupConfig: SecurityConfig? = null
    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENCRYPTION_IV_KEY = "encryption_iv"
        const val MIGRATION_COMPLETED_FILE = "iv_backup_migration_completed"
        const val BACKUP_FILE_PREFIX = "encryption_iv"
        private val log = LoggerFactory.getLogger(ModernEncryptionProvider::class.java)
    }

    private var encryptionIv = restoreIv()
    
    // Lock for atomic key generation
    private val keyGenerationLock = Any()
    
    /**
     * Set the backup config (called by dependency injection)
     */
    fun setBackupConfig(backupConfig: SecurityConfig) {
        this.backupConfig = backupConfig
        // Run migration after backup config is set
        migrateExistingIvToBackups()
    }

    @Throws(GeneralSecurityException::class)
    override fun encrypt(keyAlias: String, textToEncrypt: String): ByteArray? {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = getSecretKey(keyAlias)

        if (encryptionIv == null) {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            saveIv(cipher.iv)
        } else {
            val spec = GCMParameterSpec(128, encryptionIv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        }

        return cipher.doFinal(textToEncrypt.toByteArray(StandardCharsets.UTF_8))
    }

    private fun saveIv(encryptionIv: ByteArray) {
        this.encryptionIv = encryptionIv
        val encryptionIvStr = Base64.encodeToString(encryptionIv, Base64.NO_WRAP)
        securityPrefs.edit { putString(ENCRYPTION_IV_KEY, encryptionIvStr) }
        
        // Backup IV to multiple locations
        backupIv(encryptionIvStr)
    }
    
    private fun backupIv(ivString: String) {
        // DataStore backup
        backupConfig?.let { config ->
            try {
                kotlinx.coroutines.runBlocking {
                    config.backupEncryptionIv(ivString)
                }
                log.info("Successfully backed up IV to DataStore")
            } catch (e: Exception) {
                log.error("Failed to backup IV to DataStore", e)
            }
        }
        
        // File backup - primary
        try {
            val filesDir = WalletApplication.getInstance().filesDir
            val backupFile = java.io.File(SecurityFileUtils.createBackupDir(filesDir), BACKUP_FILE_PREFIX + SecurityFileUtils.BACKUP_FILE_SUFFIX)
            SecurityFileUtils.writeToFile(backupFile, ivString)
            
            // Secondary file backup
            val backup2File = java.io.File(SecurityFileUtils.createBackupDir(filesDir), BACKUP_FILE_PREFIX + SecurityFileUtils.BACKUP2_FILE_SUFFIX)
            SecurityFileUtils.writeToFile(backup2File, ivString)
            
            log.info("Successfully backed up IV to files")
        } catch (e: Exception) {
            log.error("Failed to backup IV to files", e)
        }
    }

    private fun restoreIv(): ByteArray? {
        // Try primary SharedPreferences first
        var encryptionIvStr = securityPrefs.getString(ENCRYPTION_IV_KEY, null)
        
        if (encryptionIvStr != null) {
            log.info("Retrieved IV from primary SharedPreferences")
            return Base64.decode(encryptionIvStr, Base64.NO_WRAP)
        }
        
        // Try DataStore backup
        backupConfig?.let { config ->
            try {
                encryptionIvStr = kotlinx.coroutines.runBlocking {
                    config.getEncryptionIv()
                }
                if (encryptionIvStr != null) {
                    log.info("Recovered IV from DataStore backup")
                    // Restore to primary SharedPreferences
                    securityPrefs.edit { putString(ENCRYPTION_IV_KEY, encryptionIvStr) }
                    return Base64.decode(encryptionIvStr, Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                log.warn("Failed to recover IV from DataStore backup", e)
            }
        }
        
        // Try file backups
        val fileRecoveredIv = recoverIvFromFiles()
        if (fileRecoveredIv != null) {
            log.info("Recovered IV from file backup")
            // Restore to primary SharedPreferences and re-backup to all locations
            securityPrefs.edit { putString(ENCRYPTION_IV_KEY, fileRecoveredIv) }
            backupIv(fileRecoveredIv)  // Re-backup to all locations
            return Base64.decode(fileRecoveredIv, Base64.NO_WRAP)
        }
        
        log.warn("No IV found in any location")
        return null
    }
    
    private fun recoverIvFromFiles(): String? {
        val fileNames = listOf(
            BACKUP_FILE_PREFIX + SecurityFileUtils.BACKUP_FILE_SUFFIX,
            BACKUP_FILE_PREFIX + SecurityFileUtils.BACKUP_FILE_SUFFIX
        )
        
        for (fileName in fileNames) {
            try {
                val backupFile = java.io.File(
                    SecurityFileUtils.createBackupDir(WalletApplication.getInstance().filesDir),
                    fileName
                )
                if (backupFile.exists()) {
                    val ivData = SecurityFileUtils.readFromFile(backupFile)
                    if (!ivData.isNullOrEmpty()) {
                        log.info("Recovered IV from file: {}", fileName)
                        return ivData
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to recover IV from file: {}", fileName, e)
            }
        }
        return null
    }

    @Throws(GeneralSecurityException::class)
    override fun decrypt(keyAlias: String, encryptedData: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = getSecretKey(keyAlias)
        val spec = GCMParameterSpec(128, encryptionIv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return String(cipher.doFinal(encryptedData), StandardCharsets.UTF_8)
    }

    @Throws(KeyStoreException::class)
    override fun deleteKey(keyAlias: String) {
        keyStore.deleteEntry(keyAlias)
    }

    /**
     * Force IV recovery from backup sources when corruption is detected
     * This allows SecurityGuard to trigger IV recovery after initialization
     */
    fun recoverIvFromBackups(): Boolean {
        try {
            log.info("Attempting to recover IV from backup sources...")
            var recoveredIv: String? = null
            // Try DataStore backup
            backupConfig?.let { config ->
                try {
                    recoveredIv = kotlinx.coroutines.runBlocking {
                        config.getEncryptionIv()
                    }
                    if (recoveredIv != null) {
                        log.info("Recovered IV from DataStore backup")
                        // Restore to primary SharedPreferences
                        securityPrefs.edit { putString(ENCRYPTION_IV_KEY, recoveredIv) }
                    }
                } catch (e: Exception) {
                    log.warn("Failed to recover IV from DataStore backup", e)
                }
            }

            if (recoveredIv == null) {
                // Try file backups
                recoveredIv = recoverIvFromFiles()
                if (recoveredIv != null) {
                    log.info("Recovered IV from file backup")
                    // Restore to primary SharedPreferences and re-backup to all locations
                    securityPrefs.edit { putString(ENCRYPTION_IV_KEY, recoveredIv) }
                }
            }

            if (recoveredIv != null) {
                encryptionIv = Base64.decode(recoveredIv, Base64.NO_WRAP)
                log.info("Successfully recovered IV from backups")
                return true
            } else {
                log.warn("No valid IV found in any backup source")
                return false
            }
        } catch (e: Exception) {
            log.error("Failed to recover IV from backups", e)
            return false
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun getSecretKey(alias: String): SecretKey {
        if (!keyStore.containsAlias(alias)) {
            synchronized(keyGenerationLock) {
                // Check again inside synchronized block
                if (!keyStore.containsAlias(alias)) {
                    val keyGenerator: KeyGenerator = KeyGenerator
                        .getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStore.provider)
                    keyGenerator.init(
                        KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(false)
                            .build()
                    )
                    keyGenerator.generateKey()
                }
            }
        }
        return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }
    
    
    /**
     * Migrate existing IV from previous versions that didn't have backup system
     */
    private fun migrateExistingIvToBackups() {
        try {
            // Check if migration has already been done using file flag
            if (SecurityFileUtils.isMigrationCompleted(WalletApplication.getInstance().filesDir, MIGRATION_COMPLETED_FILE)) {
                log.info("IV backup migration already completed")
                return
            }
            
            log.info("Starting IV backup migration for existing data")
            
            // Check if there's an existing IV in SharedPreferences
            val existingIv = securityPrefs.getString(ENCRYPTION_IV_KEY, null)
            if (existingIv != null && existingIv.isNotEmpty()) {
                log.info("Migrating existing IV to backup system")
                backupIv(existingIv)
            }
            
            // Mark migration as completed using file flag
            SecurityFileUtils.setMigrationCompleted(WalletApplication.getInstance().filesDir, MIGRATION_COMPLETED_FILE)
            log.info("IV backup migration completed successfully")
            
        } catch (e: Exception) {
            log.error("Failed to migrate existing IV to backup system", e)
        }
    }
}
