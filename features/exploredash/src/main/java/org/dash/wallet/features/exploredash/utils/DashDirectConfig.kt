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

package org.dash.wallet.features.exploredash.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dash.wallet.common.util.security.EncryptionProvider
import java.io.IOException
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashDirectConfig @Inject constructor(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private val securityKeyAlias = "dash_direct_data-store"
    private val bytesToStringSeparator = "|"
    companion object {
        val PREFS_KEY_LAST_DASH_DIRECT_ACCESS_TOKEN = stringPreferencesKey("last_dash_direct_access_token")
        val PREFS_KEY_DASH_DIRECT_EMAIL = stringPreferencesKey("dash_direct_email")
    }

    private val keyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
    }

    private val Context.dataStore by preferencesDataStore("dashdirect")

    private val encryptionProvider by lazy {
        EncryptionProvider(keyStore, prefs)
    }

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
        return dataStore.map { preferences -> preferences[key] }.first()
    }

    suspend fun <T> setPreference(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    suspend fun getSecuredData(key: Preferences.Key<String>) =
        dataStore.secureMap<String> { preferences ->
            preferences[key].orEmpty()
        }.first()

    suspend fun setSecuredData(key: Preferences.Key<String>, value: String) {
        context.dataStore.secureEdit(value) { preferences, encryptedValue ->
            preferences[key] = encryptedValue
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private inline fun <reified T> Flow<Preferences>.secureMap(crossinline fetchValue: (value: Preferences) -> String): Flow<T?> {
        return map {
            try {
                encryptionProvider.decrypt(
                    securityKeyAlias,
                    fetchValue(it).split(bytesToStringSeparator).map { result ->
                        result.toByte()
                    }.toByteArray()
                )?.let { data -> Json { encodeDefaults = true }.decodeFromString(data) }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private suspend inline fun <reified T> DataStore<Preferences>.secureEdit(
        value: T,
        crossinline editStore: (MutablePreferences, String) -> Unit
    ) {
        edit {
            encryptionProvider.encrypt(securityKeyAlias, Json.encodeToString(value))?.let { encryptedValue ->
                editStore.invoke(it, encryptedValue.joinToString(bytesToStringSeparator))
            }
        }
    }
}
