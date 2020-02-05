package de.schildbach.wallet.ui.security;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Calendar;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

import de.schildbach.wallet.WalletApplication;

public class StaleEncryptionProvider implements EncryptionProviderFactory.EncryptionProvider {

    private static final String CIPHER_PROVIDER = "AndroidOpenSSL";

    private static final String RSA_MODE = "RSA/ECB/PKCS1Padding";
    private static final String AES_MODE = "AES/ECB/PKCS7Padding";

    private KeyStore keyStore;
    private SharedPreferences securityPrefs;

    public StaleEncryptionProvider(SharedPreferences securityPrefs, KeyStore keyStore) {
        this.securityPrefs = securityPrefs;
        this.keyStore = keyStore;
    }

    @Override
    public byte[] encrypt(String keyAlias, String textToEncrypt) throws GeneralSecurityException, IOException {
        @SuppressLint("GetInstance")
        Cipher cipher = Cipher.getInstance(AES_MODE, "BC");
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(keyAlias));
        return cipher.doFinal(textToEncrypt.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decrypt(String keyAlias, byte[] encryptedData) throws GeneralSecurityException, IOException {
        @SuppressLint("GetInstance")
        Cipher cipher = Cipher.getInstance(AES_MODE, "BC");
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(keyAlias));
        return new String(cipher.doFinal(encryptedData), StandardCharsets.UTF_8);
    }

    public void deleteKey(String keyAlias) throws KeyStoreException {
        keyStore.deleteEntry(keyAlias);
    }

    private SecretKey getSecretKey(String alias) throws GeneralSecurityException, IOException {
        if (!keyStore.containsAlias(alias)) {
            generateAndSaveKeys(alias);
        }
        String encryptedKeyStr = securityPrefs.getString("encryption_key_" + alias, null);
        byte[] encryptedKey = Base64.decode(encryptedKeyStr, Base64.NO_WRAP);
        byte[] key = rsaDecrypt(alias, encryptedKey);
        return new SecretKeySpec(key, "AES");
    }

    /**
     * Generates a pair of RSA keys (in AndroidKeyStore) and random AES key.
     * When all the keys are ready it encrypts the AES key using the RSA public key
     * and store the encrypted AES key in SharedPreferences
     */
    private void generateAndSaveKeys(String keyAlias) throws GeneralSecurityException, IOException {
        // Generate a key pair for encryption in AndroidKeyStore
        Calendar start = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 30);

        WalletApplication context = WalletApplication.getInstance();

        KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context)
                .setAlias(keyAlias)
                .setSubject(new X500Principal("CN=" + keyAlias))
                .setSerialNumber(BigInteger.TEN)
                .setStartDate(start.getTime())
                .setEndDate(end.getTime())
                .build();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", keyStore.getProvider());
        kpg.initialize(spec);
        kpg.generateKeyPair();

        // Generate and store the encrypted AES key in preferences
        byte[] key = new byte[16];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(key);
        byte[] encryptedKey = rsaEncrypt(keyAlias, key);
        String encryptedKeyStr = Base64.encodeToString(encryptedKey, Base64.NO_WRAP);
        securityPrefs.edit().putString("encryption_key_" + keyAlias, encryptedKeyStr).apply();
    }

    private byte[] rsaDecrypt(String keyAlias, byte[] encrypted)
            throws GeneralSecurityException, IOException {

        KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(keyAlias, null);
        Cipher output = Cipher.getInstance(RSA_MODE, CIPHER_PROVIDER);
        output.init(Cipher.DECRYPT_MODE, privateKeyEntry.getPrivateKey());
        CipherInputStream cipherInputStream = new CipherInputStream(new ByteArrayInputStream(encrypted), output);
        ArrayList<Byte> values = new ArrayList<>();
        int nextByte;
        while ((nextByte = cipherInputStream.read()) != -1) {
            values.add((byte) nextByte);
        }

        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = values.get(i);
        }
        return bytes;
    }

    private byte[] rsaEncrypt(String keyAlias, byte[] secret) throws GeneralSecurityException, IOException {
        KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(keyAlias, null);
        // Encrypt the text
        Cipher inputCipher = Cipher.getInstance(RSA_MODE, CIPHER_PROVIDER);
        inputCipher.init(Cipher.ENCRYPT_MODE, privateKeyEntry.getCertificate().getPublicKey());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CipherOutputStream cipherOutputStream = new CipherOutputStream(outputStream, inputCipher);
        cipherOutputStream.write(secret);
        cipherOutputStream.close();

        return outputStream.toByteArray();
    }
}