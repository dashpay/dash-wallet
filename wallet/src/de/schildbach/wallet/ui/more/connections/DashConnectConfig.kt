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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
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
    private val configContext = context

    companion object {
        const val PREFERENCES_NAME = "dash_connect"
        const val AUDIT_FILE_NAME = "$PREFERENCES_NAME.preferences_pb"
        val CONNECTIONS = stringPreferencesKey("connections")
        private val GRANTS = stringPreferencesKey("app_connect_grants_v2")
        private val PURCHASES = stringPreferencesKey("dash_st_purchases")
    }

    // Authorizations and unknown purchase outcomes survive a local wallet reset. Their
    // network/wallet/identity scope makes them visible only when that same wallet returns.
    override suspend fun onWalletWiped() {
        set(CONNECTIONS, "[]")
    }

    fun observeConnections(): Flow<List<StoredConnection>> =
        observe(CONNECTIONS).map { decode(it) }

    suspend fun getConnections(): List<StoredConnection> = decode(get(CONNECTIONS))

    // Grant records are required for revocation. Propagate read errors instead of using
    // BaseConfig's empty-preferences fallback for ordinary settings.
    suspend fun getGrants(): List<JSONObject> = observeGrants().first()

    fun observeGrants(): Flow<List<JSONObject>> = configContext.dataStore.data.map { preferences ->
        decodeGrants(preferences[GRANTS])
    }

    private fun decodeGrants(raw: String?): List<JSONObject> {
        if (raw == null) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    suspend fun saveGrant(grant: JSONObject) {
        configContext.dataStore.edit { preferences ->
            val records = decodeGrants(preferences[GRANTS])
                .filter { it.getString("id") != grant.getString("id") } + grant
            preferences[GRANTS] = JSONArray(records).toString()
        }
    }

    suspend fun purchaseWasSubmitted(id: String): Boolean {
        val records = purchaseReceipts()
        return (0 until records.length()).any { records.getJSONObject(it).getString("id") == id }
    }

    suspend fun savePurchase(receipt: JSONObject) {
        configContext.dataStore.edit { preferences ->
            val records = JSONArray(preferences[PURCHASES] ?: "[]")
            val updated = (0 until records.length()).map { records.getJSONObject(it) }
                .filter { it.getString("id") != receipt.getString("id") } + receipt
            preferences[PURCHASES] = JSONArray(updated).toString()
        }
    }

    // Unlike ordinary preferences, unreadable debit receipts must never look like an empty store.
    private suspend fun purchaseReceipts() =
        JSONArray(configContext.dataStore.data.first()[PURCHASES] ?: "[]")

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

    suspend fun removeLegacyConnection(contractId: String, scope: String?) {
        configContext.dataStore.edit { preferences ->
            check(decodeGrants(preferences[GRANTS]).none {
                it.getString("app") == contractId && it.getString("scope") == scope
            }) { "This connection has grant records. Use Revoke access; its audit cannot be removed locally." }
            preferences[CONNECTIONS] = encode(decode(preferences[CONNECTIONS]).filter { it.contractId != contractId })
        }
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
