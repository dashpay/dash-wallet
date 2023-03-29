package de.schildbach.wallet.security;

import static org.dash.wallet.common.util.Constants.ANDROID_KEY_STORE;

import android.content.SharedPreferences;

import org.dash.wallet.common.util.security.EncryptionProvider;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

public class EncryptionProviderFactory {

    public static EncryptionProvider create(SharedPreferences securityPrefs)
            throws GeneralSecurityException, IOException {

        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);

        return new ModernEncryptionProvider(keyStore, securityPrefs);
    }
}