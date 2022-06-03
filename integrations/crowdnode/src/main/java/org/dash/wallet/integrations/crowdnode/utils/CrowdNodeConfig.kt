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
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class CrowdNodeConfig @Inject constructor(private val context: Context) {
    companion object {
        val INFO_SHOWN = booleanPreferencesKey("info_shown")
        val ONLINE_INFO_SHOWN = booleanPreferencesKey("online_info_shown")
        val CONFIRMATION_DIALOG_SHOWN = booleanPreferencesKey("confirmation_dialog_shown")
        val BACKGROUND_ERROR = stringPreferencesKey("error")
        val ONLINE_ACCOUNT_STATUS = intPreferencesKey("online_account_status")
        val LAST_BALANCE = longPreferencesKey("last_balance")
        val SIGNED_EMAIL_MESSAGE_ID = intPreferencesKey("signed_email_message_id")
    }

    private val Context.dataStore by preferencesDataStore("crowdnode")
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