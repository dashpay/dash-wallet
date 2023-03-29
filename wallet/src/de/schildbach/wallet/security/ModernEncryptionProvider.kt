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
import org.dash.wallet.common.util.security.EncryptionProvider
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ModernEncryptionProvider(
    private val keyStore: KeyStore,
    private val securityPrefs: SharedPreferences
): EncryptionProvider {
    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENCRYPTION_IV_KEY = "encryption_iv"
    }

    private var encryptionIv = restoreIv()

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
        securityPrefs.edit().putString(ENCRYPTION_IV_KEY, encryptionIvStr).apply()
    }

    private fun restoreIv(): ByteArray? {
        val encryptionIvStr = securityPrefs.getString(ENCRYPTION_IV_KEY, null)
        return if (encryptionIvStr != null) Base64.decode(encryptionIvStr, Base64.NO_WRAP) else null
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

    @Throws(GeneralSecurityException::class)
    private fun getSecretKey(alias: String): SecretKey {
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
        return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }
}
