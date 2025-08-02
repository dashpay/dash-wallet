package de.schildbach.wallet.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.dash.wallet.common.util.security.EncryptionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import de.schildbach.wallet.WalletApplication;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.Dispatchers;

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
    private SecurityConfig backupConfig;

    private SecurityGuard() throws GeneralSecurityException, IOException {
        securityPrefs = WalletApplication.getInstance().getSharedPreferences(SECURITY_PREFS_NAME, Context.MODE_PRIVATE);
        // TODO: this is temporary to help determine why securityPrefs are empty in rare cases
        log.info("loading security guard with keys: {}", securityPrefs.getAll().keySet());
        encryptionProvider = EncryptionProviderFactory.create(securityPrefs);
        
        // Backup config will be injected separately to avoid circular dependency
        backupConfig = null;
    }

    private SecurityGuard(SharedPreferences securityPrefs) throws GeneralSecurityException, IOException {
        this.securityPrefs = securityPrefs;
        // TODO: this is temporary to help determine why securityPrefs are empty in rare cases
        log.info("loading security guard with keys: {}", securityPrefs.getAll().keySet());
        encryptionProvider = EncryptionProviderFactory.create(securityPrefs);

        // Backup config will be injected separately to avoid circular dependency
        backupConfig = null;
    }

    public static SecurityGuard getInstance() throws GeneralSecurityException, IOException {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new SecurityGuard();
                }
            }
        }
        log.info("loading security guard with keys: {}", instance.securityPrefs.getAll().keySet());
        return instance;
    }

    public static SecurityGuard getTestInstance(SharedPreferences securityPrefs) throws GeneralSecurityException, IOException {
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
        log.info("password: saved: {}", password);
        validateKeyIntegrity(WALLET_PASSWORD_KEY_ALIAS);
        String encryptedPin = encrypt(WALLET_PASSWORD_KEY_ALIAS, password);
        securityPrefs.edit().putString(WALLET_PASSWORD_KEY_ALIAS, encryptedPin).apply();
        
        // Backup to multiple locations
        backupEncryptedData(WALLET_PASSWORD_KEY_ALIAS, encryptedPin);
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
            String encryptedPasswordStr = recoverEncryptedData(WALLET_PASSWORD_KEY_ALIAS);
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
            throw new SecurityGuardException("Failed to retrieve password", e);
        }
    }

    public synchronized String retrievePin() throws SecurityGuardException {
        try {
            String savedPinStr = recoverEncryptedData(UI_PIN_KEY_ALIAS);
            if (savedPinStr == null || savedPinStr.isEmpty()) {
                throw new SecurityGuardException("No encrypted PIN found");
            }
            
            byte[] savedPin = Base64.decode(savedPinStr, Base64.NO_WRAP);
            return encryptionProvider.decrypt(UI_PIN_KEY_ALIAS, savedPin);
        } catch (GeneralSecurityException | IOException e) {
            log.error("Failed to retrieve PIN", e);
            throw new SecurityGuardException("Failed to retrieve PIN", e);
        }
    }

    public synchronized void savePin(String pin) throws GeneralSecurityException, IOException {
        validateKeyIntegrity(UI_PIN_KEY_ALIAS);
        String encryptedPin = encrypt(UI_PIN_KEY_ALIAS, pin);
        securityPrefs.edit().putString(UI_PIN_KEY_ALIAS, encryptedPin).apply();
        
        // Backup to multiple locations
        backupEncryptedData(UI_PIN_KEY_ALIAS, encryptedPin);
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

    /**
     * Set the backup config (called by dependency injection)
     */
    public void setBackupConfig(SecurityConfig backupConfig) {
        this.backupConfig = backupConfig;
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
        securityPrefs.edit()
                .remove(UI_PIN_KEY_ALIAS)
                .remove(WALLET_PASSWORD_KEY_ALIAS)
                .apply();
        
        // Clear backups
        clearBackups();
    }

    /**
     * Backup encrypted data to multiple locations: DataStore and file system
     */
    private void backupEncryptedData(String keyAlias, String encryptedData) {
        // DataStore backup
        if (backupConfig != null) {
            try {
                CompletableFuture.runAsync(() -> {
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
                });
            } catch (Exception e) {
                log.error("Failed to initiate DataStore backup for {}", keyAlias, e);
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
            File backupDir = new File(WalletApplication.getInstance().getFilesDir(), "security_backup");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }
            
            // Primary backup
            File backupFile = new File(backupDir, keyAlias + "_backup.dat");
            writeToFile(backupFile, data);
            
            // Secondary backup
            File backup2File = new File(backupDir, keyAlias + "_backup2.dat");
            writeToFile(backup2File, data);
            
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
        List<String> fileNames = Arrays.asList(
            keyAlias + "_backup.dat",
            keyAlias + "_backup2.dat"
        );
        
        for (String fileName : fileNames) {
            try {
                File backupFile = new File(new File(WalletApplication.getInstance().getFilesDir(), "security_backup"), fileName);
                if (backupFile.exists()) {
                    String fileData = readFromFile(backupFile);
                    if (fileData != null && isValidEncryptedData(keyAlias, fileData)) {
                        log.info("Recovered {} from file backup: {}", keyAlias, fileName);
                        // Restore to primary SharedPreferences
                        securityPrefs.edit().putString(keyAlias, fileData).apply();
                        // Re-backup to all locations
                        backupEncryptedData(keyAlias, fileData);
                        return fileData;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to recover {} from file backup: {}", keyAlias, fileName, e);
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
     * Clear all backup data
     */
    private void clearBackups() {
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
        try {
            File backupDir = new File(WalletApplication.getInstance().getFilesDir(), "security_backup");
            if (backupDir.exists()) {
                File[] backupFiles = backupDir.listFiles();
                if (backupFiles != null) {
                    for (File file : backupFiles) {
                        file.delete();
                    }
                }
                backupDir.delete();
                log.info("Cleared file backups");
            }
        } catch (Exception e) {
            log.error("Failed to clear file backups", e);
        }
    }
    
    /**
     * Migrate existing data from previous versions that didn't have backup system
     */
    private void migrateExistingDataToBackups() {
        try {
            // Check if migration has already been done using file flag
            if (isMigrationCompleted("backup_migration_completed")) {
                log.info("Backup migration already completed");
                return;
            }
            
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
            setMigrationCompleted("backup_migration_completed");
            log.info("Backup migration completed successfully");
            
        } catch (Exception e) {
            log.error("Failed to migrate existing data to backup system", e);
        }
    }
    
    /**
     * Write string to file (API 24 compatible)
     */
    private void writeToFile(File file, String data) throws IOException {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(data.getBytes("UTF-8"));
            fos.flush();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    log.warn("Failed to close file output stream", e);
                }
            }
        }
    }
    
    /**
     * Read string from file (API 24 compatible)
     */
    private String readFromFile(File file) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);
            return new String(buffer, "UTF-8");
        } catch (IOException e) {
            log.warn("Failed to read from file: {}", file.getName(), e);
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    log.warn("Failed to close file input stream", e);
                }
            }
        }
    }
    
    /**
     * Check if migration is completed using file-based flag (more reliable than SharedPreferences)
     */
    private boolean isMigrationCompleted(String migrationName) {
        try {
            File migrationDir = new File(WalletApplication.getInstance().getFilesDir(), "migration_flags");
            File migrationFile = new File(migrationDir, migrationName + ".flag");
            return migrationFile.exists();
        } catch (Exception e) {
            log.warn("Failed to check migration flag: {}", migrationName, e);
            return false;
        }
    }
    
    /**
     * Mark migration as completed using file-based flag
     */
    private void setMigrationCompleted(String migrationName) {
        try {
            File migrationDir = new File(WalletApplication.getInstance().getFilesDir(), "migration_flags");
            if (!migrationDir.exists()) {
                migrationDir.mkdirs();
            }
            
            File migrationFile = new File(migrationDir, migrationName + ".flag");
            migrationFile.createNewFile();
            log.info("Migration flag set: {}", migrationName);
        } catch (Exception e) {
            log.error("Failed to set migration flag: {}", migrationName, e);
        }
    }
}