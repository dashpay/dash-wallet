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

import android.content.SharedPreferences
import androidx.core.content.edit
import org.dash.wallet.common.WalletDataProvider
import org.slf4j.LoggerFactory

/**
 * Migrates existing encrypted data to dual-fallback system
 *
 * This migration runs when:
 * 1. User upgrades to version with DualFallbackEncryptionProvider
 * 2. Primary encryption exists but fallback encryptions are missing
 * 3. User provides PIN (for PIN-based fallback)
 * 4. Wallet loads (for mnemonic-based fallback)
 *
 * Migration strategy:
 * - Phase 1: Add PIN-based fallback when user enters PIN
 * - Phase 2: Add mnemonic-based fallbacks when wallet loads
 */
class DualFallbackMigration(
    private val securityPrefs: SharedPreferences,
    private val dualFallbackProvider: DualFallbackEncryptionProvider,
    private val walletDataProvider: WalletDataProvider?
) {

    companion object {
        private val log = LoggerFactory.getLogger(DualFallbackMigration::class.java)

        private const val MIGRATION_PIN_FALLBACK_KEY = "dual_fallback_migration_pin_completed"
        private const val MIGRATION_MNEMONIC_FALLBACK_KEY = "dual_fallback_migration_mnemonic_completed"
    }

    /**
     * Attempt to add PIN-based fallback for wallet password
     * Call this when user enters their PIN (e.g., during login or PIN setup)
     *
     * @param pin User's PIN
     * @return true if migration completed or already done, false if skipped/failed
     */
    fun migrateToPinFallback(pin: String): Boolean {
        try {
            // Check if already migrated
            if (securityPrefs.getBoolean(MIGRATION_PIN_FALLBACK_KEY, false)) {
                log.debug("PIN-based fallback migration already completed")
                return true
            }

            // Check if primary wallet password exists
            val primaryPassword = securityPrefs.getString("primary_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}", null)
            if (primaryPassword == null) {
                log.debug("No primary wallet password found, skipping PIN-based fallback migration")
                return false
            }

            // Check if PIN-based fallback already exists
            val existingPinFallback = securityPrefs.getString("fallback_pin_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}", null)
            if (existingPinFallback != null) {
                log.debug("PIN-based fallback already exists, marking migration as complete")
                securityPrefs.edit { putBoolean(MIGRATION_PIN_FALLBACK_KEY, true) }
                return true
            }

            // Add PIN-based fallback encryption
            log.info("Starting PIN-based fallback migration for wallet password")
            val success = dualFallbackProvider.ensurePinFallback(pin)

            if (success) {
                log.info("PIN-based fallback migration completed successfully")
                securityPrefs.edit { putBoolean(MIGRATION_PIN_FALLBACK_KEY, true) }
                return true
            } else {
                log.warn("PIN-based fallback migration failed")
                return false
            }

        } catch (e: Exception) {
            log.error("PIN-based fallback migration error: ${e.message}", e)
            return false
        }
    }

    /**
     * Attempt to add mnemonic-based fallbacks for both PIN and wallet password
     * Call this after wallet loads and mnemonic is available
     *
     * @return true if migration completed or already done, false if skipped/failed
     */
    fun migrateToMnemonicFallbacks(): Boolean {
        try {
            // Check if already migrated
            if (securityPrefs.getBoolean(MIGRATION_MNEMONIC_FALLBACK_KEY, false)) {
                log.debug("Mnemonic-based fallback migration already completed")
                return true
            }

            // Check if wallet is available
            val wallet = walletDataProvider?.wallet
            if (wallet == null) {
                log.debug("Wallet not available, skipping mnemonic-based fallback migration")
                return false
            }

            // Get mnemonic from wallet
            // need to decrypt if encrypted
            val seed = wallet.keyChainSeed
            if (seed == null) {
                log.debug("Wallet seed not available, skipping mnemonic-based fallback migration")
                return false
            }

            val mnemonicWords = seed.mnemonicCode
            if (mnemonicWords == null) {
                log.debug("Mnemonic not available, skipping mnemonic-based fallback migration")
                return false
            }

            // Check if primary data exists for keys we want to protect
            val hasPrimaryPin = securityPrefs.contains("primary_${SecurityGuard.UI_PIN_KEY_ALIAS}")
            val hasPrimaryPassword = securityPrefs.contains("primary_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}")

            if (!hasPrimaryPin && !hasPrimaryPassword) {
                log.debug("No primary encrypted data found, skipping mnemonic-based fallback migration")
                return false
            }

            // Check if mnemonic-based fallbacks already exist
            val hasAllFallbacks =
                securityPrefs.contains("fallback_mnemonic_${SecurityGuard.UI_PIN_KEY_ALIAS}") &&
                securityPrefs.contains("fallback_mnemonic_${SecurityGuard.WALLET_PASSWORD_KEY_ALIAS}")

            if (hasAllFallbacks) {
                log.debug("Mnemonic-based fallbacks already exist, marking migration as complete")
                securityPrefs.edit { putBoolean(MIGRATION_MNEMONIC_FALLBACK_KEY, true) }
                return true
            }

            // Add mnemonic-based fallback encryptions
            log.info("Starting mnemonic-based fallback migration for PIN and wallet password")
            val success = dualFallbackProvider.ensureMnemonicFallbacks(mnemonicWords)

            if (success) {
                log.info("Mnemonic-based fallback migration completed successfully")
                securityPrefs.edit { putBoolean(MIGRATION_MNEMONIC_FALLBACK_KEY, true) }
                return true
            } else {
                log.warn("Mnemonic-based fallback migration failed")
                return false
            }

        } catch (e: Exception) {
            log.error("Mnemonic-based fallback migration error: ${e.message}", e)
            return false
        }
    }

    /**
     * Check if PIN-based fallback migration is complete
     */
    fun isPinFallbackMigrationComplete(): Boolean {
        return securityPrefs.getBoolean(MIGRATION_PIN_FALLBACK_KEY, false)
    }

    /**
     * Check if mnemonic-based fallback migration is complete
     */
    fun isMnemonicFallbackMigrationComplete(): Boolean {
        return securityPrefs.getBoolean(MIGRATION_MNEMONIC_FALLBACK_KEY, false)
    }

    /**
     * Check if all migrations are complete
     */
    fun isAllMigrationsComplete(): Boolean {
        return isPinFallbackMigrationComplete() && isMnemonicFallbackMigrationComplete()
    }

    /**
     * Reset migration status (for testing or troubleshooting)
     */
    fun resetMigrationStatus() {
        securityPrefs.edit()
            .remove(MIGRATION_PIN_FALLBACK_KEY)
            .remove(MIGRATION_MNEMONIC_FALLBACK_KEY)
            .apply()
        log.info("Dual-fallback migration status reset")
    }

    fun migrateToFallbacks(securityGuard: SecurityGuard) {
        walletDataProvider?.let {
            it.wallet?.let { wallet ->
                //migrateToMnemonicFallbacks()
                //migrateToPinFallback()

                try {
                    val password = securityGuard.retrievePassword()
                    val encryptionKey = wallet.keyCrypter!!.deriveKey(password)
                    val walletSeed = wallet.keyChainSeed.decrypt(wallet.keyCrypter, "", encryptionKey)
                    val mnemonicWords: List<String>? = walletSeed.mnemonicCode
                    if (mnemonicWords != null) {
                        securityGuard.ensureMnemonicFallbacks(mnemonicWords)
                        log.info("Mnemonic-based fallbacks ensured")
                    }
                } catch (e: Exception) {
                    log.error("Failed to ensure mnemonic-based fallbacks", e)
                }
                try {
                    val success = securityGuard.ensurePinFallback(securityGuard.retrievePin())
                    if (success) {
                        log.info("PIN-based fallback added successfully")
                    }
                } catch (e: java.lang.Exception) {
                    log.error("Failed to ensure PIN fallbacks", e)
                    // Don't crash - app can continue with primary+PIN fallback only
                }
            }
        }
    }
}