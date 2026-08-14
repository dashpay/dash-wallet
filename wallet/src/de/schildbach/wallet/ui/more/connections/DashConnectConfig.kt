/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.more.connections

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A locally-persisted DashConnect connection record. Platform is the source of truth for whether
 * a `loginKeyResponse` document exists, but the human-facing label/url and the status transitions
 * (approved → active → disconnected) are local UI state that Platform does not carry, so they are
 * persisted here, keyed by the app's contract id.
 */
data class StoredConnection(
    val contractId: String,
    val label: String,
    val url: String,
    val status: String,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put(KEY_CONTRACT_ID, contractId)
        .put(KEY_LABEL, label)
        .put(KEY_URL, url)
        .put(KEY_STATUS, status)
        .put(KEY_UPDATED_AT, updatedAt)

    companion object {
        private const val KEY_CONTRACT_ID = "contractId"
        private const val KEY_LABEL = "label"
        private const val KEY_URL = "url"
        private const val KEY_STATUS = "status"
        private const val KEY_UPDATED_AT = "updatedAt"

        fun fromJson(obj: JSONObject): StoredConnection = StoredConnection(
            contractId = obj.getString(KEY_CONTRACT_ID),
            label = obj.optString(KEY_LABEL),
            url = obj.optString(KEY_URL),
            status = obj.getString(KEY_STATUS),
            updatedAt = obj.optLong(KEY_UPDATED_AT)
        )
    }
}

/**
 * DataStore-backed store of DashConnect connections, following the [BaseConfig] pattern used by
 * `DashPayConfig`. Connections are serialized to a single JSON-array string preference (kept small;
 * a user has at most a handful of connected apps), avoiding a Room migration.
 */
@Singleton
open class DashConnectConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider
) : BaseConfig(context, PREFERENCES_NAME, walletDataProvider) {

    companion object {
        const val PREFERENCES_NAME = "dash_connect"
        val CONNECTIONS = stringPreferencesKey("connections")
    }

    fun observeConnections(): Flow<List<StoredConnection>> =
        observe(CONNECTIONS).map { decode(it) }

    suspend fun getConnections(): List<StoredConnection> = decode(get(CONNECTIONS))

    /** Inserts or replaces the stored connection keyed by [StoredConnection.contractId]. */
    suspend fun upsertConnection(connection: StoredConnection) {
        val current = getConnections().filter { it.contractId != connection.contractId }
        set(CONNECTIONS, encode(current + connection))
    }

    suspend fun updateStatus(contractId: String, status: String, updatedAt: Long) {
        val updated = getConnections().map {
            if (it.contractId == contractId) it.copy(status = status, updatedAt = updatedAt) else it
        }
        set(CONNECTIONS, encode(updated))
    }

    suspend fun removeConnection(contractId: String) {
        set(CONNECTIONS, encode(getConnections().filter { it.contractId != contractId }))
    }

    private fun encode(connections: List<StoredConnection>): String {
        val array = JSONArray()
        connections.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun decode(raw: String?): List<StoredConnection> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { StoredConnection.fromJson(array.getJSONObject(it)) }
        } catch (ex: Exception) {
            emptyList()
        }
    }
}
