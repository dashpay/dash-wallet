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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionsUIState(
    val connections: List<DAppConnection> = emptyList(),
    val pendingRequest: ConnectionRequest? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val repository: DashConnectRepository
) : ViewModel() {

    sealed class ApproveResult {
        object Idle : ApproveResult()
        object Loading : ApproveResult()
        data class Success(val connection: DAppConnection) : ApproveResult()
        data class Error(val message: String?) : ApproveResult()
    }

    sealed class ScanOutcome {
        object Idle : ScanOutcome()

        /** The QR was a connection request — the approve sheet should be shown. */
        object ConnectionRequested : ScanOutcome()

        /** The QR was a login request and the session is now active. */
        object LoginCompleted : ScanOutcome()
    }

    private val _uiState = MutableStateFlow(ConnectionsUIState())
    val uiState: StateFlow<ConnectionsUIState> = _uiState.asStateFlow()

    private val _approveResult = MutableStateFlow<ApproveResult>(ApproveResult.Idle)
    val approveResult: StateFlow<ApproveResult> = _approveResult.asStateFlow()

    private val _scanOutcome = MutableStateFlow<ScanOutcome>(ScanOutcome.Idle)
    val scanOutcome: StateFlow<ScanOutcome> = _scanOutcome.asStateFlow()

    init {
        repository.observeConnections()
            .onEach { connections ->
                _uiState.update { it.copy(connections = connections) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Decodes a scanned DashConnect QR code: a connection request becomes the
     * pending request for the approve sheet, a login request completes the login.
     */
    fun onQrScanned(qrContent: String) {
        viewModelScope.launch {
            try {
                when (val payload = repository.parseQr(qrContent)) {
                    is DashConnectQr.Connect -> {
                        _uiState.update { it.copy(pendingRequest = payload.request) }
                        _scanOutcome.value = ScanOutcome.ConnectionRequested
                    }
                    is DashConnectQr.Login -> {
                        repository.completeLogin(payload.connectionId)
                        _scanOutcome.value = ScanOutcome.LoginCompleted
                    }
                }
            } catch (ex: Exception) {
                _uiState.update { it.copy(error = ex.message) }
            }
        }
    }

    fun resetScanOutcome() {
        _scanOutcome.value = ScanOutcome.Idle
    }

    fun approvePendingRequest() {
        val request = _uiState.value.pendingRequest ?: return
        viewModelScope.launch {
            _approveResult.value = ApproveResult.Loading
            try {
                val connection = repository.approveConnection(request)
                _uiState.update { it.copy(pendingRequest = null) }
                _approveResult.value = ApproveResult.Success(connection)
            } catch (ex: Exception) {
                _approveResult.value = ApproveResult.Error(ex.message)
            }
        }
    }

    fun denyPendingRequest() {
        _uiState.update { it.copy(pendingRequest = null) }
    }

    fun resetApproveResult() {
        _approveResult.value = ApproveResult.Idle
    }

    /**
     * Placeholder interaction until the Dash Platform integration lands:
     * an approved connection completes its login, an active one disconnects.
     */
    fun onConnectionClick(connection: DAppConnection) {
        viewModelScope.launch {
            when (connection.status) {
                ConnectionStatus.APPROVED -> repository.completeLogin(connection.id)
                ConnectionStatus.ACTIVE -> repository.disconnect(connection.id)
                ConnectionStatus.DISCONNECTED -> Unit // requires scanning the QR code again
            }
        }
    }
}
