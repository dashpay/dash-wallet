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

package org.dash.wallet.common.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.migrations.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.util.security.EncryptionProvider
import java.io.IOException

@OptIn(ExperimentalSerializationApi::class)
abstract class BaseConfig(
    private val context: Context,
    private val name: String,
    walletDataProvider: WalletDataProvider,
    private val encryptionProvider: EncryptionProvider? = null,
    migrations: List<SharedPreferencesMigration<Preferences>> = listOf()
) {
    private val securityKeyAlias = "${name}_security_key"
    private val json = Json { encodeDefaults = true }

    protected val Context.dataStore by preferencesDataStore(
        name = name,
        produceMigrations = { migrations }
    )

    protected val data = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    init {
        walletDataProvider.attachOnWalletWipedListener {
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch { clearAll() }
        }
    }

    fun <T> observe(key: Preferences.Key<T>): Flow<T?> {
        return data.map { preferences -> preferences[key] }
    }

    open suspend fun <T> get(key: Preferences.Key<T>): T? {
        return data.map { preferences -> preferences[key] }.first()
    }

    open suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    fun observeSecureData(key: Preferences.Key<String>): Flow<String?> {
        return data.secureMap { preferences -> preferences[key].orEmpty() }
    }

    suspend fun getSecuredData(key: Preferences.Key<String>) =
        data.secureMap<String> { preferences -> preferences[key].orEmpty() }.first()

    suspend fun setSecuredData(key: Preferences.Key<String>, value: String) {
        context.dataStore.secureEdit(value) { preferences, encryptedValue -> preferences[key] = encryptedValue }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private inline fun <reified T> Flow<Preferences>.secureMap(
        crossinline fetchValue: (value: Preferences) -> String
    ): Flow<T?> {
        requireNotNull(encryptionProvider) { "encryptionProvider not provided for $name config" }

        return map { prefs ->
            try {
                val encryptedData = Base64.decode(fetchValue(prefs), Base64.NO_WRAP)

                if (encryptedData.isNotEmpty()) {
                    val data = encryptionProvider.decrypt(
                        securityKeyAlias,
                        encryptedData
                    )
                    json.decodeFromString(data)
                } else {
                    null
                }
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
        requireNotNull(encryptionProvider) { "encryptionProvider not provided for $name config" }

        edit {
            encryptionProvider.encrypt(securityKeyAlias, Json.encodeToString(value))?.let { encryptedValue ->
                editStore.invoke(it, Base64.encodeToString(encryptedValue, Base64.NO_WRAP))
            }
        }
    }
}
