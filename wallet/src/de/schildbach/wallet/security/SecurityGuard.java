package de.schildbach.wallet.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.dash.wallet.common.util.security.EncryptionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.util.UUID;

import de.schildbach.wallet.WalletApplication;

public class SecurityGuard {

    private static final Logger log = LoggerFactory.getLogger(SecurityGuard.class);

    public static final String SECURITY_PREFS_NAME = "security";

    private static final String UI_PIN_KEY_ALIAS = "ui_pin_key";
    private static final String WALLET_PASSWORD_KEY_ALIAS = "wallet_password_key";

    // Singleton implementation
    private static volatile SecurityGuard instance;
    private static final Object LOCK = new Object();

    private SharedPreferences securityPrefs;
    private EncryptionProvider encryptionProvider;

    private SecurityGuard() throws GeneralSecurityException, IOException {
        securityPrefs = WalletApplication.getInstance().getSharedPreferences(SECURITY_PREFS_NAME, Context.MODE_PRIVATE);
        // TODO: this is temporary to help determine why securityPrefs are empty in rare cases
        log.info("loading security guard with keys: {}", securityPrefs.getAll().keySet());
        encryptionProvider = EncryptionProviderFactory.create(securityPrefs);
    }

    public static SecurityGuard getInstance() throws GeneralSecurityException, IOException {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new SecurityGuard();
                }
            }
        }
        return instance;
    }

    // For testing and cleanup purposes
    public static synchronized void reset() {
        instance = null;
    }

    public String generateRandomPassword() {
        return UUID.randomUUID().toString();
    }

    public synchronized void savePassword(String password) throws GeneralSecurityException, IOException {
        validateKeyIntegrity(WALLET_PASSWORD_KEY_ALIAS);
        String encryptedPin = encrypt(WALLET_PASSWORD_KEY_ALIAS, password);
        securityPrefs.edit().putString(WALLET_PASSWORD_KEY_ALIAS, encryptedPin).apply();
    }

    public static boolean isConfiguredQuickCheck() {
        return WalletApplication.getInstance()
                .getSharedPreferences(SECURITY_PREFS_NAME, Context.MODE_PRIVATE)
                .contains(WALLET_PASSWORD_KEY_ALIAS);
    }

    public boolean isConfigured() {
        return securityPrefs.contains(WALLET_PASSWORD_KEY_ALIAS);
    }

    public synchronized String retrievePassword() throws SecurityGuardException {
        try {
            String encryptedPasswordStr = securityPrefs.getString(WALLET_PASSWORD_KEY_ALIAS, null);
            if (encryptedPasswordStr == null) {
                throw new SecurityGuardException("No encrypted password found");
            }
            
            byte[] encryptedPassword = Base64.decode(encryptedPasswordStr, Base64.NO_WRAP);
            log.info("encrypted password: {}", encryptedPasswordStr);
            
            String password = encryptionProvider.decrypt(WALLET_PASSWORD_KEY_ALIAS, encryptedPassword);
            log.info("decrypted password: {}", password);
            return password;
        } catch (GeneralSecurityException | IOException e) {
            log.error("Failed to retrieve password", e);
            // Attempt recovery
            if (attemptKeyRecovery(WALLET_PASSWORD_KEY_ALIAS)) {
                throw new SecurityGuardException("Password corrupted but recovery may be possible", e);
            }
            throw new SecurityGuardException("Failed to retrieve password", e);
        }
    }

    public synchronized String retrievePin() throws SecurityGuardException {
        try {
            String savedPinStr = securityPrefs.getString(UI_PIN_KEY_ALIAS, "");
            if (savedPinStr.isEmpty()) {
                throw new SecurityGuardException("No encrypted PIN found");
            }
            
            byte[] savedPin = Base64.decode(savedPinStr, Base64.NO_WRAP);
            return encryptionProvider.decrypt(UI_PIN_KEY_ALIAS, savedPin);
        } catch (GeneralSecurityException | IOException e) {
            log.error("Failed to retrieve PIN", e);
            // Attempt recovery
            if (attemptKeyRecovery(UI_PIN_KEY_ALIAS)) {
                throw new SecurityGuardException("PIN corrupted but recovery may be possible", e);
            }
            throw new SecurityGuardException("Failed to retrieve PIN", e);
        }
    }

    public synchronized void savePin(String pin) throws GeneralSecurityException, IOException {
        validateKeyIntegrity(UI_PIN_KEY_ALIAS);
        String encryptedPin = encrypt(UI_PIN_KEY_ALIAS, pin);
        securityPrefs.edit().putString(UI_PIN_KEY_ALIAS, encryptedPin).apply();
    }

    public synchronized boolean checkPin(String pin) throws GeneralSecurityException, IOException {
        validateKeyIntegrity(UI_PIN_KEY_ALIAS);
        String encryptedPin = encrypt(UI_PIN_KEY_ALIAS, pin);
        String savedPin = securityPrefs.getString(UI_PIN_KEY_ALIAS, "");
        return encryptedPin.equals(savedPin);
    }

    private String encrypt(String keyAlias, String data) throws GeneralSecurityException, IOException {
        byte[] encryptedPin = encryptionProvider.encrypt(keyAlias, data);
        return Base64.encodeToString(encryptedPin, Base64.NO_WRAP);
    }

    /**
     * Validates that key exists and can be used for encryption/decryption
     * Throws exception if key is in inconsistent state
     */
    private void validateKeyIntegrity(String keyAlias) throws GeneralSecurityException {
        try {
            // Test encryption/decryption with a small test string
            String testData = "test";
            byte[] encrypted = encryptionProvider.encrypt(keyAlias, testData);
            String decrypted = encryptionProvider.decrypt(keyAlias, encrypted);
            
            if (!testData.equals(decrypted)) {
                throw new GeneralSecurityException("Key integrity validation failed for alias: " + keyAlias);
            }
        } catch (Exception e) {
            log.error("Key validation failed for alias: {}", keyAlias, e);
            throw new GeneralSecurityException("Key validation failed", e);
        }
    }

    /**
     * Attempts to recover from corrupted key state
     * @param keyAlias the key alias to recover
     * @return true if recovery is possible, false otherwise
     */
    private boolean attemptKeyRecovery(String keyAlias) {
        log.warn("Attempting key recovery for alias: {}", keyAlias);
        try {
            // Clear the corrupted encrypted data from SharedPreferences
            securityPrefs.edit().remove(keyAlias).apply();
            
            // Delete the corrupted key from KeyStore
            encryptionProvider.deleteKey(keyAlias);
            
            log.info("Corrupted key state cleared for alias: {}", keyAlias);
            return true;
        } catch (Exception e) {
            log.error("Failed to recover key for alias: {}", keyAlias, e);
            return false;
        }
    }
    
    /**
     * Checks if the security state is recoverable after corruption
     * @return true if keys can be regenerated safely
     */
    public boolean isRecoverable() {
        try {
            // If we can create a new encryption provider, recovery is possible
            EncryptionProviderFactory.create(securityPrefs);
            return true;
        } catch (Exception e) {
            log.error("Security state is not recoverable", e);
            return false;
        }
    }

    public synchronized void removeKeys() {
        log.warn("removing security keys");
        try {
            encryptionProvider.deleteKey(UI_PIN_KEY_ALIAS);
        } catch (KeyStoreException e) {
            log.warn("unable to remove UI_PIN_KEY_ALIAS key from keystore", e);
        }
        try {
            encryptionProvider.deleteKey(WALLET_PASSWORD_KEY_ALIAS);
        } catch (KeyStoreException e) {
            log.warn("unable to remove WALLET_PASSWORD_KEY_ALIAS key from keystore", e);
        }
        securityPrefs.edit()
                .remove(UI_PIN_KEY_ALIAS)
                .remove(WALLET_PASSWORD_KEY_ALIAS)
                .apply();
    }
}