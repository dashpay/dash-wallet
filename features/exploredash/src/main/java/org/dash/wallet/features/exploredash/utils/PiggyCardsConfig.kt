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

package org.dash.wallet.features.exploredash.utils

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import org.dash.wallet.common.util.security.EncryptionProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PiggyCardsConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider,
    encryptionProvider: EncryptionProvider
) : BaseConfig(context, PREFERENCES_NAME, walletDataProvider, encryptionProvider) {
    companion object {
        const val PREFERENCES_NAME = "piggycards"

        val PREFS_KEY_ACCESS_TOKEN = stringPreferencesKey("piggy_cards_access_token")
        val PREFS_KEY_USER_ID = stringPreferencesKey("piggy_cards_user_id")
        val PREFS_KEY_PASSWORD = stringPreferencesKey("piggy_cards_password")
        val PREFS_KEY_EMAIL = stringPreferencesKey("piggy_cards_email")
        val PREFS_KEY_TOKEN_EXPIRES_AT = stringPreferencesKey("piggy_cards_token_expires_at")
    }
}
