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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lifecycle of a DashConnect connection (Figma: DashConnect section 5775:50970).
 *
 *  - [APPROVED] — connection approved, awaiting login ("Scan the QR code to complete your login")
 *  - [ACTIVE] — session is active ("Touch to disconnect at any time")
 *  - [DISCONNECTED] — session ended ("Scan the QR code to log back in")
 */
enum class ConnectionStatus {
    APPROVED,
    ACTIVE,
    DISCONNECTED
}

/** An app on the Dash network connected (or previously connected) to this wallet. */
data class DAppConnection(
    val id: String,
    val name: String,
    val url: String,
    val status: ConnectionStatus,
    /** Millis since epoch of the last status change. */
    val updatedAt: Long
)

/** A pending connection request decoded from a scanned DashConnect QR code. */
data class ConnectionRequest(
    val appName: String,
    val appUrl: String,
    val username: String,
    val identity: String
)

/** The decoded payload of a scanned DashConnect QR code. */
sealed class DashConnectQr {
    /** First QR: an app requests a connection, which the user must approve. */
    data class Connect(val request: ConnectionRequest) : DashConnectQr()

    /** Second QR: an already-approved app requests a login. */
    data class Login(val connectionId: String) : DashConnectQr()
}

/**
 * Boundary for DashConnect data. The real implementation will be backed by a
 * Dash Platform data contract; [MockDashConnectRepository] provides placeholder
 * data until that integration lands.
 */
interface DashConnectRepository {
    fun observeConnections(): Flow<List<DAppConnection>>

    /** Decodes a scanned QR code into a connection request or a login request. */
    suspend fun parseQr(qrContent: String): DashConnectQr

    suspend fun approveConnection(request: ConnectionRequest): DAppConnection

    /** Marks the connection as logged in / session active. */
    suspend fun completeLogin(connectionId: String)

    suspend fun disconnect(connectionId: String)
}

@Singleton
class MockDashConnectRepository @Inject constructor() : DashConnectRepository {
    private val connections = MutableStateFlow<List<DAppConnection>>(emptyList())

    override fun observeConnections(): Flow<List<DAppConnection>> = connections.asStateFlow()

    override suspend fun parseQr(qrContent: String): DashConnectQr {
        // Placeholder: a real implementation will decode the QR payload and
        // resolve the requesting app via a Dash Platform data contract. Until
        // then, a scan is a login request if a connection is awaiting one,
        // otherwise a new connection request.
        val awaitingLogin = connections.value.firstOrNull {
            it.status == ConnectionStatus.APPROVED || it.status == ConnectionStatus.DISCONNECTED
        }
        return if (awaitingLogin != null) {
            DashConnectQr.Login(awaitingLogin.id)
        } else {
            DashConnectQr.Connect(
                ConnectionRequest(
                    appName = "Yappr",
                    appUrl = "yappr.io",
                    username = "john.doe",
                    identity = "5DbLwAx…zUo8"
                )
            )
        }
    }

    override suspend fun approveConnection(request: ConnectionRequest): DAppConnection {
        val connection = DAppConnection(
            id = UUID.randomUUID().toString(),
            name = request.appName,
            url = request.appUrl,
            status = ConnectionStatus.APPROVED,
            updatedAt = System.currentTimeMillis()
        )
        connections.value = connections.value.filter { it.name != connection.name } + connection
        return connection
    }

    override suspend fun completeLogin(connectionId: String) {
        setStatus(connectionId, ConnectionStatus.ACTIVE)
    }

    override suspend fun disconnect(connectionId: String) {
        setStatus(connectionId, ConnectionStatus.DISCONNECTED)
    }

    private fun setStatus(connectionId: String, status: ConnectionStatus) {
        connections.value = connections.value.map {
            if (it.id == connectionId) {
                it.copy(status = status, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
    }
}
