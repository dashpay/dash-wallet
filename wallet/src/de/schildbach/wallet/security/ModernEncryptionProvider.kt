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
 *
 *  IMPORTANT: This implementation uses proper GCM encryption with unique IVs
 *  - Each encryption operation generates a fresh IV
 *  - IV is prepended to the encrypted data
 *  - No shared IV is stored (eliminates corruption issues)
 */
class ModernEncryptionProvider(
    private val keyStore: KeyStore,
    private val securityPrefs: SharedPreferences
): EncryptionProvider {

    private var backupConfig: SecurityConfig? = null
    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        // Legacy keys for migration only
        const val ENCRYPTION_IV_KEY = "encryption_iv"
        const val MIGRATION_COMPLETED_FILE = "iv_backup_migration_completed"
        const val BACKUP_FILE_PREFIX = "encryption_iv"
        private val log = LoggerFactory.getLogger(ModernEncryptionProvider::class.java)
    }

    // Lock for atomic key generation
    private val keyGenerationLock = Any()
    
    /**
     * Set the backup config (called by dependency injection)
     */
    fun setBackupConfig(backupConfig: SecurityConfig) {
        this.backupConfig = backupConfig
    }

    @Throws(GeneralSecurityException::class)
    override fun encrypt(keyAlias: String, textToEncrypt: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = getSecretKey(keyAlias)

        // Always generate a new IV for each encryption (proper GCM usage)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv

        val encryptedData = cipher.doFinal(textToEncrypt.toByteArray(StandardCharsets.UTF_8))

        // Package the IV with the encrypted data
        // Format: [IV_LENGTH(4 bytes)][IV][ENCRYPTED_DATA]
        val buffer = java.nio.ByteBuffer.allocate(4 + iv.size + encryptedData.size)
        buffer.putInt(iv.size)
        buffer.put(iv)
        buffer.put(encryptedData)

        log.debug("Encrypted data for alias: {} (IV: {} bytes, Data: {} bytes)",
                  keyAlias, iv.size, encryptedData.size)

        return buffer.array()
    }

    @Throws(GeneralSecurityException::class)
    override fun decrypt(keyAlias: String, encryptedData: ByteArray): String {
        val buffer = java.nio.ByteBuffer.wrap(encryptedData)

        // Extract IV length
        val ivLength = buffer.int

        // Validate IV length
        if (ivLength < 1 || ivLength > 32) {
            throw GeneralSecurityException("Invalid IV length: $ivLength")
        }

        // Extract IV
        val iv = ByteArray(ivLength)
        buffer.get(iv)

        // Extract encrypted data
        val encrypted = ByteArray(buffer.remaining())
        buffer.get(encrypted)

        // Decrypt
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = getSecretKey(keyAlias)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(encrypted)

        log.info("Decrypted data for alias: {}", keyAlias)

        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    @Throws(KeyStoreException::class)
    override fun deleteKey(keyAlias: String) {
        log.info("deleting $keyAlias")
        keyStore.deleteEntry(keyAlias)
    }

    @Throws(GeneralSecurityException::class)
    private fun getSecretKey(alias: String): SecretKey {
        if (!keyStore.containsAlias(alias)) {
            synchronized(keyGenerationLock) {
                log.info("key store does not have $alias, but has {}, generating new key. Stack trace:\n{}",
                    keyStore.aliases(),
                    Thread.currentThread().stackTrace.joinToString("\n") { "  at $it" })
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
}
