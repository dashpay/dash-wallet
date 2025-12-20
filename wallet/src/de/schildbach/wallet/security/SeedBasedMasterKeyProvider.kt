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

import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.wallet.DeterministicSeed
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.util.security.MasterKeyProvider
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Derives encryption keys from the wallet's BIP39 seed phrase
 * This allows recovery of encrypted data using only the wallet seed phrase
 *
 * Key derivation uses custom BIP32 paths:
 * - m/9999'/0'/1' for UI PIN encryption
 * - m/9999'/1'/[hash]' for other key aliases
 *
 * IMPORTANT: This provider CANNOT be used for wallet password encryption due to circular dependency:
 * - Wallet seed is encrypted with wallet password
 * - Can't use seed to encrypt the password that encrypts the seed!
 *
 * The high path number (9999) ensures no collision with standard wallet keys
 */
class SeedBasedMasterKeyProvider(
    private val walletDataProvider: WalletDataProvider
) : MasterKeyProvider {

    companion object {
        private val log = LoggerFactory.getLogger(SeedBasedMasterKeyProvider::class.java)

        // Custom derivation paths for encryption keys (using purpose 9999 to avoid conflicts)
        private const val ENCRYPTION_PURPOSE = 9999

        private const val UI_PIN_ACCOUNT = 0
        private const val UI_PIN_INDEX = 1

        private const val OTHER_KEYS_ACCOUNT = 1
    }

    @Throws(GeneralSecurityException::class)
    override fun getMasterKey(keyAlias: String): SecretKey {
        // CRITICAL: Cannot use seed for wallet password (circular dependency)
        if (SecurityGuard.WALLET_PASSWORD_KEY_ALIAS == keyAlias) {
            throw GeneralSecurityException(
                "Cannot use seed-based encryption for wallet password (circular dependency). " +
                "Wallet password must use KeyStore-only encryption."
            )
        }

        val wallet = walletDataProvider.wallet
            ?: throw GeneralSecurityException("Wallet not available")

        // Get the decrypted seed
        val seed = getDecryptedSeed(wallet)
            ?: throw GeneralSecurityException("Wallet seed not available or wallet is locked")

        // Derive master key from seed
        val masterKey = HDKeyDerivation.createMasterPrivateKey(seed.seedBytes)

        // Derive specific key based on alias
        val path = getDerivationPath(keyAlias)
        val derivedKey = deriveKey(masterKey, path)

        // Use first 32 bytes of derived private key as AES-256 key
        val keyBytes = derivedKey.privKeyBytes.copyOf(32)

        log.info("Derived master key for alias: {} using path: {}", keyAlias, path)
        return SecretKeySpec(keyBytes, "AES")
    }

    override fun isAvailable(): Boolean {
        return try {
            val wallet = walletDataProvider.wallet
            wallet != null && wallet.keyChainSeed != null
        } catch (e: Exception) {
            log.warn("MasterKeyProvider not available: {}", e.message)
            false
        }
    }

    /**
     * Get the decrypted seed from the wallet
     * If wallet is encrypted, this will fail with an exception
     */
    private fun getDecryptedSeed(wallet: org.bitcoinj.wallet.Wallet): DeterministicSeed? {
        val seed = wallet.keyChainSeed ?: return null

        // Check if seed bytes are accessible
        if (seed.seedBytes == null) {
            throw GeneralSecurityException(
                "Wallet is encrypted - cannot access seed. " +
                "Seed-based encryption can only be used when wallet is decrypted."
            )
        }

        return seed
    }

    private fun getDerivationPath(keyAlias: String): List<ChildNumber> {
        return when (keyAlias) {
            SecurityGuard.UI_PIN_KEY_ALIAS -> {
                // m/9999'/0'/1' for UI PIN
                listOf(
                    ChildNumber(ENCRYPTION_PURPOSE, true),
                    ChildNumber(UI_PIN_ACCOUNT, true),
                    ChildNumber(UI_PIN_INDEX, true)
                )
            }
            else -> {
                // m/9999'/1'/[hashcode]' for other aliases
                val index = kotlin.math.abs(keyAlias.hashCode() % 1000000)
                listOf(
                    ChildNumber(ENCRYPTION_PURPOSE, true),
                    ChildNumber(OTHER_KEYS_ACCOUNT, true),
                    ChildNumber(index, true)
                )
            }
        }
    }

    private fun deriveKey(masterKey: DeterministicKey, path: List<ChildNumber>): DeterministicKey {
        var key = masterKey
        for (childNumber in path) {
            key = HDKeyDerivation.deriveChildKey(key, childNumber)
        }
        return key
    }
}