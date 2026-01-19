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

import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Derives encryption keys from user's PIN
 *
 * Used for first-level fallback when KeyStore fails.
 * Allows recovery of wallet password using PIN.
 *
 * Security considerations:
 * - PINs are typically 4-6 digits (low entropy)
 * - Uses high iteration count to slow brute force
 * - Only used for wallet password encryption (not PIN itself - that would be circular)
 */
class PinBasedKeyProvider {

    companion object {
        private val log = LoggerFactory.getLogger(PinBasedKeyProvider::class.java)

        // High iteration count to compensate for low PIN entropy
        private const val PBKDF2_ITERATIONS = 100000
        private const val KEY_LENGTH = 256
    }

    /**
     * Derive encryption key from user's PIN
     *
     * @param pin User's PIN (typically 4-6 digits)
     * @param keyAlias Identifier for the key being derived (used as salt component)
     * @return SecretKey for encryption/decryption
     */
    @Throws(GeneralSecurityException::class)
    fun deriveKeyFromPin(pin: String, keyAlias: String): SecretKey {
        if (pin.isEmpty()) {
            throw GeneralSecurityException("PIN cannot be empty")
        }

        // Use key alias as part of salt (deterministic, different for each key)
        val salt = "dash_wallet_pin_$keyAlias".toByteArray(Charsets.UTF_8)

        // Derive key using PBKDF2 with high iteration count
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        val keyBytes = tmp.encoded

        log.debug("Derived key from PIN for alias: {}", keyAlias)

        return SecretKeySpec(keyBytes, "AES")
    }
}