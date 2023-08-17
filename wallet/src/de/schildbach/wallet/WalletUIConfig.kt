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

package de.schildbach.wallet

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// Intended for the UI settings which affect what the user sees on the screen.
// Should be used from views and viewModels only.
// For other settings, use Configuration or another datastore class.
open class WalletUIConfig @Inject constructor(private val context: Context) {
    companion object {
        val AUTO_HIDE_BALANCE = booleanPreferencesKey("hide_balance")
        val SHOW_TAP_TO_HIDE_HINT = booleanPreferencesKey("show_tap_to_hide_balance_hint")
        val VOTE_DASH_PAY_ENABLED = booleanPreferencesKey("VOTE_DASH_PAY_ENABLED")
    }

    private val Context.dataStore by preferencesDataStore("wallet_ui", produceMigrations = {
        listOf(
            // Migrating relevant keys from default prefs
            SharedPreferencesMigration(
                context = context,
                sharedPreferencesName = context.packageName + "_preferences",
                keysToMigrate = setOf(
                    AUTO_HIDE_BALANCE.name
                )
            )
        )
    })

    private val dataStore = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    open fun <T> observePreference(key: Preferences.Key<T>): Flow<T?> {
        return dataStore.map { preferences -> preferences[key] }
    }

    open suspend fun <T> getPreference(key: Preferences.Key<T>): T? {
        return dataStore.map { preferences -> preferences[key] }.first()
    }

    open suspend fun <T> setPreference(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    open suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
