/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.features.exploredash.utils

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import org.dash.wallet.common.util.security.EncryptionProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CTXSpendConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider,
    encryptionProvider: EncryptionProvider
) : BaseConfig(context, PREFERENCES_NAME, walletDataProvider, encryptionProvider) {
    companion object {
        const val PREFERENCES_NAME = "ctxspend"

        val PREFS_KEY_ACCESS_TOKEN = stringPreferencesKey("last_ctx_spend_access_token")
        val PREFS_KEY_REFRESH_TOKEN = stringPreferencesKey("last_ctx_spend_refresh_token")
        val PREFS_KEY_CTX_PAY_EMAIL = stringPreferencesKey("ctx_spend_email")
        val PREFS_DEVICE_UUID = stringPreferencesKey("device_uuid")
        val PREFS_LAST_PURCHASE_START = longPreferencesKey("last_purchase_start")
    }
}
