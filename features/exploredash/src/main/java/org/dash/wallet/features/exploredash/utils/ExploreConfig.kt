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

package org.dash.wallet.features.exploredash.utils

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import javax.inject.Inject
import javax.inject.Singleton

data class ExploreDatabasePrefs(
    val localDbTimestamp: Long = 0,
    val lastSyncTimestamp: Long = 0,
    val preloadedOnTimestamp: Long = 0,
    val failedSyncAttempts: Int = 0,
    val preloadedTestDb: Boolean = false
)

@Singleton
open class ExploreConfig @Inject constructor(
    private val context: Context,
    walletDataProvider: WalletDataProvider
) : BaseConfig(
    context,
    PREFERENCES_NAME,
    walletDataProvider,
    migrations = listOf(
        // Migrating all keys from explore.xml
        SharedPreferencesMigration(context, PREFERENCES_NAME),
        // Migrating explore keys from default prefs
        SharedPreferencesMigration(
            context,
            context.packageName + "_preferences",
            keysToMigrate = setOf(
                HAS_INFO_SCREEN_BEEN_SHOWN.name,
                HAS_LOCATION_DIALOG_BEEN_SHOWN.name,
                EXPLORE_DATABASE_NAME.name
            )
        )
    )
) {
    companion object {
        const val PREFERENCES_NAME = "explore"

        val LOCAL_DB_TIMESTAMP = longPreferencesKey("local_db_timestamp")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val PRELOADED_ON_TIMESTAMP = longPreferencesKey("preloaded_on")
        val FAILED_SYNC_ATTEMPTS = intPreferencesKey("failed_sync_attempts")
        val PRELOADED_TEST_DB = booleanPreferencesKey("preloaded_test_database")

        val HAS_INFO_SCREEN_BEEN_SHOWN = booleanPreferencesKey("has_info_screen_been_shown")
        val HAS_LOCATION_DIALOG_BEEN_SHOWN = booleanPreferencesKey("has_location_dialog_been_shown")
        val EXPLORE_DATABASE_NAME = stringPreferencesKey("explore_database_name")
    }

    val exploreDatabasePrefs: Flow<ExploreDatabasePrefs> = data
        .map { prefs ->
            ExploreDatabasePrefs(
                localDbTimestamp = prefs[LOCAL_DB_TIMESTAMP] ?: 0,
                lastSyncTimestamp = prefs[LAST_SYNC_TIMESTAMP] ?: 0,
                preloadedOnTimestamp = prefs[PRELOADED_ON_TIMESTAMP] ?: 0,
                failedSyncAttempts = prefs[FAILED_SYNC_ATTEMPTS] ?: 0,
                preloadedTestDb = prefs[PRELOADED_TEST_DB] ?: false
            )
        }

    suspend fun saveExploreDatabasePrefs(databasePrefs: ExploreDatabasePrefs) {
        context.dataStore.edit { prefs ->
            prefs[LOCAL_DB_TIMESTAMP] = databasePrefs.localDbTimestamp
            prefs[LAST_SYNC_TIMESTAMP] = databasePrefs.lastSyncTimestamp
            prefs[PRELOADED_ON_TIMESTAMP] = databasePrefs.preloadedOnTimestamp
            prefs[FAILED_SYNC_ATTEMPTS] = databasePrefs.failedSyncAttempts
            prefs[PRELOADED_TEST_DB] = databasePrefs.preloadedTestDb
        }
    }
}
