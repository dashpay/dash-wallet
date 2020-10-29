package de.schildbach.wallet.ui.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.util.UUID;

import de.schildbach.wallet.WalletApplication;

public class SecurityGuard {

    private static final Logger log = LoggerFactory.getLogger(SecurityGuard.class);

    private static final String SECURITY_PREFS_NAME = "security";

    private static final String UI_PIN_KEY_ALIAS = "ui_pin_key";
    private static final String WALLET_PASSWORD_KEY_ALIAS = "wallet_password_key";

    private SharedPreferences securityPrefs;
    private EncryptionProviderFactory.EncryptionProvider encryptionProvider;

    public SecurityGuard() throws GeneralSecurityException, IOException {
        securityPrefs = WalletApplication.getInstance().getSharedPreferences(SECURITY_PREFS_NAME, Context.MODE_PRIVATE);
        encryptionProvider = EncryptionProviderFactory.create(securityPrefs);
    }

    public String generateRandomPassword() {
        return UUID.randomUUID().toString();
    }

    public void savePassword(String password) throws GeneralSecurityException, IOException {
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

    public String retrievePassword() {
        String encryptedPasswordStr = securityPrefs.getString(WALLET_PASSWORD_KEY_ALIAS, null);
        byte[] encryptedPassword = Base64.decode(encryptedPasswordStr, Base64.NO_WRAP);
        try {
            return encryptionProvider.decrypt(WALLET_PASSWORD_KEY_ALIAS, encryptedPassword);
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public String retrievePin() {
        String savedPinStr = securityPrefs.getString(UI_PIN_KEY_ALIAS, "");
        byte[] savedPin = Base64.decode(savedPinStr, Base64.NO_WRAP);
        try {
            return encryptionProvider.decrypt(UI_PIN_KEY_ALIAS, savedPin);
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void savePin(String pin) throws GeneralSecurityException, IOException {
        String encryptedPin = encrypt(UI_PIN_KEY_ALIAS, pin);
        securityPrefs.edit().putString(UI_PIN_KEY_ALIAS, encryptedPin).apply();
    }

    public boolean checkPin(String pin) throws GeneralSecurityException, IOException {
        String encryptedPin = encrypt(UI_PIN_KEY_ALIAS, pin);
        String savedPin = securityPrefs.getString(UI_PIN_KEY_ALIAS, "");
        return encryptedPin.equals(savedPin);
    }

    private String encrypt(String keyAlias, String data) throws GeneralSecurityException, IOException {
        byte[] encryptedPin = encryptionProvider.encrypt(keyAlias, data);
        return Base64.encodeToString(encryptedPin, Base64.NO_WRAP);
    }

    public void removeKeys() {
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