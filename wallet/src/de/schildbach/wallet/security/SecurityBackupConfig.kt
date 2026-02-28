/*
 * Copyright 2023 Dash Core Group.
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
import androidx.datastore.preferences.core.stringPreferencesKey
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import org.dash.wallet.common.util.security.EncryptionProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityBackupConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider,
    encryptionProvider: EncryptionProvider
) : SecurityConfig(
    context = context,
    dataStoreName = "security_backup",
    walletDataProvider = walletDataProvider,
    encryptionProvider = encryptionProvider
)

// open to be used in tests
open class SecurityConfig(
    context: Context,
    walletDataProvider: WalletDataProvider,
    encryptionProvider: EncryptionProvider,
    dataStoreName: String
) : BaseConfig(
    context = context,
    name = dataStoreName,
    walletDataProvider = walletDataProvider,
    encryptionProvider = encryptionProvider
) {
    companion object {
        val ENCRYPTION_IV_KEY = stringPreferencesKey("encryption_iv_backup")
        val WALLET_PASSWORD_KEY = stringPreferencesKey("wallet_password_backup")
        val UI_PIN_KEY = stringPreferencesKey("ui_pin_backup")
    }

    suspend fun backupEncryptionIv(iv: String) {
        set(ENCRYPTION_IV_KEY, iv)
    }

    suspend fun getEncryptionIv(): String? {
        return get(ENCRYPTION_IV_KEY)
    }

    suspend fun backupWalletPassword(encryptedPassword: String) {
        set(WALLET_PASSWORD_KEY, encryptedPassword)
    }

    suspend fun getWalletPassword(): String? {
        return get(WALLET_PASSWORD_KEY)
    }

    suspend fun backupUiPin(encryptedPin: String) {
        set(UI_PIN_KEY, encryptedPin)
    }

    suspend fun getUiPin(): String? {
        return get(UI_PIN_KEY)
    }
}