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
import android.util.Log
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModuleConfiguration @Inject constructor(private val context: Context) {
    companion object {
        private val ACCOUNT_ADDRESS_KEY = stringPreferencesKey("account_address")
        private val CROWDNODE_ERROR_KEY = stringPreferencesKey("error")
        private val LAST_BALANCE_KEY = longPreferencesKey("last_balance")
    }

    init {
        Log.i("CROWDNODE", "ModuleConfiguration init")
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

    val accountAddress: Flow<String> = dataStore
        .map { preferences ->
            preferences[ACCOUNT_ADDRESS_KEY] ?: ""
        }


    suspend fun setAccountAddress(address: String) {
        context.dataStore.edit { settings ->
            settings[ACCOUNT_ADDRESS_KEY] = address
        }
    }

    val crowdNodeError: Flow<String> = dataStore
        .map { preferences ->
            preferences[CROWDNODE_ERROR_KEY] ?: ""
        }


    suspend fun setCrowdNodeError(address: String) {
        context.dataStore.edit { settings ->
            settings[CROWDNODE_ERROR_KEY] = address
        }
    }

    val lastBalance: Flow<Long> = dataStore
        .map { preferences ->
            preferences[LAST_BALANCE_KEY] ?: 0L
        }


    suspend fun setLastBalance(balance: Long) {
        context.dataStore.edit { settings ->
            settings[LAST_BALANCE_KEY] = balance
        }
    }
}