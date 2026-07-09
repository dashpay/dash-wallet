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

import de.schildbach.wallet.ui.more.connections.protocol.DashKeyRequest
import de.schildbach.wallet.ui.more.connections.protocol.DashStRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * An app on the Dash network connected (or previously connected) to this wallet. Identified by
 * the app's data-contract id (Base58), which is stable across logins.
 */
data class DAppConnection(
    /** The app's data-contract id, Base58-encoded. Stable identity of the connection. */
    val id: String,
    val name: String,
    val url: String,
    val status: ConnectionStatus,
    /** Millis since epoch of the last status change. */
    val updatedAt: Long
)

/**
 * A pending connection request derived from a scanned `dash-key:` login QR (QR #1).
 *
 * Note: [appLabel] is UNAUTHENTICATED — it is a claim carried in the QR by the app and is
 * spoofable. The [walletUsername] / [walletIdentityId] shown to the user are the wallet's OWN
 * verified identity, resolved locally, and are what the app will actually learn on login.
 */
data class ConnectionRequest(
    /** Unauthenticated label claimed by the app (may be blank). */
    val appLabel: String,
    /** The app's data-contract id, Base58-encoded. */
    val appContractId: String,
    /** The wallet user's own DashPay username (verified, resolved locally). */
    val walletUsername: String,
    /** The wallet user's own identity id, Base58-encoded (verified). */
    val walletIdentityId: String
)

/** The decoded payload of a scanned DashConnect QR code. */
sealed class DashConnectQr {
    /** First QR (`dash-key:`): an app requests a login the user must approve. */
    data class Login(val request: DashKeyRequest) : DashConnectQr()

    /** Second QR (`dash-st:`): first-login key registration on the identity. */
    data class KeyRegistration(val request: DashStRequest) : DashConnectQr()
}

/**
 * Boundary for DashConnect data, backed by Dash Platform. [MockDashConnectRepository] provides
 * placeholder data for tests and Compose previews.
 */
interface DashConnectRepository {
    fun observeConnections(): Flow<List<DAppConnection>>

    /** Decodes and validates a scanned QR into a login request or a key-registration request. */
    suspend fun parseQr(qrContent: String): DashConnectQr

    /**
     * Approves a `dash-key:` login request: derives the login key, encrypts it against the app's
     * ephemeral key, and publishes (or replaces) the `loginKeyResponse` document on the
     * key-exchange contract. Returns the resulting connection.
     */
    suspend fun approveLogin(request: DashKeyRequest): DAppConnection

    /**
     * Completes a `dash-st:` first-login key registration: validates the transition end-to-end,
     * signs it with the identity master key and broadcasts it.
     */
    suspend fun completeKeyRegistration(request: DashStRequest)

    suspend fun disconnect(connectionId: String)
}

/**
 * In-memory mock used by tests and previews. Treats the app contract id as the connection id.
 */
@Singleton
class MockDashConnectRepository @Inject constructor() : DashConnectRepository {
    private val connections = MutableStateFlow<List<DAppConnection>>(emptyList())

    override fun observeConnections(): Flow<List<DAppConnection>> = connections.asStateFlow()

    override suspend fun parseQr(qrContent: String): DashConnectQr {
        throw UnsupportedOperationException("MockDashConnectRepository does not parse real QR codes")
    }

    override suspend fun approveLogin(request: DashKeyRequest): DAppConnection {
        val id = org.bitcoinj.core.Base58.encode(request.contractId)
        val connection = DAppConnection(
            id = id,
            name = request.label.ifBlank { "Unknown app" },
            url = "",
            status = ConnectionStatus.ACTIVE,
            updatedAt = System.currentTimeMillis()
        )
        connections.value = connections.value.filter { it.id != id } + connection
        return connection
    }

    override suspend fun completeKeyRegistration(request: DashStRequest) = Unit

    override suspend fun disconnect(connectionId: String) {
        connections.value = connections.value.map {
            if (it.id == connectionId) {
                it.copy(status = ConnectionStatus.DISCONNECTED, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
    }
}
