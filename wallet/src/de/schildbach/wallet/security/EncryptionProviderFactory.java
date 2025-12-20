package de.schildbach.wallet.security;

import static org.dash.wallet.common.util.Constants.ANDROID_KEY_STORE;

import android.content.SharedPreferences;

import de.schildbach.wallet.WalletApplication;
import org.dash.wallet.common.util.security.EncryptionProvider;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

public class EncryptionProviderFactory {

    /**
     * Create a dual-fallback encryption provider
     *
     * This provides:
     * - Primary: KeyStore encryption (hardware-backed when available)
     * - Fallback #1: PIN-derived encryption (for wallet password recovery)
     * - Fallback #2: Mnemonic-derived encryption (for PIN + wallet password recovery)
     * - Self-healing: Automatically restores KeyStore encryption after recovery
     */
    public static EncryptionProvider create(SharedPreferences securityPrefs)
            throws GeneralSecurityException, IOException {

        // Create KeyStore for primary provider
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);

        // Create primary provider (KeyStore-based)
        ModernEncryptionProvider primaryProvider = new ModernEncryptionProvider(keyStore, securityPrefs);

        // Create dual-fallback provider (Primary + PIN fallback + Mnemonic fallback)
        return new DualFallbackEncryptionProvider(primaryProvider, securityPrefs);
    }
}