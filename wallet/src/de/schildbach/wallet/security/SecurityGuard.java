/*
 * Copyright 2020 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.VisibleForTesting;

import org.dash.wallet.common.services.analytics.AnalyticsService;
import org.dash.wallet.common.util.security.EncryptionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet_test.BuildConfig;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.Dispatchers;
import org.dash.wallet.common.data.SecuritySystemStatus;
import org.dash.wallet.common.util.security.SecurityFileUtils;

public class SecurityGuard {

    private static final Logger log = LoggerFactory.getLogger(SecurityGuard.class);

    public static final String SECURITY_PREFS_NAME = "security";

    static final String UI_PIN_KEY_ALIAS = "ui_pin_key";
    static final String WALLET_PASSWORD_KEY_ALIAS = "wallet_password_key";

    static final String MIGRATION_COMPLETED_FILE = "backup_migration_completed";
    // Singleton implementation
    private static volatile SecurityGuard instance;
    private static final Object LOCK = new Object();
    private AnalyticsService analyticsService;

    private final SharedPreferences securityPrefs;
    private final EncryptionProvider encryptionProvider;
    private final File backupDir;
    private SecurityConfig backupConfig;
    private DualFallbackMigration dualFallbackMigration;
    private ModernEncryptionMigration modernEncryptionMigration;
    // Health listener system
    private final CopyOnWriteArrayList<HealthListener> healthListeners = new CopyOnWriteArrayList<>();
    private SharedPreferences.OnSharedPreferenceChangeListener securityPrefsListener;

    /**
     * Listener interface for security health status changes
     */
    public interface HealthListener {
        /**
         * Called when the security system health status changes
         * @param status The new security system status
         */
        void onHealthChanged(SecuritySystemStatus status);
    }

    private SecurityGuard() throws GeneralSecurityException, IOException {
        backupDir = WalletApplication.getInstance().getFilesDir();
        securityPrefs = WalletApplication.getInstance().getSharedPreferences(SECURITY_PREFS_NAME, Context.MODE_PRIVATE);
        // TODO: this is temporary to help determine why securityPrefs are empty in rare cases
        logState();
        encryptionProvider = EncryptionProviderFactory.create(securityPrefs);

        // Initialize dual-fallback migration (wallet provider will be set later)
        if (encryptionProvider instanceof DualFallbackEncryptionProvider) {
            dualFallbackMigration = new DualFallbackMigration(
                    securityPrefs,
                    (DualFallbackEncryptionProvider) encryptionProvider,
                    null  // WalletDataProvider will be set when wallet loads
            );
            modernEncryptionMigration = new ModernEncryptionMigration(
                    securityPrefs,
                    (DualFallbackEncryptionProvider) encryptionProvider
            );
            modernEncryptionMigration.migrateToModernEncryption();
            // dualFallbackMigration.migrateToMnemonicFallbacks();
            migrate();
        }

        // Backup config will be injected separately to avoid circular dependency
        backupConfig = null;
        analyticsService = WalletApplication.getInstance().getAnalyticsService();

        // Setup health monitoring system
        setupHealthMonitoring();
    }

    private void migrate() {
        modernEncryptionMigration.migrateToModernEncryption();
        dualFallbackMigration.migrateToFallbacks(this);
    }

    private void logState() {
        if (BuildConfig.DEBUG) {
            StringBuffer buffer = new StringBuffer();
            buffer.append("Security Guard Preferences:\n");

            securityPrefs.getAll().forEach(new BiConsumer<String, Object>() {
                @Override
                public void accept(String s, Object o) {
                    buffer.append("  ").append(s).append(":");
                    buffer.append(o.toString());
                    buffer.append("\n");
                }
            });
            log.info(buffer.toString());
        } else {
            log.info(
                "loading security guard [healthy={}] with keys: {}",
                isHealthy(),
                securityPrefs.getAll().keySet()
            );
        }
    }

    private SecurityGuard(SharedPreferences securityPrefs) throws GeneralSecurityException, IOException {
        backupDir = WalletApplication.getInstance().getFilesDir();
        this.securityPrefs = securityPrefs;
        // TODO: this is temporary to help determine why securityPrefs are empty in rare cases
        logState();
        encryptionProvider = EncryptionProviderFactory.create(securityPrefs);

        // Initialize dual-fallback migration (wallet provider will be set later)
        if (encryptionProvider instanceof DualFallbackEncryptionProvider) {
            dualFallbackMigration = new DualFallbackMigration(
                    securityPrefs,
                    (DualFallbackEncryptionProvider) encryptionProvider,
                    null  // WalletDataProvider will be set when wallet loads
            );
            modernEncryptionMigration = new ModernEncryptionMigration(
                    securityPrefs,
                    (DualFallbackEncryptionProvider) encryptionProvider
            );
            modernEncryptionMigration.migrateToModernEncryption();
            // dualFallbackMigration.migrateToMnemonicFallbacks();
            migrate();
        }

        // Backup config will be injected separately to avoid circular dependency
        backupConfig = null;
        analyticsService = WalletApplication.getInstance().getAnalyticsService();

        // Setup health monitoring system
        setupHealthMonitoring();
    }

    public static SecurityGuard getInstance() throws GeneralSecurityException, IOException {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new SecurityGuard();
                }
            }
        }
        instance.logState();
        // log.info("loading security guard with keys: {}", instance.securityPrefs.getAll().keySet());
        return instance;
    }

    @VisibleForTesting
    static SecurityGuard getTestInstance(SharedPreferences securityPrefs) throws GeneralSecurityException, IOException {
        return new SecurityGuard(securityPrefs);
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

        // DualFallbackEncryptionProvider handles storage internally with primary_ and fallback_ prefixes
        encryptionProvider.encrypt(WALLET_PASSWORD_KEY_ALIAS, password);
    }

    public static boolean isConfiguredQuickCheck() {
        SharedPreferences prefs = WalletApplication.getInstance()
                .getSharedPreferences(SECURITY_PREFS_NAME, Context.MODE_PRIVATE);

        // Check for dual-fallback system format (primary or fallbacks)
        if (prefs.contains("primary_" + WALLET_PASSWORD_KEY_ALIAS) ||
            prefs.contains("fallback_pin_" + WALLET_PASSWORD_KEY_ALIAS) ||
            prefs.contains("fallback_mnemonic_" + WALLET_PASSWORD_KEY_ALIAS)) {
            return true;
        }

        // Check for legacy format (backward compatibility during migration)
        return prefs.contains(WALLET_PASSWORD_KEY_ALIAS);
    }

    public boolean isConfigured() {
        // Check for dual-fallback system format (primary or fallbacks)
        if (securityPrefs.contains("primary_" + WALLET_PASSWORD_KEY_ALIAS) ||
            securityPrefs.contains("fallback_pin_" + WALLET_PASSWORD_KEY_ALIAS) ||
            securityPrefs.contains("fallback_mnemonic_" + WALLET_PASSWORD_KEY_ALIAS)) {
            return true;
        }

        // Check for legacy format (backward compatibility during migration)
        return securityPrefs.contains(WALLET_PASSWORD_KEY_ALIAS);
    }

    public synchronized String retrievePassword() throws SecurityGuardException {
        try {
            // DualFallbackEncryptionProvider handles loading from primary_/fallback_ prefixes internally
            // We just pass a dummy byte array since the parameter is ignored
            String password = encryptionProvider.decrypt(WALLET_PASSWORD_KEY_ALIAS, new byte[0]);
            if (BuildConfig.DEBUG) {
                log.info("password = {}", password);
            }
            return password;
        } catch (GeneralSecurityException | IOException e) {
            log.error("Failed to retrieve password", e);
            analyticsService.logError(e, "Failed to retrieve password");
            throw new SecurityGuardException("Failed to retrieve password", e);
        }
    }

    public synchronized String retrievePin() throws SecurityGuardException {
        try {
            // DualFallbackEncryptionProvider handles loading from primary_/fallback_ prefixes internally
            // We just pass a dummy byte array since the parameter is ignored
            return encryptionProvider.decrypt(UI_PIN_KEY_ALIAS, new byte[0]);
        } catch (GeneralSecurityException | IOException e) {
            log.error("Failed to retrieve PIN", e);
            analyticsService.logError(e, "Failed to retrieve PIN");
            throw new SecurityGuardException("Failed to retrieve PIN", e);
        }
    }

    public synchronized void savePin(String pin) throws GeneralSecurityException, IOException {
        validateKeyIntegrity(UI_PIN_KEY_ALIAS);

        // DualFallbackEncryptionProvider handles storage internally with primary_ prefix
        encryptionProvider.encrypt(UI_PIN_KEY_ALIAS, pin);
    }

    public synchronized boolean checkPin(String pin) throws GeneralSecurityException, IOException, SecurityGuardException {
        try {
            // Retrieve the stored PIN and compare with input
            String storedPin = retrievePin();
            return pin.equals(storedPin);
        } catch (Exception e) {
            log.error("PIN check failed: {}", e.getMessage());
            throw e;
        }
    }

    private String encrypt(String keyAlias, String data) throws GeneralSecurityException, IOException {
        byte[] encryptedPin = encryptionProvider.encrypt(keyAlias, data);
        return Base64.encodeToString(encryptedPin, Base64.NO_WRAP);
    }

    public boolean validateKeyIntegrity() {
        try {
            retrievePassword();
            retrievePin();
            return true;
        } catch (Exception e) {
            notifyHealthListeners(calculateHealthStatus());
            return false;
        }
    }

    /**
     * Validates that key exists and can be used for encryption/decryption
     * Throws exception if key is in inconsistent state
     *
     * This is a destructive call as it will replace the encrypted data with "test"
     *
     * It should only be called before savePin and savePassword.
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
     * Set the backup config (called by dependency injection)
     */
    public void setBackupConfig(SecurityConfig backupConfig) {
        this.backupConfig = backupConfig;
        // Run migration after backup config is set
        migrateExistingDataToBackups();
    }

    @VisibleForTesting
    void setBackupConfigForTesting(SecurityConfig backupConfig) {
        this.backupConfig = backupConfig;
        if (this.encryptionProvider instanceof ModernEncryptionProvider) {
            ((ModernEncryptionProvider) encryptionProvider).setBackupConfig(backupConfig);
        }
        // Run migration after backup config is set
        migrateExistingDataToBackups();
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

        // Remove all keys (legacy, hybrid, and dual-fallback formats)
        securityPrefs.edit()
                .remove(UI_PIN_KEY_ALIAS)
                .remove(WALLET_PASSWORD_KEY_ALIAS)
                .remove("primary_" + UI_PIN_KEY_ALIAS)
                .remove("primary_" + WALLET_PASSWORD_KEY_ALIAS)
                .remove("fallback_pin_" + UI_PIN_KEY_ALIAS)
                .remove("fallback_pin_" + WALLET_PASSWORD_KEY_ALIAS)
                .remove("fallback_mnemonic_" + UI_PIN_KEY_ALIAS)
                .remove("fallback_mnemonic_" + WALLET_PASSWORD_KEY_ALIAS)
                .apply();

        // Clear backups
        clearBackups();
    }

    /**
     * Backup encrypted data to multiple locations: DataStore and file system
     * Made synchronous to avoid race conditions
     */
    private void backupEncryptedData(String keyAlias, String encryptedData) {
        if (backupConfig != null) {
            try {
                if (WALLET_PASSWORD_KEY_ALIAS.equals(keyAlias)) {
                    BuildersKt.runBlocking(
                        Dispatchers.getIO(),
                        (scope, continuation) -> backupConfig.backupWalletPassword(encryptedData, continuation)
                    );
                } else if (UI_PIN_KEY_ALIAS.equals(keyAlias)) {
                    BuildersKt.runBlocking(
                        Dispatchers.getIO(),
                        (scope, continuation) -> backupConfig.backupUiPin(encryptedData, continuation)
                    );
                }
                log.info("Successfully backed up {} to DataStore", keyAlias);
            } catch (Exception e) {
                log.error("Failed to backup {} to DataStore", keyAlias, e);
            }
        }
        
        // File backup
        backupToFile(keyAlias, encryptedData);
    }

    /**
     * Backup data to file system
     */
    private void backupToFile(String keyAlias, String data) {
        try {
            File filesDir = backupDir;
            
            // Primary backup
            File backupFile = SecurityFileUtils.INSTANCE.getBackupFile(filesDir, keyAlias, true);
            SecurityFileUtils.INSTANCE.writeToFile(backupFile, data);
            
            // Secondary backup
            File backup2File = SecurityFileUtils.INSTANCE.getBackupFile(filesDir, keyAlias, false);
            SecurityFileUtils.INSTANCE.writeToFile(backup2File, data);
            
            log.info("Successfully backed up {} to files", keyAlias);
        } catch (Exception e) {
            log.error("Failed to backup {} to file", keyAlias, e);
        }
    }

    /**
     * Recover encrypted data from multiple backup sources with validation
     */
    private String recoverEncryptedData(String keyAlias) {
        // Try primary SharedPreferences first
        String data = securityPrefs.getString(keyAlias, null);
        if (isValidEncryptedData(keyAlias, data)) {
            log.info("Retrieved {} from primary SharedPreferences", keyAlias);
            return data;
        }
        
//        // If primary data is invalid, try IV recovery first (common cause of decryption failures)
//        if (data != null && !data.isEmpty()) {
//            log.info("Primary data exists but validation failed for {}, attempting IV recovery", keyAlias);
//            if (tryIvRecovery()) {
//                // Re-validate after IV recovery
//                if (isValidEncryptedData(keyAlias, data)) {
//                    log.info("Successfully recovered {} after IV recovery", keyAlias);
//                    return data;
//                }
//            }
//        }
        
        // Try DataStore backup
        if (backupConfig != null) {
            try {
                String backupData = null;
                if (WALLET_PASSWORD_KEY_ALIAS.equals(keyAlias)) {
                    backupData = BuildersKt.runBlocking(
                        Dispatchers.getIO(),
                        (scope, continuation) -> backupConfig.getWalletPassword(continuation)
                    );
                } else if (UI_PIN_KEY_ALIAS.equals(keyAlias)) {
                    backupData = BuildersKt.runBlocking(
                        Dispatchers.getIO(),
                        (scope, continuation) -> backupConfig.getUiPin(continuation)
                    );
                }
                
                if (isValidEncryptedData(keyAlias, backupData)) {
                    log.info("Recovered {} from DataStore backup", keyAlias);
                    // Restore to primary SharedPreferences
                    securityPrefs.edit().putString(keyAlias, backupData).apply();
                    return backupData;
                }
            } catch (Exception e) {
                log.warn("Failed to recover {} from DataStore backup", keyAlias, e);
            }
        }
        
        // Try file backups
        File filesDir = backupDir;
        File[] backupFiles = {
            SecurityFileUtils.INSTANCE.getBackupFile(filesDir, keyAlias, true),
            SecurityFileUtils.INSTANCE.getBackupFile(filesDir, keyAlias, false)
        };
        
        for (File backupFile : backupFiles) {
            try {
                if (backupFile.exists()) {
                    String fileData = SecurityFileUtils.INSTANCE.readFromFile(backupFile);
                    if (fileData != null && isValidEncryptedData(keyAlias, fileData)) {
                        log.info("Recovered {} from file backup: {}", keyAlias, backupFile.getName());
                        // Restore to primary SharedPreferences
                        securityPrefs.edit().putString(keyAlias, fileData).apply();
                        // Re-backup to all locations
                        backupEncryptedData(keyAlias, fileData);
                        return fileData;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to recover {} from file backup: {}", keyAlias, backupFile.getName(), e);
            }
        }
        
        log.error("Unable to recover {} from any backup source", keyAlias);
        return null;
    }

    /**
     * Validate encrypted data by attempting decryption
     */
    private boolean isValidEncryptedData(String keyAlias, String encryptedData) {
        if (encryptedData == null || encryptedData.isEmpty()) {
            return false;
        }
        
        try {
            byte[] encrypted = Base64.decode(encryptedData, Base64.NO_WRAP);
            encryptionProvider.decrypt(keyAlias, encrypted);
            return true;
        } catch (Exception e) {
            log.debug("Invalid encrypted data for {}: {}", keyAlias, e.getMessage());
            return false;
        }
    }

    /**
     * Validate encrypted data by attempting decryption
     */
    @VisibleForTesting
    boolean isValidEncryptedDataThrows(String keyAlias, String encryptedData) throws GeneralSecurityException, IOException {
        if (encryptedData == null || encryptedData.isEmpty()) {
            return false;
        }

        byte[] encrypted = Base64.decode(encryptedData, Base64.NO_WRAP);
        encryptionProvider.decrypt(keyAlias, encrypted);
        return true;

    }

    /**
     * Attempt to recover IV from backups when decryption failures occur
     * This handles the case where IV corruption causes AEADBadTagException
     */
//    private boolean tryIvRecovery() {
//        try {
//            if (encryptionProvider instanceof ModernEncryptionProvider) {
//                ModernEncryptionProvider modernProvider = (ModernEncryptionProvider) encryptionProvider;
//                boolean recovered = modernProvider.recoverIvFromBackups();
//                if (recovered) {
//                    log.info("Successfully recovered IV from backups");
//                    return true;
//                } else {
//                    log.warn("IV recovery failed - no valid IV found in backups");
//                    return false;
//                }
//            } else {
//                log.debug("EncryptionProvider is not ModernEncryptionProvider, skipping IV recovery");
//                return false;
//            }
//        } catch (Exception e) {
//            log.error("IV recovery attempt failed", e);
//            return false;
//        }
//    }

    /**
     * Clear all backup data
     */
    @VisibleForTesting
    void clearBackups() {
        // Clear DataStore backup
        if (backupConfig != null) {
            try {
                BuildersKt.runBlocking(
                    Dispatchers.getIO(),
                    (scope, continuation) -> backupConfig.clearAll(continuation)
                );
                log.info("Cleared DataStore backups");
            } catch (Exception e) {
                log.error("Failed to clear DataStore backups", e);
            }
        }
        
        // Clear file backups
        SecurityFileUtils.INSTANCE.clearBackupDirectory(backupDir);
    }
    
    /**
     * Migrate existing data from previous versions that didn't have backup system
     */
    private void migrateExistingDataToBackups() {
        try {
            // Check if migration has already been done using file flag
            if (SecurityFileUtils.INSTANCE.isMigrationCompleted(backupDir, MIGRATION_COMPLETED_FILE)) {
                log.info("Backup migration already completed");
                return;
            }
            log.info("check that the password and pin can be decrypted");
            retrievePassword();
            retrievePin();

            log.info("Starting backup migration for existing data");

            // Backup existing wallet password if it exists
            String existingPassword = securityPrefs.getString(WALLET_PASSWORD_KEY_ALIAS, null);
            if (existingPassword != null && !existingPassword.isEmpty()) {
                log.info("Migrating existing wallet password to backup system");
                backupEncryptedData(WALLET_PASSWORD_KEY_ALIAS, existingPassword);
            }

            // Backup existing PIN if it exists
            String existingPin = securityPrefs.getString(UI_PIN_KEY_ALIAS, null);
            if (existingPin != null && !existingPin.isEmpty()) {
                log.info("Migrating existing PIN to backup system");
                backupEncryptedData(UI_PIN_KEY_ALIAS, existingPin);
            }

            // Mark migration as completed using file flag
            SecurityFileUtils.INSTANCE.setMigrationCompleted(backupDir, MIGRATION_COMPLETED_FILE);
            log.info("Backup migration completed successfully");

        } catch (Exception e) {
            log.error("Failed to migrate existing data to backup system", e);
        }
    }

    // ===== Dual-Fallback Recovery Methods =====

    /**
     * Recover wallet password using PIN-based fallback
     * Use this when KeyStore fails but user remembers their PIN
     *
     * @param pin User's PIN
     * @return Recovered wallet password
     * @throws SecurityGuardException if PIN-fallback fails or is not available
     */
    public synchronized String recoverPasswordWithPin(String pin) throws SecurityGuardException {
        try {
            if (encryptionProvider instanceof DualFallbackEncryptionProvider) {
                String password = ((DualFallbackEncryptionProvider) encryptionProvider)
                        .decryptWithPin(WALLET_PASSWORD_KEY_ALIAS, pin);
                log.info("Successfully recovered wallet password with PIN-based fallback");
                return password;
            } else {
                throw new SecurityGuardException("PIN-based fallback not available");
            }
        } catch (GeneralSecurityException e) {
            log.error("PIN-based recovery failed", e);
            analyticsService.logError(e, "PIN-based recovery failed");
            throw new SecurityGuardException("PIN-based recovery failed", e);
        }
    }

    /**
     * Recover PIN using mnemonic-based fallback
     * Use this when KeyStore fails and user needs to recover their PIN
     *
     * @param mnemonicWords User's wallet recovery phrase
     * @return Recovered PIN
     * @throws SecurityGuardException if mnemonic-fallback fails or is not available
     */
    public synchronized String recoverPinWithMnemonic(List<String> mnemonicWords) throws SecurityGuardException {
        try {
            if (encryptionProvider instanceof DualFallbackEncryptionProvider) {
                String pin = ((DualFallbackEncryptionProvider) encryptionProvider)
                        .decryptWithMnemonic(UI_PIN_KEY_ALIAS, mnemonicWords);
                log.info("Successfully recovered PIN with mnemonic-based fallback");
                return pin;
            } else {
                throw new SecurityGuardException("Mnemonic-based fallback not available");
            }
        } catch (GeneralSecurityException e) {
            log.error("Mnemonic-based PIN recovery failed", e);
            analyticsService.logError(e, "Mnemonic-based PIN recovery failed");
            throw new SecurityGuardException("Mnemonic-based PIN recovery failed", e);
        }
    }

    /**
     * Recover wallet password using mnemonic-based fallback
     * Use this when KeyStore fails and user forgot their PIN (nuclear option)
     *
     * @param mnemonicWords User's wallet recovery phrase
     * @return Recovered wallet password
     * @throws SecurityGuardException if mnemonic-fallback fails or is not available
     */
    public synchronized String recoverPasswordWithMnemonic(List<String> mnemonicWords) throws SecurityGuardException {
        try {
            if (encryptionProvider instanceof DualFallbackEncryptionProvider) {
                String password = ((DualFallbackEncryptionProvider) encryptionProvider)
                        .decryptWithMnemonic(WALLET_PASSWORD_KEY_ALIAS, mnemonicWords);
                log.info("Successfully recovered wallet password with mnemonic-based fallback");
                return password;
            } else {
                throw new SecurityGuardException("Mnemonic-based fallback not available");
            }
        } catch (GeneralSecurityException e) {
            log.error("Mnemonic-based password recovery failed", e);
            analyticsService.logError(e, "Mnemonic-based password recovery failed");
            throw new SecurityGuardException("Mnemonic-based password recovery failed", e);
        }
    }

    /**
     * Ensure PIN-based fallback encryption for wallet password
     * Call this after user sets or enters their PIN
     *
     * @param pin User's PIN
     * @return true if PIN-fallback was added or already exists, false on failure
     */
    public synchronized boolean ensurePinFallback(String pin) {
        try {
            if (encryptionProvider instanceof DualFallbackEncryptionProvider) {
                boolean success = ((DualFallbackEncryptionProvider) encryptionProvider)
                        .ensurePinFallback(pin);
                if (success) {
                    log.info("PIN-based fallback ensured for wallet password");
                }
                return success;
            } else {
                log.warn("DualFallbackEncryptionProvider not available, skipping PIN-fallback");
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to ensure PIN-based fallback", e);
            return false;
        }
    }

    /**
     * Ensure mnemonic-based fallback encryption for both PIN and wallet password
     * Call this after wallet initialization when mnemonic is available
     *
     * @param mnemonicWords User's wallet recovery phrase
     * @return true if all mnemonic-fallbacks were added or already exist, false on failure
     */
    public synchronized boolean ensureMnemonicFallbacks(List<String> mnemonicWords) {
        try {
            if (encryptionProvider instanceof DualFallbackEncryptionProvider) {
                boolean success = ((DualFallbackEncryptionProvider) encryptionProvider)
                        .ensureMnemonicFallbacks(mnemonicWords);
                if (success) {
                    log.info("Mnemonic-based fallbacks ensured for PIN and wallet password");
                }
                return success;
            } else {
                log.warn("DualFallbackEncryptionProvider not available, skipping mnemonic-fallbacks");
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to ensure mnemonic-based fallbacks", e);
            return false;
        }
    }

    public boolean isHealthy() {
        if (encryptionProvider instanceof DualFallbackEncryptionProvider) {
            return ((DualFallbackEncryptionProvider) encryptionProvider).isKeyStoreHealthy();
        }
        return false;
    }

    public boolean isHealthyWithFallbacks() {
        if (encryptionProvider instanceof DualFallbackEncryptionProvider provider) {
            return provider.isKeyStoreHealthy() && hasFallbacks();
        }
        return false;
    }

    public boolean hasFallbacks() {
        if (encryptionProvider instanceof DualFallbackEncryptionProvider provider) {
            return provider.hasMnemonicFallback(WALLET_PASSWORD_KEY_ALIAS) &&
                    provider.hasMnemonicFallback(UI_PIN_KEY_ALIAS) &&
                    provider.hasPinFallback(WALLET_PASSWORD_KEY_ALIAS);
        }
        return false;
    }

    // ===== Health Listener System =====

    /**
     * Add a health listener to be notified of security system status changes
     * @param listener The listener to add
     */
    public void addHealthListener(HealthListener listener) {
        if (!healthListeners.contains(listener)) {
            healthListeners.add(listener);
            log.debug("Added health listener: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * Remove a health listener
     * @param listener The listener to remove
     */
    public void removeHealthListener(HealthListener listener) {
        healthListeners.remove(listener);
        log.debug("Removed health listener: {}", listener.getClass().getSimpleName());
    }

    /**
     * Setup health monitoring system
     * Registers a SharedPreferences listener to monitor security-related changes
     */
    private void setupHealthMonitoring() {
        securityPrefsListener = (prefs, key) -> {
            // Check if key is security-related
            if (key != null && isSecurityRelatedKey(key)) {
                log.debug("Security preference changed: {}", key);

                // Calculate current health status
                SecuritySystemStatus status = calculateHealthStatus();

                // Notify all listeners
                notifyHealthListeners(status);
            }
        };

        securityPrefs.registerOnSharedPreferenceChangeListener(securityPrefsListener);
        log.info("Health monitoring system initialized");
    }

    /**
     * Check if a preference key is security-related
     */
    private boolean isSecurityRelatedKey(String key) {
        return key.startsWith("primary_") ||
               key.startsWith("fallback_pin_") ||
               key.startsWith("fallback_mnemonic_") ||
               key.equals(DualFallbackEncryptionProvider.KEYSTORE_HEALTHY_KEY) ||
               key.equals(UI_PIN_KEY_ALIAS) ||
               key.equals(WALLET_PASSWORD_KEY_ALIAS);
    }

    /**
     * Calculate current security system health status
     */
    private SecuritySystemStatus calculateHealthStatus() {
        if (isHealthyWithFallbacks()) {
            return SecuritySystemStatus.HEALTHY_WITH_FALLBACKS;
        } else if (isHealthy()) {
            return SecuritySystemStatus.HEALTHY;
        } else if (hasFallbacks()) {
            return SecuritySystemStatus.FALLBACKS;
        } else {
            return SecuritySystemStatus.DEAD;
        }
    }

    /**
     * Notify all registered health listeners of status change
     */
    private void notifyHealthListeners(SecuritySystemStatus status) {
        log.info("Notifying {} health listeners of status change: {}", healthListeners.size(), status);

        for (HealthListener listener : healthListeners) {
            try {
                listener.onHealthChanged(status);
            } catch (Exception e) {
                log.error("Health listener threw exception: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Get current security system health status
     * @return Current security system status
     */
    public SecuritySystemStatus getHealthStatus() {
        return calculateHealthStatus();
    }
}