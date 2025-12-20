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

import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.util.security.MasterKeyProvider
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import javax.crypto.SecretKey

/**
 * Smart master key provider that uses different strategies based on the key type
 *
 * Strategy:
 * - WALLET_PASSWORD_KEY_ALIAS: Uses mnemonic-based derivation (works even when wallet encrypted)
 * - Other keys (PIN, etc.): Uses seed-based derivation (requires wallet decrypted)
 *
 * This allows:
 * 1. Normal unlock: PIN → KeyStore → wallet password → decrypt wallet
 * 2. Recovery unlock: Mnemonic phrase → derive key → decrypt wallet password → unlock wallet
 */
class SmartMasterKeyProvider(
    private val walletDataProvider: WalletDataProvider
) : MasterKeyProvider {

    companion object {
        private val log = LoggerFactory.getLogger(SmartMasterKeyProvider::class.java)
    }

    private val mnemonicProvider = MnemonicBasedKeyProvider()
    private val seedProvider = SeedBasedMasterKeyProvider(walletDataProvider)

    @Throws(GeneralSecurityException::class)
    override fun getMasterKey(keyAlias: String): SecretKey {
        return when (keyAlias) {
            SecurityGuard.WALLET_PASSWORD_KEY_ALIAS -> {
                // Use mnemonic-based derivation for wallet password
                // This works even when wallet is encrypted!
                getMasterKeyFromMnemonic(keyAlias)
            }
            else -> {
                // Use seed-based derivation for other keys (PIN, etc.)
                // This requires wallet to be decrypted
                seedProvider.getMasterKey(keyAlias)
            }
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            val wallet = walletDataProvider.wallet
            wallet != null && wallet.keyChainSeed != null
        } catch (e: Exception) {
            log.warn("SmartMasterKeyProvider not available: {}", e.message)
            false
        }
    }

    /**
     * Derive key from mnemonic phrase for wallet password encryption
     */
    private fun getMasterKeyFromMnemonic(keyAlias: String): SecretKey {
        val wallet = walletDataProvider.wallet
            ?: throw GeneralSecurityException("Wallet not available")

        val seed = wallet.keyChainSeed
            ?: throw GeneralSecurityException("Wallet seed not available")

        // Get mnemonic words - these are available even when wallet is encrypted
        val mnemonicWords = seed.mnemonicCode
            ?: throw GeneralSecurityException("Mnemonic not available")

        // Derive key from mnemonic
        return mnemonicProvider.deriveKeyFromMnemonic(mnemonicWords, keyAlias)
    }
}