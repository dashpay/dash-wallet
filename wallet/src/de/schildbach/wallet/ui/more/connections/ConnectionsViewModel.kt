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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Base58
import javax.inject.Inject

data class ConnectionsUIState(
    val connections: List<DAppConnection> = emptyList(),
    val pendingRequest: ConnectionRequest? = null,
    /** Connect requires Platform support and is disabled in production/on mainnet. */
    val featureUnavailable: Boolean = !Constants.SUPPORTS_PLATFORM || Constants.IS_PROD_BUILD ||
        Constants.NETWORK_PARAMETERS.id == NetworkParameters.ID_MAINNET,
    val isLoading: Boolean = false,
    val requiresRevocation: Boolean = false,
    val pendingPurchase: TokenPurchaseApproval? = null,
    val purchaseStatus: TokenPurchaseStatus = TokenPurchaseStatus.READY,
    val purchaseError: String? = null,
    val error: String? = null
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val repository: DashConnectRepository,
    private val identityRepository: IdentityRepository
) : ViewModel() {

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(ConnectionsViewModel::class.java)
    }

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

        data class Error(val message: String?) : ScanOutcome()
    }

    private val _uiState = MutableStateFlow(ConnectionsUIState())
    val uiState: StateFlow<ConnectionsUIState> = _uiState.asStateFlow()

    private val _approveResult = MutableStateFlow<ApproveResult>(ApproveResult.Idle)
    val approveResult: StateFlow<ApproveResult> = _approveResult.asStateFlow()

    private val _scanOutcome = MutableStateFlow<ScanOutcome>(ScanOutcome.Idle)
    val scanOutcome: StateFlow<ScanOutcome> = _scanOutcome.asStateFlow()
    private val _callbackOffer = MutableStateFlow<String?>(null)
    val callbackOffer: StateFlow<String?> = _callbackOffer.asStateFlow()

    /** The validated login request awaiting approval; retained separately from the UI claim. */
    private var pendingLoginRequest: DashKeyRequest? = null
    private var pendingPlan: AppConnectPlan? = null
    private var pendingOpenedFromUrl = false
    private var operationInProgress = false
    private var walletUnlockCheck: () -> Boolean = { false }

    internal val isWalletUnlocked: Boolean get() = walletUnlockCheck()

    internal fun bindWalletUnlockCheck(check: () -> Boolean) {
        walletUnlockCheck = check
    }

    init {
        repository.observeConnections()
            .catch { error ->
                log.error("Cannot read connection grant records", error)
                _scanOutcome.value = ScanOutcome.Error("Connection records could not be read. No access has been revoked.")
            }
            .onEach { connections ->
                _uiState.update { it.copy(connections = connections) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Decodes a scanned DashConnect QR code. A `dash-key:` login request builds the pending request
     * for the approve sheet with the wallet's own verified identity for display.
     */
    fun onQrScanned(qrContent: String) = prepareRequest(qrContent, openedFromUrl = false)

    fun onUrlOpened(uri: String) = prepareRequest(uri, openedFromUrl = true)

    private fun prepareRequest(qrContent: String, openedFromUrl: Boolean) {
        if (operationInProgress || pendingLoginRequest != null || _uiState.value.pendingPurchase != null) return
        operationInProgress = true
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                check(Constants.SUPPORTS_PLATFORM) { "App Connect is unavailable on this device" }
                // Keep the URI in this pending coroutine until local identity loading finishes.
                // A completed load with no identity is handled by the normal preparation error.
                identityRepository.initializationResult.filterNotNull().first().getOrThrow()
                if (de.schildbach.wallet.ui.more.connections.protocol.DashConnectUri.isStUri(qrContent.trim())) {
                    val purchase = repository.prepareTokenPurchase(
                        de.schildbach.wallet.ui.more.connections.protocol.DashConnectUri.parseStRequest(qrContent.trim()))
                    _uiState.update { it.copy(pendingPurchase = purchase,
                        purchaseStatus = TokenPurchaseStatus.READY, purchaseError = null) }
                    return@launch
                }
                val payload = repository.parseQr(qrContent)
                val plan = repository.prepareLogin(payload.request)
                // Resolve BEFORE update {}: that is a compare-and-set retry loop, and
                // toConnectionRequest makes two Platform round trips — a CAS retry would
                // repeat them (and could observe different results between attempts).
                val request = toConnectionRequest(payload.request)
                pendingLoginRequest = payload.request
                pendingPlan = plan
                pendingOpenedFromUrl = openedFromUrl
                _uiState.update { it.copy(pendingRequest = request.copy(permissionText = plan?.permissionText.orEmpty(),
                    grantChoices = plan?.choices().orEmpty(), recovering = plan?.recovering ?: false,
                    walletIdentityId = plan?.identityId ?: request.walletIdentityId),
                    requiresRevocation = plan?.recovering == true) }
                _approveResult.value = ApproveResult.Idle
                _scanOutcome.value = ScanOutcome.ConnectionRequested
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                log.error("failed to process scanned DashConnect QR", ex)
                _scanOutcome.value = ScanOutcome.Error(ex.message)
                _uiState.update { it.copy(error = ex.message) }
            } finally {
                operationInProgress = false
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun toConnectionRequest(request: DashKeyRequest): ConnectionRequest {
        // Prefer the DPNS display label (e.g. "John.Doe"); fall back to the stored (normalized)
        // username if it can't be resolved from Platform.
        val username = repository.resolveWalletUsername() ?: identityRepository.getUsername().orEmpty()
        val identityId = identityRepository.blockchainIdentity?.uniqueIdString.orEmpty()
        // Prefer the app's on-chain name (contract owner's profile / DPNS) over the spoofable QR
        // label; falls back to the yappr branding and then the raw label.
        val appDisplay = repository.resolveAppDisplay(request.contractId, request.label)
        return ConnectionRequest(
            appLabel = appDisplay.name,
            appUrl = appDisplay.url,
            appContractId = Base58.encode(request.contractId),
            walletUsername = username,
            walletIdentityId = identityId
        )
    }

    fun resetScanOutcome() {
        _scanOutcome.value = ScanOutcome.Idle
    }

    fun approveTokenPurchase() {
        if (!isWalletUnlocked) return
        if (operationInProgress || _uiState.value.purchaseStatus !in setOf(TokenPurchaseStatus.READY, TokenPurchaseStatus.FAILED)) return
        val purchase = _uiState.value.pendingPurchase ?: return
        operationInProgress = true
        viewModelScope.launch {
            try {
                if (!isWalletUnlocked) return@launch
                _uiState.update { it.copy(purchaseStatus = TokenPurchaseStatus.SUBMITTING, purchaseError = null) }
                repository.approveTokenPurchase(purchase)
                _uiState.update { it.copy(purchaseStatus = TokenPurchaseStatus.SUBMITTED) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                log.error("failed to submit DashConnect token purchase", error)
                _uiState.update { it.copy(purchaseStatus = if (error is TokenPurchaseOutcomeUnknown)
                    TokenPurchaseStatus.OUTCOME_UNKNOWN else TokenPurchaseStatus.FAILED, purchaseError = error.message) }
            } finally { operationInProgress = false }
        }
    }

    fun dismissTokenPurchase() {
        if (!isWalletUnlocked) return
        if (operationInProgress) return
        _uiState.update { it.copy(pendingPurchase = null, purchaseStatus = TokenPurchaseStatus.READY, purchaseError = null) }
    }

    fun selectGrant(index: Int, encryption: Boolean, selected: Boolean) {
        if (!isWalletUnlocked) return
        if (operationInProgress || _uiState.value.requiresRevocation) return
        val plan = pendingPlan ?: return
        if (plan.recovering || (!encryption && index == 0)) return
        val changed = if (encryption) plan.copy(selectedEncryption =
            if (selected) plan.selectedEncryption + index else plan.selectedEncryption - index)
        else plan.copy(selectedAuth = if (selected) plan.selectedAuth + index else plan.selectedAuth - index)
        pendingPlan = changed
        _uiState.update { it.copy(pendingRequest = it.pendingRequest?.copy(grantChoices = changed.choices())) }
    }

    fun selectLimits(budget: Int, lifetime: Int) {
        if (!isWalletUnlocked) return
        if (operationInProgress || _uiState.value.requiresRevocation) return
        val plan = pendingPlan ?: return
        if (plan.recovering) return
        val changed = plan.withPresets(budget, lifetime)
        pendingPlan = changed
        _uiState.update { it.copy(pendingRequest = it.pendingRequest?.copy(grantChoices = changed.choices(),
            budgetPreset = budget, lifetimePreset = lifetime)) }
    }

    fun approvePendingRequest() {
        if (!isWalletUnlocked) return
        if (operationInProgress) return
        val request = pendingLoginRequest ?: return
        val plan = pendingPlan
        val openedFromUrl = pendingOpenedFromUrl
        operationInProgress = true
        viewModelScope.launch {
            try {
                if (!isWalletUnlocked) return@launch
                // Once approval starts, a transport failure cannot establish that no grant was published.
                _uiState.update { it.copy(requiresRevocation = true,
                    pendingRequest = it.pendingRequest?.copy(recovering = true)) }
                _approveResult.value = ApproveResult.Loading
                val connection = repository.approvePreparedLogin(request, plan)
                pendingLoginRequest = null
                pendingPlan = null
                pendingOpenedFromUrl = false
                _uiState.update { it.copy(pendingRequest = null, requiresRevocation = false) }
                _approveResult.value = ApproveResult.Success(connection)
                offerCallback(request, "ok", openedFromUrl)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                log.error("failed to approve DashConnect login", ex)
                _approveResult.value = ApproveResult.Error(ex.message)
            } finally { operationInProgress = false }
        }
    }

    fun denyPendingRequest() {
        if (!isWalletUnlocked) return
        if (operationInProgress) return
        val request = pendingLoginRequest ?: return
        val openedFromUrl = pendingOpenedFromUrl
        if (_uiState.value.requiresRevocation) {
            operationInProgress = true
            viewModelScope.launch {
                try {
                    if (!isWalletUnlocked) return@launch
                    _approveResult.value = ApproveResult.Loading
                    repository.revokePreparedLogin(request, pendingPlan)
                    clearPendingRequest()
                    offerCallback(request, "declined", openedFromUrl)
                } catch (ex: Exception) {
                    if (ex is CancellationException) throw ex
                    log.error("failed to revoke pending DashConnect approval", ex)
                    _approveResult.value = ApproveResult.Error(ex.message)
                } finally { operationInProgress = false }
            }
        } else {
            clearPendingRequest()
            offerCallback(request, "declined", openedFromUrl)
        }
    }

    private fun clearPendingRequest() {
        pendingLoginRequest = null
        pendingPlan = null
        pendingOpenedFromUrl = false
        _uiState.update { it.copy(pendingRequest = null, requiresRevocation = false) }
        _approveResult.value = ApproveResult.Idle
    }

    fun closePendingRequest() {
        if (!isWalletUnlocked) return
        if (operationInProgress) return
        if (_uiState.value.requiresRevocation) {
            _scanOutcome.value = ScanOutcome.Error("No revocation was confirmed. Any unresolved grant remains in Connections for recovery.")
            clearPendingRequest()
        } else denyPendingRequest()
    }

    private fun offerCallback(request: DashKeyRequest, status: String, openedFromUrl: Boolean) {
        if (!openedFromUrl) return
        request.appConnect?.callback?.let {
            val uri = android.net.Uri.parse(it.toASCIIString())
            val builder = uri.buildUpon().clearQuery()
            uri.queryParameterNames.filter { name -> name != "ac" }.forEach { name ->
                uri.getQueryParameters(name).forEach { value -> builder.appendQueryParameter(name, value) }
            }
            _callbackOffer.value = builder.appendQueryParameter("ac", status).build().toString()
        }
    }

    fun resetCallbackOffer() { _callbackOffer.value = null }

    suspend fun connectionDetails(connection: DAppConnection): String = repository.connectionDetails(connection.id, connection.scope)

    fun resetApproveResult() {
        _approveResult.value = ApproveResult.Idle
    }

    fun disconnect(connection: DAppConnection) {
        viewModelScope.launch {
            try { if (isWalletUnlocked) repository.disconnect(connection.id, connection.scope) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                _scanOutcome.value = ScanOutcome.Error(e.message)
            }
        }
    }

    /** Cancels a pending login or removes an old connection so the user can start over. */
    fun removeConnection(connection: DAppConnection) {
        viewModelScope.launch {
            try { if (isWalletUnlocked) repository.removeConnection(connection.id, connection.scope) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                _scanOutcome.value = ScanOutcome.Error(e.message)
            }
        }
    }
}
