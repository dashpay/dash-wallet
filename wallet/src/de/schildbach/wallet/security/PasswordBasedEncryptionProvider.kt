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

import org.dash.wallet.common.util.security.EncryptionProvider
import org.dash.wallet.common.util.security.MasterKeyProvider
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Password-based encryption provider that derives keys from wallet seed or recovery code
 *
 * Key features:
 * - Proper GCM usage: Generates unique IV for each encryption operation
 * - IV is prepended to encrypted data: [IV_LENGTH(4 bytes)][IV][ENCRYPTED_DATA]
 * - Keys are derived from wallet seed, making them recoverable
 * - No dependency on Android KeyStore, avoiding corruption issues
 *
 * This provider serves as a reliable fallback when KeyStore-based encryption fails
 */
class PasswordBasedEncryptionProvider(
    private val masterKeyProvider: MasterKeyProvider
) : EncryptionProvider {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private val log = LoggerFactory.getLogger(PasswordBasedEncryptionProvider::class.java)
    }

    @Throws(GeneralSecurityException::class)
    override fun encrypt(keyAlias: String, textToEncrypt: String): ByteArray {
        if (!masterKeyProvider.isAvailable()) {
            log.warn("Master key provider not available for encryption, wallet seed may not be initialized")
            throw GeneralSecurityException("Master key provider not available - wallet seed not initialized")
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = try {
            masterKeyProvider.getMasterKey(keyAlias)
        } catch (e: Exception) {
            log.error("Failed to get master key for {}: {}", keyAlias, e.message)
            throw GeneralSecurityException("Failed to derive master key from wallet seed", e)
        }

        // Always generate a new IV for each encryption (proper GCM usage)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv

        val encryptedData = cipher.doFinal(textToEncrypt.toByteArray(StandardCharsets.UTF_8))

        // Package the IV with the encrypted data
        // Format: [IV_LENGTH(4 bytes)][IV][ENCRYPTED_DATA]
        val buffer = ByteBuffer.allocate(4 + iv.size + encryptedData.size)
        buffer.putInt(iv.size)
        buffer.put(iv)
        buffer.put(encryptedData)

        log.debug("Encrypted data for alias: {} (IV: {} bytes, Data: {} bytes)",
                  keyAlias, iv.size, encryptedData.size)

        return buffer.array()
    }

    @Throws(GeneralSecurityException::class)
    override fun decrypt(keyAlias: String, encryptedData: ByteArray): String {
        if (!masterKeyProvider.isAvailable()) {
            throw GeneralSecurityException("Master key provider not available")
        }

        val buffer = ByteBuffer.wrap(encryptedData)

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
        val secretKey = masterKeyProvider.getMasterKey(keyAlias)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(encrypted)

        log.debug("Decrypted data for alias: {}", keyAlias)

        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    @Throws(KeyStoreException::class)
    override fun deleteKey(keyAlias: String) {
        // Password-based keys are derived on-demand, nothing to delete
        log.debug("Delete key requested for: {} (no-op for password-based provider)", keyAlias)
    }
}