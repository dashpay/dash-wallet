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

package org.dash.wallet.common.util.security

import javax.crypto.SecretKey

/**
 * Interface for providing master encryption keys
 * Implementations can derive keys from wallet seed, recovery codes, or other sources
 */
interface MasterKeyProvider {
    /**
     * Get the master encryption key for a specific key alias
     * This key is used for encrypting/decrypting sensitive data
     *
     * @param keyAlias The alias identifying which key to retrieve
     * @return SecretKey for encryption/decryption
     * @throws GeneralSecurityException if key cannot be derived
     */
    fun getMasterKey(keyAlias: String): SecretKey

    /**
     * Check if the master key provider is available and ready to use
     * For seed-based providers, this checks if wallet is initialized
     */
    fun isAvailable(): Boolean
}