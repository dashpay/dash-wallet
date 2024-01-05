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

package org.dash.wallet.integrations.crowdnode.utils

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class CrowdNodeConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider
) : BaseConfig(context, PREFERENCES_NAME, walletDataProvider) {
    companion object {
        const val PREFERENCES_NAME = "crowdnode"

        val INFO_SHOWN = booleanPreferencesKey("info_shown")
        val ONLINE_INFO_SHOWN = booleanPreferencesKey("online_info_shown")
        val CONFIRMATION_DIALOG_SHOWN = booleanPreferencesKey("confirmation_dialog_shown")
        val BACKGROUND_ERROR = stringPreferencesKey("error")
        val ONLINE_ACCOUNT_STATUS = intPreferencesKey("online_account_status")
        val LAST_BALANCE = longPreferencesKey("last_balance")
        val SIGNED_EMAIL_MESSAGE_ID = intPreferencesKey("signed_email_message_id")
        val WITHDRAWAL_LIMITS_SHOWN = booleanPreferencesKey("withdrawal_limits_shown")
        val WITHDRAWAL_LIMIT_PER_TX = longPreferencesKey("withdrawal_limit_per_tx")
        val WITHDRAWAL_LIMIT_PER_HOUR = longPreferencesKey("withdrawal_limit_per_hour")
        val WITHDRAWAL_LIMIT_PER_DAY = longPreferencesKey("withdrawal_limit_per_day")
        val LAST_WITHDRAWAL_BLOCK = intPreferencesKey("last_withdrawal_block")
    }
}
