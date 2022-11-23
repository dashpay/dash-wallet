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
import android.preference.PreferenceManager
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.bitcoinj.core.Coin
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoinbaseConfig @Inject constructor(private val context: Context) {
    companion object {
        private const val PREFS_KEY_LAST_COINBASE_BALANCE = "last_coinbase_balance"
        val LAST_BALANCE = longPreferencesKey("last_balance")
        val UPDATE_BASE_IDS = booleanPreferencesKey("should_update_base_ids")
    }

    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val Context.dataStore by preferencesDataStore("coinbase")
    private val dataStore = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    fun <T> observePreference(key: Preferences.Key<T>): Flow<T?> {
        return dataStore.map { preferences -> preferences[key] }
    }

    suspend fun <T> getPreference(key: Preferences.Key<T>): T? {
        migrateLastBalance(key)
        return dataStore.map { preferences -> preferences[key] }.first()
    }

    suspend fun <T> setPreference(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun <T> migrateLastBalance(key: Preferences.Key<T>) {
        if (key == LAST_BALANCE && !sharedPrefs.getString(PREFS_KEY_LAST_COINBASE_BALANCE, null).isNullOrEmpty()) {
            // TODO: finish migration of this preference
            val balanceValue = Coin.parseCoin(sharedPrefs.getString(PREFS_KEY_LAST_COINBASE_BALANCE, "0.0")).value
            setPreference(LAST_BALANCE, balanceValue)
            sharedPrefs.edit().putString(PREFS_KEY_LAST_COINBASE_BALANCE, null).apply()
        }
    }
}