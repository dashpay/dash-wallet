//package de.schildbach.wallet.security;
//
//import android.content.SharedPreferences;
//import android.security.keystore.KeyGenParameterSpec;
//import android.security.keystore.KeyProperties;
//import android.util.Base64;
//
//import androidx.annotation.RequiresApi;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.nio.charset.StandardCharsets;
//import java.security.GeneralSecurityException;
//import java.security.KeyStore;
//import java.security.KeyStoreException;
//
//import javax.crypto.Cipher;
//import javax.crypto.KeyGenerator;
//import javax.crypto.SecretKey;
//import javax.crypto.spec.GCMParameterSpec;
//
//public class ModernEncryptionProvider implements EncryptionProviderFactory.EncryptionProvider {
//    private static Logger log = LoggerFactory.getLogger(ModernEncryptionProvider.class);
//
//    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
//
//    private KeyStore keyStore;
//    private SharedPreferences securityPrefs;
//    private byte[] encryptionIv;
//
//    public ModernEncryptionProvider(SharedPreferences securityPrefs, KeyStore keyStore) {
//        this.securityPrefs = securityPrefs;
//        this.keyStore = keyStore;
//        encryptionIv = restoreIv();
//        log.info("encryption: iv size {} bytes", encryptionIv != null ? encryptionIv.length : 0);
//    }
//
//    @Override
//    public byte[] encrypt(String keyAlias, String textToEncrypt) throws GeneralSecurityException {
//        final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
//        SecretKey secretKey = getSecretKey(keyAlias);
//
//        if (encryptionIv == null) {
//            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
//            saveIv(cipher.getIV());
//        } else {
//            GCMParameterSpec spec = new GCMParameterSpec(128, encryptionIv);
//            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
//        }
//
//        return cipher.doFinal(textToEncrypt.getBytes(StandardCharsets.UTF_8));
//    }
//
//    private void saveIv(final byte[] encryptionIv) {
//        this.encryptionIv = encryptionIv;
//        String encryptionIvStr = Base64.encodeToString(encryptionIv, Base64.NO_WRAP);
//        securityPrefs.edit().putString("encryption_iv", encryptionIvStr).apply();
//    }
//
//    private byte[] restoreIv() {
//        String encryptionIvStr = securityPrefs.getString("encryption_iv", null);
//        return encryptionIvStr != null ? Base64.decode(encryptionIvStr, Base64.NO_WRAP) : null;
//    }
//
//    @Override
//    public String decrypt(String keyAlias, byte[] encryptedData) throws GeneralSecurityException {
//        log.info("decryption: iv size {} bytes , data size: {} bytes",
//                encryptionIv != null ? encryptionIv.length : 0,
//                encryptedData != null ? encryptedData.length : 0);
//        final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
//        SecretKey secretKey = getSecretKey(keyAlias);
//        GCMParameterSpec spec = new GCMParameterSpec(128, encryptionIv);
//        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
//
//        return new String(cipher.doFinal(encryptedData), StandardCharsets.UTF_8);
//    }
//    TODO:
//    public void deleteKey(String keyAlias) throws KeyStoreException {
//        keyStore.deleteEntry(keyAlias);
//    }
//
//    private SecretKey getSecretKey(String alias) throws GeneralSecurityException {
//
//        if (!keyStore.containsAlias(alias)) {
//
//            final KeyGenerator keyGenerator = KeyGenerator
//                    .getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStore.getProvider());
//
//            keyGenerator.init(new KeyGenParameterSpec.Builder(alias,
//                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
//                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
//                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
//                    .setRandomizedEncryptionRequired(false)
//                    .build());
//
//            keyGenerator.generateKey();
//        }
//
//        return ((KeyStore.SecretKeyEntry) keyStore.getEntry(alias, null)).getSecretKey();
//    }
//}