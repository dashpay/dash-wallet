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

package de.schildbach.wallet.ui.dashpay.utils

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class DashPayConfig @Inject constructor(private val context: Context) {
    companion object {
        const val DISABLE_NOTIFICATIONS: Long = -1

        const val DASHPAY_PREFS_DIRECTORY = "dashpay"
        val LAST_SEEN_NOTIFICATION_TIME = longPreferencesKey("last_seen_notification_time")
        val LAST_METADATA_PUSH = longPreferencesKey("last_metadata_push")
        val HAS_DASH_PAY_INFO_SCREEN_BEEN_SHOWN = booleanPreferencesKey("has_dash_pay_info_screen_been_shown")
    }
    private val Context.dataStore by preferencesDataStore(DASHPAY_PREFS_DIRECTORY, produceMigrations = {
        listOf(
            // Migrating relevant keys from default prefs
            SharedPreferencesMigration(
                context = context,
                sharedPreferencesName = context.packageName + "_preferences",
                keysToMigrate = setOf(
                    LAST_SEEN_NOTIFICATION_TIME.name
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

    open fun <T> observe(key: Preferences.Key<T>): Flow<T?> {
        return dataStore.map { preferences -> preferences[key] }
    }

    open suspend fun <T> get(key: Preferences.Key<T>): T? {
        return dataStore.map { preferences -> preferences[key] }.first()
    }

    open suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    open suspend fun areNotificationsDisabled(): Boolean {
        return (get(LAST_SEEN_NOTIFICATION_TIME) ?: 0) == DISABLE_NOTIFICATIONS
    }

    open suspend fun disableNotifications() {
        set(LAST_SEEN_NOTIFICATION_TIME, DISABLE_NOTIFICATIONS)
    }

    open suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    fun clearDashPayConfig() =
        GlobalScope.launch { clearAll() }
}


