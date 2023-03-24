package org.dash.wallet.common.util.security

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptionProvider(
    private val keyStore: KeyStore,
    private val securityPrefs: SharedPreferences
) {
    private val ENCRYPTION_IV_KEY = "encryption_iv"
    private var encryptionIv = restoreIv()

    private val cipher by lazy {
        Cipher.getInstance("AES/GCM/NoPadding")
    }

    @Throws(GeneralSecurityException::class)
    fun encrypt(keyAlias: String, textToEncrypt: String): ByteArray? {
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
    fun decrypt(keyAlias: String, encryptedData: ByteArray): String {
        val secretKey = getSecretKey(keyAlias)
        val spec = GCMParameterSpec(128, encryptionIv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return String(cipher.doFinal(encryptedData), StandardCharsets.UTF_8)
    }

    @Throws(KeyStoreException::class)
    fun deleteKey(keyAlias: String?) {
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
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(false)
                    .build()
            )
            keyGenerator.generateKey()
        }
        return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }
}
