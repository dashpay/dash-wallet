/*
 * Copyright 2025 Dash Core Group.
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

package de.schildbach.wallet.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import de.schildbach.wallet.WalletApplication
import org.slf4j.LoggerFactory
import java.security.KeyStore

/**
 * Testing utilities to simulate KeyStore failures and test fallback recovery
 *
 * ⚠️ WARNING: FOR TESTING/DEBUGGING ONLY! DO NOT USE IN PRODUCTION!
 */
object FallbackTestingUtils {
    private val log = LoggerFactory.getLogger(FallbackTestingUtils::class.java)

    private const val TEST_MODE_KEY = "fallback_testing_enabled"
    private const val FORCE_KEYSTORE_FAIL_KEY = "force_keystore_fail"

    /**
     * Get security preferences
     */
    private fun getSecurityPrefs(): SharedPreferences {
        return WalletApplication.getInstance()
            .getSharedPreferences(SecurityGuard.SECURITY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Enable test mode (allows forcing KeyStore failures)
     */
    fun enableTestMode() {
        getSecurityPrefs().edit {
            putBoolean(TEST_MODE_KEY, true)
        }
        log.warn("⚠️ FALLBACK TESTING MODE ENABLED - KeyStore can be forced to fail")
    }

    /**
     * Disable test mode
     */
    fun disableTestMode() {
        getSecurityPrefs().edit {
            remove(TEST_MODE_KEY)
            remove(FORCE_KEYSTORE_FAIL_KEY)
        }
        log.info("Fallback testing mode disabled")
    }

    /**
     * Check if test mode is enabled
     */
    fun isTestModeEnabled(): Boolean {
        return getSecurityPrefs().getBoolean(TEST_MODE_KEY, false)
    }

    /**
     * Force KeyStore decryption to fail (simulates KeyStore corruption)
     * This will make all primary decryption attempts fail, forcing fallback recovery
     *
     * ⚠️ Requires test mode to be enabled first!
     */
    fun forceKeystoreFailure() {
        if (!isTestModeEnabled()) {
            log.error("Cannot force KeyStore failure - test mode not enabled!")
            return
        }

        getSecurityPrefs().edit {
            putBoolean(FORCE_KEYSTORE_FAIL_KEY, true)
        }
        log.warn("⚠️ KEYSTORE FORCED TO FAIL - All primary encryption will fail!")
    }

    /**
     * Restore normal KeyStore operation
     */
    fun restoreKeystoreOperation() {
        getSecurityPrefs().edit {
            remove(FORCE_KEYSTORE_FAIL_KEY)
        }
        log.info("KeyStore operation restored to normal")
    }

    /**
     * Check if KeyStore is forced to fail
     */
    fun isKeystoreForcedToFail(): Boolean {
        return getSecurityPrefs().getBoolean(FORCE_KEYSTORE_FAIL_KEY, false)
    }

    /**
     * Method 1: Delete KeyStore keys but keep fallback data
     * This simulates KeyStore corruption while fallbacks are intact
     */
    fun simulateKeystoreCorruption_KeepFallbacks() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            // Delete keys from KeyStore
            try {
                keyStore.deleteEntry(SecurityGuard.UI_PIN_KEY_ALIAS)
                log.info("Deleted UI_PIN_KEY_ALIAS from KeyStore")
            } catch (e: Exception) {
                log.debug("Could not delete UI_PIN_KEY_ALIAS: ${e.message}")
            }

            try {
                keyStore.deleteEntry(SecurityGuard.WALLET_PASSWORD_KEY_ALIAS)
                log.info("Deleted WALLET_PASSWORD_KEY_ALIAS from KeyStore")
            } catch (e: Exception) {
                log.debug("Could not delete WALLET_PASSWORD_KEY_ALIAS: ${e.message}")
            }

            // Keep fallback data in SharedPreferences
            log.info("✓ KeyStore corrupted (keys deleted), fallback data preserved")

        } catch (e: Exception) {
            log.error("Failed to simulate KeyStore corruption: ${e.message}", e)
        }
    }

    /**
     * Replace KeyStore keys by deleting and recreating them
     * This simulates what happens when device lock screen settings change,
     * invalidating biometric/hardware-backed keys and making old encrypted data undecryptable
     */
    fun simulateKeystoreKeyReplacement() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val securityPrefs = getSecurityPrefs()

            // Delete existing keys
            try {
                keyStore.deleteEntry(SecurityGuard.UI_PIN_KEY_ALIAS)
                log.info("Deleted UI_PIN_KEY_ALIAS from KeyStore")
            } catch (e: Exception) {
                log.debug("Could not delete UI_PIN_KEY_ALIAS: ${e.message}")
            }

            try {
                keyStore.deleteEntry(SecurityGuard.WALLET_PASSWORD_KEY_ALIAS)
                log.info("Deleted WALLET_PASSWORD_KEY_ALIAS from KeyStore")
            } catch (e: Exception) {
                log.debug("Could not delete WALLET_PASSWORD_KEY_ALIAS: ${e.message}")
            }

            // Recreate keys (new keys will be different, making old encrypted data undecryptable)
            val modernProvider = ModernEncryptionProvider(keyStore, securityPrefs)

            // Generate new keys by attempting encryption (this will auto-create keys)
            try {
                modernProvider.encrypt(SecurityGuard.UI_PIN_KEY_ALIAS, "test")
                log.info("Recreated UI_PIN_KEY_ALIAS in KeyStore")
            } catch (e: Exception) {
                log.warn("Could not recreate UI_PIN_KEY_ALIAS: ${e.message}")
            }

            try {
                modernProvider.encrypt(SecurityGuard.WALLET_PASSWORD_KEY_ALIAS, "test")
                log.info("Recreated WALLET_PASSWORD_KEY_ALIAS in KeyStore")
            } catch (e: Exception) {
                log.warn("Could not recreate WALLET_PASSWORD_KEY_ALIAS: ${e.message}")
            }

            log.info("✓ KeyStore keys replaced (old encrypted data now undecryptable, fallback data preserved)")

        } catch (e: Exception) {
            log.error("Failed to replace KeyStore keys: ${e.message}", e)
        }
    }

    /**
     * Method 2: Corrupt primary encrypted data
     * This makes decryption fail with BadPaddingException
     */
    fun simulateKeystoreCorruption_CorruptData() {
        val prefs = getSecurityPrefs()

        // Corrupt primary encrypted data
        val primaryPinData = prefs.getString("primary_${SecurityGuard.UI_PIN_KEY_ALIAS}", null)
        val primaryPasswordData = prefs.getString("primary_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}", null)

        if (primaryPinData != null) {
            // Flip some bytes to corrupt the data
            val corrupted = corruptBase64String(primaryPinData)
            prefs.edit {
                putString("primary_${SecurityGuard.UI_PIN_KEY_ALIAS}", corrupted)
            }
            log.info("✓ Corrupted primary PIN data")
        }

        if (primaryPasswordData != null) {
            val corrupted = corruptBase64String(primaryPasswordData)
            prefs.edit {
                putString("primary_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}", corrupted)
            }
            log.info("✓ Corrupted primary password data")
        }

        log.info("✓ Primary encrypted data corrupted, fallback data preserved")
    }

    /**
     * Method 3: Delete everything except one fallback type
     * Tests specific fallback recovery paths
     */
    fun simulateKeystoreCorruption_OnlyPinFallback() {
        simulateKeystoreCorruption_KeepFallbacks()

        val prefs = getSecurityPrefs()

        // Remove mnemonic fallback data
        prefs.edit {
            remove("fallback_mnemonic_${SecurityGuard.UI_PIN_KEY_ALIAS}")
            remove("fallback_mnemonic_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}")
        }

        log.info("✓ Only PIN-based fallback available for testing")
    }

    /**
     * Method 4: Delete everything except mnemonic fallback
     */
    fun simulateKeystoreCorruption_OnlyMnemonicFallback() {
        simulateKeystoreCorruption_KeepFallbacks()

        val prefs = getSecurityPrefs()

        // Remove PIN fallback data
        prefs.edit {
            remove("fallback_pin_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}")
        }

        log.info("✓ Only mnemonic-based fallback available for testing")
    }

    /**
     * Method 5: Complete encryption failure (for cryptographic verification testing)
     */
    fun simulateCompleteEncryptionFailure() {
        simulateKeystoreCorruption_KeepFallbacks()

        val prefs = getSecurityPrefs()

        // Corrupt ALL fallback data
        val pinFallback = prefs.getString("fallback_pin_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}", null)
        val mnemonicPinFallback = prefs.getString("fallback_mnemonic_${SecurityGuard.UI_PIN_KEY_ALIAS}", null)
        val mnemonicPasswordFallback = prefs.getString("fallback_mnemonic_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}", null)

        if (pinFallback != null) {
            prefs.edit {
                putString("fallback_pin_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}", corruptBase64String(pinFallback))
            }
        }

        if (mnemonicPinFallback != null) {
            prefs.edit {
                putString("fallback_mnemonic_${SecurityGuard.UI_PIN_KEY_ALIAS}", corruptBase64String(mnemonicPinFallback))
            }
        }

        if (mnemonicPasswordFallback != null) {
            prefs.edit {
                putString("fallback_mnemonic_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}", corruptBase64String(mnemonicPasswordFallback))
            }
        }

        log.warn("⚠️ ALL ENCRYPTION CORRUPTED - Only cryptographic verification will work!")
    }

    /**
     * Method 5: Complete encryption failure (for cryptographic verification testing)
     */
    fun simulateCompleteEncryptionFailureFromPreviousInstall() {
        simulateKeystoreCorruption_KeepFallbacks()

        val prefs = getSecurityPrefs()

        // Corrupt ALL fallback data
        val pinFallback = prefs.getString("fallback_pin_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}", null)
        val mnemonicPinFallback = prefs.getString("fallback_mnemonic_${SecurityGuard.UI_PIN_KEY_ALIAS}", null)
        val mnemonicPasswordFallback = prefs.getString("fallback_mnemonic_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}", null)

        if (pinFallback != null) {
            prefs.edit {
                remove("fallback_pin_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}")
            }
        }

        if (mnemonicPinFallback != null) {
            prefs.edit {
                remove("fallback_mnemonic_${SecurityGuard.UI_PIN_KEY_ALIAS}")
            }
        }

        if (mnemonicPasswordFallback != null) {
            prefs.edit {
                remove("fallback_mnemonic_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}")
            }
        }

        log.warn("⚠️ ALL ENCRYPTION CORRUPTED/REMOVED - Only cryptographic verification will work!")
    }

    /**
     * Restore everything to normal
     */
    fun restoreAllEncryption() {
        disableTestMode()
        log.info("✓ Encryption restored - remove app data and re-setup to fully reset")
    }

    /**
     * Helper: Corrupt a Base64 string by flipping characters
     * Note: This always changes characters but is reversible if called twice
     */
    private fun corruptBase64String(base64: String): String {
        if (base64.length < 10) return base64

        val chars = base64.toCharArray()
        // Flip multiple characters to ensure corruption is detectable
        val positions = listOf(
            chars.size / 4,
            chars.size / 2,
            chars.size * 3 / 4
        )

        for (pos in positions) {
            if (pos < chars.size) {
                // XOR with a fixed offset to ensure we always get a different character
                chars[pos] = when (chars[pos]) {
                    'A' -> 'Z'
                    'Z' -> 'A'
                    '/' -> '+'
                    '+' -> '/'
                    '=' -> 'A'
                    else -> 'A'
                }
            }
        }

        return String(chars)
    }

    /**
     * Print current encryption status
     */
    fun printEncryptionStatus() {
        val prefs = getSecurityPrefs()

        log.info("=== ENCRYPTION STATUS ===")
        log.info("Test Mode: ${isTestModeEnabled()}")
        log.info("KeyStore Forced Fail: ${isKeystoreForcedToFail()}")
        log.info("")

        log.info("Primary PIN: ${if (prefs.contains("primary_${SecurityGuard.UI_PIN_KEY_ALIAS}")) "✓" else "✗"}")
        log.info("Primary Password: ${if (prefs.contains("primary_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}")) "✓" else "✗"}")
        log.info("")

        log.info("PIN Fallback (Password): ${if (prefs.contains("fallback_pin_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}")) "✓" else "✗"}")
        log.info("")

        log.info("Mnemonic Fallback (PIN): ${if (prefs.contains("fallback_mnemonic_${SecurityGuard.UI_PIN_KEY_ALIAS}")) "✓" else "✗"}")
        log.info("Mnemonic Fallback (Password): ${if (prefs.contains("fallback_mnemonic_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}")) "✓" else "✗"}")
        log.info("========================")
    }
}