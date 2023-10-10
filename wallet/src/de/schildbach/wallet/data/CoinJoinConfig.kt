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

package de.schildbach.wallet.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// Intended for the CoinJoinService configuration only.
open class CoinJoinConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider
) : BaseConfig(context, PREFERENCES_NAME, walletDataProvider) {
    companion object {
        const val PREFERENCES_NAME = "coinjoin"
        val COINJOIN_ROUNDS = intPreferencesKey("coinjoin_rounds")
        val COINJOIN_SESSIONS = intPreferencesKey("coinjoin_sessions")
        val COINJOIN_MULTISESSION = booleanPreferencesKey("coinjoin_multisession")
        val COINJOIN_AMOUNT = longPreferencesKey("coinjoin_amount")
        val FIRST_TIME_INFO_SHOWN = booleanPreferencesKey("first_time_info_shown")
    }
}
