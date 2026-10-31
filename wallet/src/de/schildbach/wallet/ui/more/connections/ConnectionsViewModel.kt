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
import de.schildbach.wallet.service.platform.hasInitializedIdentityScope
import de.schildbach.wallet.ui.more.connections.protocol.DashKeyRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Base58
import javax.inject.Inject

enum class ConnectionRecordsStatus { LOADING, READY, UNAVAILABLE, NO_IDENTITY }

/** The Connections page tabs (Figma 40042:59884). */
enum class ConnectionsTab {
    ACTIVE,
    DISCONNECTED,
    EXPIRED;

    /** Pending connections may still hold live keys, so they stay with the active ones. */
    fun includes(connection: DAppConnection): Boolean = when (this) {
        ACTIVE -> !connection.legacyOnly &&
            (connection.status == ConnectionStatus.ACTIVE || connection.status == ConnectionStatus.APPROVED)
        DISCONNECTED -> connection.legacyOnly || connection.status == ConnectionStatus.DISCONNECTED
        EXPIRED -> !connection.legacyOnly && connection.status == ConnectionStatus.EXPIRED
    }
}

/** The connection whose details sheet is open; the connection itself is read from the live list. */
data class ConnectionDetailsState(
    val connectionId: String,
    val username: String = "",
    val identityId: String = "",
    val terms: ConnectionTerms? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

data class ConnectionsUIState(
    val connections: List<DAppConnection> = emptyList(),
    val recordsStatus: ConnectionRecordsStatus = ConnectionRecordsStatus.LOADING,
    val pendingRequest: ConnectionRequest? = null,
    /** Connect requires Platform support and is disabled in production/on mainnet. */
    val featureUnavailable: Boolean = !Constants.SUPPORTS_PLATFORM || Constants.IS_PROD_BUILD ||
        Constants.NETWORK_PARAMETERS.id == NetworkParameters.ID_MAINNET,
    val isLoading: Boolean = false,
    val requiresRevocation: Boolean = false,
    val pendingPurchase: TokenPurchaseApproval? = null,
    val purchaseStatus: TokenPurchaseStatus = TokenPurchaseStatus.READY,
    val purchaseError: String? = null,
    val error: String? = null,
    val selectedTab: ConnectionsTab = ConnectionsTab.ACTIVE,
    val selectedConnection: ConnectionDetailsState? = null,
    /** Unlinked DashPay profile keys to warn about; null when there are none or they were dismissed. */
    val sharedProfileKeys: SharedProfileKeys? = null,
    val isRevokingSharedKeys: Boolean = false
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val repository: DashConnectRepository,
    private val identityRepository: IdentityRepository,
    private val applicationScope: CoroutineScope,
    private val identityAvailability: ConnectIdentityAvailability
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
    private var pendingApprovalFailed = false
    private var pendingApprovalRefreshFailed = false
    private var operationInProgress = false
    private var walletUnlockCheck: () -> Boolean = { false }
    private var connectionsJob: Job? = null
    private var sharedKeysJob: Job? = null
    private var detailsJob: Job? = null
    private var identityRetryJob: Job? = null
    private var walletLockJob: Job? = null
    private var walletLockGeneration: () -> Long = { 0L }
    private val sensitiveOperations = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val foregroundGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var foregroundAvailable = false

    internal val isWalletUnlocked: Boolean get() = foregroundAvailable && walletUnlockCheck()

    internal fun onForegroundChanged(available: Boolean) {
        if (!available) {
            foregroundAvailable = false
            foregroundGeneration.incrementAndGet()
            cancelSensitiveOperations()
        } else foregroundAvailable = true
    }
    internal val canUseConnections: Boolean get() = _uiState.value.recordsStatus == ConnectionRecordsStatus.READY

    private fun requireConnectionRecords() {
        check(_uiState.value.recordsStatus != ConnectionRecordsStatus.NO_IDENTITY) { "Create a username to connect apps." }
        check(canUseConnections) { "Connection records are unavailable. Retry loading them before continuing. No access has been revoked." }
    }

    internal fun bindWalletUnlockCheck(check: () -> Boolean) {
        walletUnlockCheck = check
        if (!isWalletUnlocked) cancelSensitiveOperations()
    }

    internal fun bindWalletLockState(locked: StateFlow<Boolean>, generation: () -> Long = { 0L }) {
        walletLockJob?.cancel()
        walletLockGeneration = generation
        walletLockJob = locked.onEach { if (it) cancelSensitiveOperations() }.launchIn(viewModelScope)
    }

    private fun cancelSensitiveOperations() {
        sensitiveOperations.cancelChildren(CancellationException("Wallet locked"))
    }

    private fun launchAuthorizedOperation(singleFlight: Boolean = false, block: suspend CoroutineScope.() -> Unit): Job {
        val generation = walletLockGeneration
        val approvedGeneration = generation()
        val approvedForeground = foregroundGeneration.get()
        return viewModelScope.launch(sensitiveOperations + ConnectionAuthorization {
            isWalletUnlocked && generation() == approvedGeneration && foregroundGeneration.get() == approvedForeground
        }) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                val message = "Operation interrupted. Review its status before continuing. Any recovery records have been retained."
                if (_approveResult.value == ApproveResult.Loading) _approveResult.value = ApproveResult.Error(message)
                _uiState.update { state ->
                    when (state.purchaseStatus) {
                        TokenPurchaseStatus.SUBMITTING -> state.copy(purchaseStatus = TokenPurchaseStatus.OUTCOME_UNKNOWN, purchaseError = message)
                        TokenPurchaseStatus.SETTING_UP_KEY -> state.copy(purchaseStatus = TokenPurchaseStatus.FAILED, purchaseError = message)
                        else -> state
                    }
                }
                throw cancelled
            }
        }.also { job -> job.invokeOnCompletion { if (singleFlight) operationInProgress = false } }
    }

    init {
        refreshConnections()
    }

    fun retryConnections() {
        if (_uiState.value.featureUnavailable || identityRetryJob?.isActive == true) return
        val initialized = identityRepository.initializationResult.value
        val reloadIdentity = initialized != null && (initialized.isFailure || !identityRepository.hasInitializedIdentityScope())
        refreshConnections()
        if (!reloadIdentity) return
        val identities = identityRepository
        // Loading shared identity metadata must finish even if this screen is closed.
        identityRetryJob = applicationScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                identities.init()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                log.error("Cannot reload identity for connection records", error)
            }
        }
    }

    fun refreshConnections() {
        connectionsJob?.cancel()
        _uiState.update { it.copy(recordsStatus = ConnectionRecordsStatus.LOADING, connections = emptyList()) }
        if (!Constants.SUPPORTS_PLATFORM || Constants.IS_PROD_BUILD ||
            Constants.NETWORK_PARAMETERS.id == NetworkParameters.ID_MAINNET) {
            _uiState.update { it.copy(featureUnavailable = true, recordsStatus = ConnectionRecordsStatus.UNAVAILABLE) }
            return
        }
        connectionsJob = viewModelScope.launch {
            identityAvailability.observe().collectLatest { availability ->
                kotlin.coroutines.coroutineContext.ensureActive()
                _uiState.update { it.copy(recordsStatus = ConnectionRecordsStatus.LOADING, connections = emptyList()) }
                when (availability) {
                    ConnectIdentityStatus.LOADING -> return@collectLatest
                    ConnectIdentityStatus.NO_IDENTITY -> {
                        _uiState.update { it.copy(recordsStatus = ConnectionRecordsStatus.NO_IDENTITY) }
                        return@collectLatest
                    }
                    ConnectIdentityStatus.UNAVAILABLE -> {
                        _uiState.update { it.copy(recordsStatus = ConnectionRecordsStatus.UNAVAILABLE) }
                        return@collectLatest
                    }
                    ConnectIdentityStatus.AVAILABLE -> loadSharedProfileKeys()
                }
                try {
                    // Keep rows and availability in this cancellable observation, not separate buffered consumers.
                    repository.observeConnections().collect { connections ->
                        kotlin.coroutines.coroutineContext.ensureActive()
                        _uiState.update { it.copy(connections = connections, recordsStatus = ConnectionRecordsStatus.READY) }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    kotlin.coroutines.coroutineContext.ensureActive()
                    log.error("Cannot establish connection scope or read grant records", error)
                    _uiState.update { it.copy(recordsStatus = ConnectionRecordsStatus.UNAVAILABLE, connections = emptyList()) }
                }
            }
        }
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
                check(!Constants.IS_PROD_BUILD && Constants.NETWORK_PARAMETERS.id != NetworkParameters.ID_MAINNET) {
                    "App Connect is unavailable in production or on mainnet"
                }
                uiState.first { it.recordsStatus != ConnectionRecordsStatus.LOADING }
                // Keep the URI in this pending coroutine until local identity loading finishes.
                // A completed load with no identity is handled by the normal preparation error.
                identityRepository.initializationResult.filterNotNull().first().getOrThrow()
                requireConnectionRecords()
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
                pendingApprovalFailed = false
                pendingApprovalRefreshFailed = false
                _uiState.update { state -> state.copy(pendingRequest = request.copy(permissionText = plan?.permissionText.orEmpty(),
                    recovering = plan?.recovering ?: false,
                    requiresGroupReapproval = plan?.requiresGroupReapproval ?: false,
                    groupScopeChangeApproved = false,
                    walletIdentityId = plan?.identityId ?: request.walletIdentityId,
                    requestedAt = plan?.requestedAt ?: 0,
                    firstConnection = state.connections.none { it.id == request.appContractId })
                    .let { if (plan == null) it else it.withTerms(plan) },
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
        val submission = TokenPurchaseSubmission()
        launchAuthorizedOperation(singleFlight = true) {
            try {
                if (!isWalletUnlocked) return@launchAuthorizedOperation
                requireConnectionRecords()
                check(!purchase.requiresSigningKey) { "Set up the wallet purchase signing key before approving a purchase" }
                _uiState.update { it.copy(purchaseStatus = TokenPurchaseStatus.SUBMITTING, purchaseError = null) }
                withContext(submission) { repository.approveTokenPurchase(purchase) }
                coroutineContext.connectionAuthorization()()
                _uiState.update { it.copy(purchaseStatus = TokenPurchaseStatus.SUBMITTED) }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    _uiState.update { it.copy(purchaseStatus = if (submission.started)
                        TokenPurchaseStatus.OUTCOME_UNKNOWN else TokenPurchaseStatus.FAILED,
                        purchaseError = if (submission.started) "Purchase may have been submitted. Check your balances; do not retry."
                            else "Purchase interrupted before submission. Unlock and approve again.") }
                    throw error
                }
                log.error("failed to submit DashConnect token purchase", error)
                _uiState.update { it.copy(purchaseStatus = if (error is TokenPurchaseOutcomeUnknown)
                    TokenPurchaseStatus.OUTCOME_UNKNOWN else TokenPurchaseStatus.FAILED, purchaseError = error.message) }
            } finally { operationInProgress = false }
        }
    }

    fun setupPurchaseSigningKey() {
        if (!isWalletUnlocked || operationInProgress) return
        if (_uiState.value.purchaseStatus !in setOf(TokenPurchaseStatus.READY, TokenPurchaseStatus.FAILED)) return
        val purchase = _uiState.value.pendingPurchase ?: return
        if (!purchase.requiresSigningKey) return
        operationInProgress = true
        launchAuthorizedOperation(singleFlight = true) {
            try {
                if (!isWalletUnlocked) return@launchAuthorizedOperation
                requireConnectionRecords()
                _uiState.update { it.copy(purchaseStatus = TokenPurchaseStatus.SETTING_UP_KEY, purchaseError = null) }
                repository.setupPurchaseSigningKey(purchase)
                val refreshed = repository.prepareTokenPurchase(purchase.request)
                coroutineContext.connectionAuthorization()()
                _uiState.update { it.copy(pendingPurchase = refreshed, purchaseStatus = TokenPurchaseStatus.READY) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _uiState.update { it.copy(purchaseStatus = TokenPurchaseStatus.FAILED, purchaseError = error.message) }
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
        _uiState.update { it.copy(pendingRequest = it.pendingRequest?.withTerms(changed)) }
    }

    fun selectLimits(budget: Int, lifetime: Int) {
        if (!isWalletUnlocked) return
        if (operationInProgress || _uiState.value.requiresRevocation) return
        val plan = pendingPlan ?: return
        if (plan.recovering) return
        val changed = plan.withPresets(budget, lifetime)
        pendingPlan = changed
        _uiState.update { it.copy(pendingRequest = it.pendingRequest?.withTerms(changed)) }
    }

    fun approveGroupScopeChange(approved: Boolean) {
        if (!isWalletUnlocked || operationInProgress) return
        val plan = pendingPlan ?: return
        if (!plan.requiresGroupReapproval) return
        pendingPlan = plan.copy(groupScopeChangeApproved = approved)
        _uiState.update { it.copy(pendingRequest = it.pendingRequest?.copy(groupScopeChangeApproved = approved)) }
    }

    fun approvePendingRequest() {
        if (!isWalletUnlocked) return
        if (operationInProgress) return
        val request = pendingLoginRequest ?: return
        val plan = pendingPlan
        if (pendingApprovalRefreshFailed) {
            _approveResult.value = ApproveResult.Error("Approval terms could not be refreshed. Scan a new QR or revoke access.")
            return
        }
        if (plan?.requiresGroupReapproval == true && !plan.groupScopeChangeApproved) {
            _approveResult.value = ApproveResult.Error("Acknowledge the updated group scope before approving this recovered request.")
            return
        }
        val openedFromUrl = pendingOpenedFromUrl
        operationInProgress = true
        launchAuthorizedOperation(singleFlight = true) {
            try {
                if (!isWalletUnlocked) return@launchAuthorizedOperation
                requireConnectionRecords()
                // Once approval starts, a transport failure cannot establish that no grant was published.
                _uiState.update { it.copy(requiresRevocation = true,
                    pendingRequest = it.pendingRequest?.copy(recovering = true)) }
                _approveResult.value = ApproveResult.Loading
                val connection = repository.approvePreparedLogin(request, plan)
                coroutineContext.connectionAuthorization()()
                pendingLoginRequest = null
                pendingPlan = null
                pendingOpenedFromUrl = false
                pendingApprovalFailed = false
                _uiState.update { it.copy(pendingRequest = null, requiresRevocation = false) }
                _approveResult.value = ApproveResult.Success(connection)
                offerCallback(request, "ok", openedFromUrl)
            } catch (ex: Exception) {
                pendingApprovalFailed = true
                if (ex is CancellationException) throw ex
                log.error("failed to approve DashConnect login", ex)
                try {
                    val refreshed = checkNotNull(repository.prepareLogin(request)) { "Approval terms unavailable" }
                    coroutineContext.connectionAuthorization()()
                    pendingPlan = refreshed
                    pendingApprovalRefreshFailed = false
                    _uiState.update { state -> state.copy(requiresRevocation = refreshed.recovering ||
                        (ex !is GroupScopeChangedException && state.requiresRevocation),
                        pendingRequest = state.pendingRequest?.copy(
                        permissionText = refreshed.permissionText,
                        recovering = refreshed.recovering,
                        requiresGroupReapproval = refreshed.requiresGroupReapproval,
                        groupScopeChangeApproved = false,
                        requestedAt = refreshed.requestedAt
                    )?.withTerms(refreshed)) }
                } catch (refreshError: Exception) {
                    if (refreshError is CancellationException) throw refreshError
                    pendingApprovalRefreshFailed = true
                    log.error("failed to refresh DashConnect approval terms", refreshError)
                    _approveResult.value = ApproveResult.Error("Approval terms could not be refreshed. Scan a new QR. ${refreshError.message}")
                    return@launchAuthorizedOperation
                }
                _approveResult.value = ApproveResult.Error(ex.message)
            } finally { operationInProgress = false }
        }
    }

    fun denyPendingRequest() {
        if (!isWalletUnlocked) return
        if (operationInProgress) return
        val request = pendingLoginRequest ?: return
        val openedFromUrl = pendingOpenedFromUrl
        val terminalStatus = if (pendingApprovalFailed) "failed" else "declined"
        if (_uiState.value.requiresRevocation) {
            operationInProgress = true
            launchAuthorizedOperation(singleFlight = true) {
                try {
                    if (!isWalletUnlocked) return@launchAuthorizedOperation
                    requireConnectionRecords()
                    _approveResult.value = ApproveResult.Loading
                    repository.revokePreparedLogin(request, pendingPlan)
                    coroutineContext.connectionAuthorization()()
                    clearPendingRequest()
                    offerCallback(request, terminalStatus, openedFromUrl)
                } catch (ex: Exception) {
                    if (ex is CancellationException) throw ex
                    log.error("failed to revoke pending DashConnect approval", ex)
                    _approveResult.value = ApproveResult.Error(ex.message)
                } finally { operationInProgress = false }
            }
        } else {
            clearPendingRequest()
            offerCallback(request, terminalStatus, openedFromUrl)
        }
    }

    private fun clearPendingRequest() {
        pendingLoginRequest = null
        pendingPlan = null
        pendingOpenedFromUrl = false
        pendingApprovalFailed = false
        pendingApprovalRefreshFailed = false
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

    suspend fun connectionDetails(connection: DAppConnection): String {
        requireConnectionRecords()
        return repository.connectionDetails(connection.id, connection.scope).also { requireConnectionRecords() }
    }

    fun selectTab(tab: ConnectionsTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    /** Opens the details sheet for [connection] and loads what its recorded keys allow. */
    fun openConnection(connection: DAppConnection) {
        if (!isWalletUnlocked || !canUseConnections) return
        // Each opening owns its load, so a slower earlier load cannot overwrite a newer one.
        detailsJob?.cancel()
        _uiState.update { it.copy(selectedConnection = ConnectionDetailsState(connection.id)) }
        detailsJob = viewModelScope.launch {
            val loaded = try {
                requireConnectionRecords()
                val terms = repository.connectionTerms(connection.id, connection.scope)
                val username = repository.resolveWalletUsername() ?: identityRepository.getUsername().orEmpty()
                ConnectionDetailsState(connection.id, username,
                    identityRepository.blockchainIdentity?.uniqueIdString.orEmpty(), terms, isLoading = false)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                log.error("failed to load DashConnect connection details", ex)
                ConnectionDetailsState(connection.id, isLoading = false, error = ex.message)
            }
            _uiState.update { state ->
                if (state.selectedConnection?.connectionId != connection.id) state else state.copy(selectedConnection = loaded)
            }
        }
    }

    private fun loadSharedProfileKeys() {
        sharedKeysJob?.cancel()
        _uiState.update { it.copy(sharedProfileKeys = null) }
        sharedKeysJob = viewModelScope.launch {
            // Best effort: without Platform the warning just stays hidden.
            val keys = try { repository.sharedProfileKeys() } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("Cannot check for unlinked DashPay profile keys", e)
                null
            }
            _uiState.update { it.copy(sharedProfileKeys = keys) }
        }
    }

    fun dismissSharedProfileKeys() {
        val keys = _uiState.value.sharedProfileKeys ?: return
        _uiState.update { it.copy(sharedProfileKeys = null) }
        applicationScope.launch {
            try {
                repository.dismissSharedProfileKeys(keys)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.error("Cannot save dismissed DashPay profile keys", e)
                // Not saved, so show the warning again rather than let it return unexplained later.
                _uiState.update { if (it.sharedProfileKeys == null) it.copy(sharedProfileKeys = keys) else it }
            }
        }
    }

    fun revokeSharedProfileKeys() {
        val keys = _uiState.value.sharedProfileKeys ?: return
        if (_uiState.value.isRevokingSharedKeys) return
        _uiState.update { it.copy(isRevokingSharedKeys = true) }
        launchAuthorizedOperation {
            try {
                if (isWalletUnlocked) {
                    requireConnectionRecords()
                    repository.revokeSharedProfileKeys(keys)
                    _uiState.update { it.copy(sharedProfileKeys = null) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _scanOutcome.value = ScanOutcome.Error(e.message)
            } finally {
                _uiState.update { it.copy(isRevokingSharedKeys = false) }
            }
        }
    }

    fun closeConnection() {
        detailsJob?.cancel()
        _uiState.update { it.copy(selectedConnection = null) }
    }

    fun resetApproveResult() {
        _approveResult.value = ApproveResult.Idle
    }

    fun disconnect(connection: DAppConnection) {
        launchAuthorizedOperation {
            try { if (isWalletUnlocked) { requireConnectionRecords(); repository.disconnect(connection.id, connection.scope) } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                _scanOutcome.value = ScanOutcome.Error(e.message)
            }
        }
    }

    /** Cancels a pending login or removes an old connection so the user can start over. */
    fun removeConnection(connection: DAppConnection) {
        launchAuthorizedOperation {
            try { if (isWalletUnlocked) { requireConnectionRecords(); repository.removeConnection(connection.id, connection.scope) } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                _scanOutcome.value = ScanOutcome.Error(e.message)
            }
        }
    }
}

/** Copies the consent terms a plan shows (grants, limits and the plain-language summary) into the request. */
private fun ConnectionRequest.withTerms(plan: AppConnectPlan) = copy(
    grantChoices = plan.choices(),
    capabilities = plan.capabilities(),
    fundingLimit = plan.fundingLimit(),
    expiresAt = plan.connectionExpiresAt(),
    budgetPreset = plan.budgetPreset,
    lifetimePreset = plan.lifetimePreset
)
