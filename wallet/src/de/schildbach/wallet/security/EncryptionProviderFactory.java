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
     * Create a hybrid encryption provider with KeyStore and seed-based fallback
     *
     * This provides:
     * - Primary: KeyStore encryption (hardware-backed when available)
     * - Fallback: Seed-derived encryption (always recoverable)
     * - Self-healing: Automatically restores KeyStore encryption after recovery
     */
    public static EncryptionProvider create(SharedPreferences securityPrefs)
            throws GeneralSecurityException, IOException {

        // Create KeyStore for primary provider
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);

        // Create primary provider (KeyStore-based)
        ModernEncryptionProvider primaryProvider = new ModernEncryptionProvider(keyStore, securityPrefs);

        // Create master key provider (seed-derived)
        SeedBasedMasterKeyProvider masterKeyProvider = new SeedBasedMasterKeyProvider(
                WalletApplication.getInstance()
        );

        // Create fallback provider (password-based, uses seed)
        PasswordBasedEncryptionProvider fallbackProvider = new PasswordBasedEncryptionProvider(masterKeyProvider);

        // Create hybrid provider with self-healing
        return new HybridEncryptionProvider(primaryProvider, fallbackProvider, securityPrefs);
    }
}