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

import org.dash.wallet.common.util.security.EncryptionProvider
import javax.inject.Inject
import javax.inject.Singleton

/** This class eliminates a circular dependency */
@Singleton
class SecurityInitializer @Inject constructor(
    private val encryptionProvider: EncryptionProvider,
    private val backupConfig: SecurityBackupConfig
) {
    
    init {
        // Wire up the backup configuration after all dependencies are created
        initializeSecurity()
    }
    
    private fun initializeSecurity() {
        try {
            // Set backup config on ModernEncryptionProvider
            if (encryptionProvider is ModernEncryptionProvider) {
                encryptionProvider.setBackupConfig(backupConfig)
            }
            
            // Set backup config on SecurityGuard
            val securityGuard = SecurityGuard.getInstance()
            securityGuard.setBackupConfig(backupConfig)
            
        } catch (e: Exception) {
            // Log error but don't crash the app
            org.slf4j.LoggerFactory.getLogger(SecurityInitializer::class.java)
                .error("Failed to initialize security backup configuration", e)
        }
    }
}