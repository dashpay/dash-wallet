package de.schildbach.wallet.ui.security;

import android.content.SharedPreferences;
import android.os.Build;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;

public class EncryptionProviderFactory {

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";

    public static EncryptionProvider create(SharedPreferences securityPrefs)
            throws GeneralSecurityException, IOException {

        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new ModernEncryptionProvider(securityPrefs, keyStore);
        } else {
            return new StaleEncryptionProvider(securityPrefs, keyStore);
        }
    }

    public interface EncryptionProvider {

        byte[] encrypt(final String keyAlias, final String textToEncrypt)
                throws GeneralSecurityException, IOException;

        String decrypt(final String keyAlias, final byte[] encryptedData)
                throws GeneralSecurityException, IOException;

        void deleteKey(final String keyAlias) throws KeyStoreException;
    }

}