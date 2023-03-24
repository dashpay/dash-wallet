package de.schildbach.wallet.security;

import static org.dash.wallet.common.util.Constants.ANDROID_KEY_STORE;

import android.content.SharedPreferences;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;

public class EncryptionProviderFactory {

    public static EncryptionProvider create(SharedPreferences securityPrefs)
            throws GeneralSecurityException, IOException {

        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);

        return new ModernEncryptionProvider(securityPrefs, keyStore);
    }

    public interface EncryptionProvider {

        byte[] encrypt(final String keyAlias, final String textToEncrypt)
                throws GeneralSecurityException, IOException;

        String decrypt(final String keyAlias, final byte[] encryptedData)
                throws GeneralSecurityException, IOException;

        void deleteKey(final String keyAlias) throws KeyStoreException;
    }

}