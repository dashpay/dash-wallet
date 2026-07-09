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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.ui.more.connections.protocol.DashKeyRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bitcoinj.core.Base58
import javax.inject.Inject

data class ConnectionsUIState(
    val connections: List<DAppConnection> = emptyList(),
    val pendingRequest: ConnectionRequest? = null,
    /** True on mainnet where the key-exchange contract is not deployed. */
    val featureUnavailable: Boolean = !Constants.IS_TESTNET_BUILD,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val repository: DashConnectRepository,
    private val identityRepository: IdentityRepository
) : ViewModel() {

    sealed class ApproveResult {
        object Idle : ApproveResult()
        object Loading : ApproveResult()
        data class Success(val connection: DAppConnection) : ApproveResult()
        data class Error(val message: String?) : ApproveResult()
    }

    sealed class ScanOutcome {
        object Idle : ScanOutcome()

        /** The QR was a login request — the approve sheet should be shown. */
        object ConnectionRequested : ScanOutcome()

        /** The QR was a key-registration (dash-st) request and it completed. */
        object LoginCompleted : ScanOutcome()

        data class Error(val message: String?) : ScanOutcome()
    }

    private val _uiState = MutableStateFlow(ConnectionsUIState())
    val uiState: StateFlow<ConnectionsUIState> = _uiState.asStateFlow()

    private val _approveResult = MutableStateFlow<ApproveResult>(ApproveResult.Idle)
    val approveResult: StateFlow<ApproveResult> = _approveResult.asStateFlow()

    private val _scanOutcome = MutableStateFlow<ScanOutcome>(ScanOutcome.Idle)
    val scanOutcome: StateFlow<ScanOutcome> = _scanOutcome.asStateFlow()

    /** The validated login request awaiting approval; retained separately from the UI claim. */
    private var pendingLoginRequest: DashKeyRequest? = null

    init {
        repository.observeConnections()
            .onEach { connections ->
                _uiState.update { it.copy(connections = connections) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Decodes a scanned DashConnect QR code. A `dash-key:` login request builds the pending request
     * for the approve sheet (with the wallet's OWN verified identity for display); a `dash-st:`
     * key-registration completes immediately.
     */
    fun onQrScanned(qrContent: String) {
        viewModelScope.launch {
            try {
                when (val payload = repository.parseQr(qrContent)) {
                    is DashConnectQr.Login -> {
                        pendingLoginRequest = payload.request
                        _uiState.update {
                            it.copy(pendingRequest = toConnectionRequest(payload.request))
                        }
                        _scanOutcome.value = ScanOutcome.ConnectionRequested
                    }
                    is DashConnectQr.KeyRegistration -> {
                        repository.completeKeyRegistration(payload.request)
                        _scanOutcome.value = ScanOutcome.LoginCompleted
                    }
                }
            } catch (ex: Exception) {
                _scanOutcome.value = ScanOutcome.Error(ex.message)
                _uiState.update { it.copy(error = ex.message) }
            }
        }
    }

    private suspend fun toConnectionRequest(request: DashKeyRequest): ConnectionRequest {
        val username = identityRepository.getUsername().orEmpty()
        val identityId = identityRepository.blockchainIdentity?.uniqueIdString.orEmpty()
        return ConnectionRequest(
            appLabel = request.label,
            appContractId = Base58.encode(request.contractId),
            walletUsername = username,
            walletIdentityId = identityId
        )
    }

    fun resetScanOutcome() {
        _scanOutcome.value = ScanOutcome.Idle
    }

    fun approvePendingRequest() {
        val request = pendingLoginRequest ?: return
        viewModelScope.launch {
            _approveResult.value = ApproveResult.Loading
            try {
                val connection = repository.approveLogin(request)
                pendingLoginRequest = null
                _uiState.update { it.copy(pendingRequest = null) }
                _approveResult.value = ApproveResult.Success(connection)
            } catch (ex: Exception) {
                _approveResult.value = ApproveResult.Error(ex.message)
            }
        }
    }

    fun denyPendingRequest() {
        pendingLoginRequest = null
        _uiState.update { it.copy(pendingRequest = null) }
    }

    fun resetApproveResult() {
        _approveResult.value = ApproveResult.Idle
    }

    /** An active connection can be disconnected; other states require scanning a QR again. */
    fun onConnectionClick(connection: DAppConnection) {
        viewModelScope.launch {
            when (connection.status) {
                ConnectionStatus.ACTIVE -> repository.disconnect(connection.id)
                ConnectionStatus.APPROVED,
                ConnectionStatus.DISCONNECTED -> Unit // requires scanning the QR code again
            }
        }
    }
}
