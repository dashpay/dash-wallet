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

package org.dash.wallet.integration.coinbase_integration.utils

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoinbaseConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider
): BaseConfig(
    context,
    PREFERENCES_NAME,
    walletDataProvider,
    migrations = listOf(
        SharedPreferencesMigration(
            context,
            context.packageName + "_preferences",
            keysToMigrate = setOf(
                LAST_ACCESS_TOKEN.name,
                LAST_REFRESH_TOKEN.name,
                USER_ACCOUNT_ID.name,
                AUTH_INFO_SHOWN.name,
                USER_WITHDRAWAL_LIMIT.name,
                SEND_LIMIT_CURRENCY.name
            )
        )
    )
) {
    companion object {
        const val PREFERENCES_NAME = "coinbase"
        val LAST_BALANCE = longPreferencesKey("last_balance")
        val UPDATE_BASE_IDS = booleanPreferencesKey("should_update_base_ids")
        val LOGOUT_COINBASE = booleanPreferencesKey("logout_coinbase")
        val LAST_ACCESS_TOKEN = stringPreferencesKey("last_coinbase_access_token")
        val LAST_REFRESH_TOKEN = stringPreferencesKey("last_coinbase_refresh_token")
        val USER_ACCOUNT_ID = stringPreferencesKey("coinbase_account_id")
        val AUTH_INFO_SHOWN = booleanPreferencesKey("coinbase_auth_info_shown")
        val USER_WITHDRAWAL_LIMIT = stringPreferencesKey("withdrawal_limit")
        val SEND_LIMIT_CURRENCY = stringPreferencesKey("send_limit_currency")
    }
}
