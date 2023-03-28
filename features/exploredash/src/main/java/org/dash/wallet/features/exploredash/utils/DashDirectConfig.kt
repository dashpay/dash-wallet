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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dash.wallet.common.data.BaseConfig
import org.dash.wallet.common.util.security.EncryptionProvider
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalSerializationApi::class)
@Singleton
class DashDirectConfig @Inject constructor(
    private val context: Context,
    private val encryptionProvider: EncryptionProvider
): BaseConfig(context, PREFERENCES_NAME) {
    companion object {
        const val PREFERENCES_NAME = "dashdirect"
        private const val securityKeyAlias = "dash_direct_data-store"
        private const val bytesToStringSeparator = "|"

        val PREFS_KEY_ACCESS_TOKEN = stringPreferencesKey("last_dash_direct_access_token")
        val PREFS_KEY_DASH_DIRECT_EMAIL = stringPreferencesKey("dash_direct_email")
        val PREFS_DEVICE_UUID = stringPreferencesKey("device_uuid")
    }

    private val json = Json { encodeDefaults = true }

    suspend fun getSecuredData(key: Preferences.Key<String>) =
        data.secureMap<String> { preferences -> preferences[key].orEmpty() }.first()

    suspend fun setSecuredData(key: Preferences.Key<String>, value: String) {
        context.dataStore.secureEdit(value) { preferences, encryptedValue -> preferences[key] = encryptedValue }
    }

    private inline fun <reified T> Flow<Preferences>.secureMap(
        crossinline fetchValue: (value: Preferences) -> String
    ): Flow<T?> {
        return map { prefs ->
            try {
                val encryptedData = fetchValue(prefs).split(bytesToStringSeparator)
                    .filterNot { it.isEmpty() }
                    .map { result -> result.toByte() }.toByteArray()

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
        edit {
            encryptionProvider.encrypt(securityKeyAlias, Json.encodeToString(value))?.let { encryptedValue ->
                editStore.invoke(it, encryptedValue.joinToString(bytesToStringSeparator))
            }
        }
    }
}
