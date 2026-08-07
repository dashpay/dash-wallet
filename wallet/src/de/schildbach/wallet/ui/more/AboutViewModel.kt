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

package de.schildbach.wallet.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.platform.sdk.CutoverCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.services.SystemActionsService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.repository.DataSyncStatusService
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.features.exploredash.utils.ExploreDatabasePrefs
import org.dash.wallet.common.util.isMainNetId
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * Raw ViewModel state for the About screen. Consolidated into a single immutable
 * snapshot so the UI never observes a mix of stale/fresh individual fields. The
 * fragment maps this (plus context-formatted strings) into the screen's [AboutUIState].
 */
data class AboutViewState(
    val exploreRemoteTimestamp: Long? = null,
    val exploreIsSyncing: Boolean = false,
    val firebaseInstallationId: String = "",
    val firebaseCloudMessagingToken: String = "",
    val isMainNet: Boolean = true,
    /**
     * Whether the Dash Kotlin SDK owns the L1 engine right now (the cutover is
     * committed AND the SDK L1 engine is enabled — see
     * [CutoverCoordinator.sdkOwnsL1Flow]). When true the About screen labels
     * the L1-engine row "Dash Kotlin SDK" and shows its version; when false
     * dashj still owns L1 and the row stays "DashJ". Observed reactively, so
     * the row flips live if the cutover commits while the screen is open.
     */
    val sdkOwnsL1: Boolean = false,
    val databasePrefs: ExploreDatabasePrefs = ExploreDatabasePrefs()
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val analytics: AnalyticsService,
    private val exploreRepository: ExploreRepository,
    exploreConfig: ExploreConfig,
    dataSyncStatus: DataSyncStatusService,
    val walletApplication: WalletApplication,
    private val systemActionsService: SystemActionsService,
    private val cutoverCoordinator: CutoverCoordinator
): ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(AboutViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(
        AboutViewState(isMainNet = Constants.NETWORK_PARAMETERS.id.isMainNetId())
    )
    val uiState: StateFlow<AboutViewState> = _uiState.asStateFlow()

    init {
        loadFirebaseIds()

        // Observe which L1 engine owns the wallet, REACTIVELY: sdkOwnsL1Flow()
        // emits true once the cutover is committed (CUT_OVER/SETTLED with the SDK
        // L1 engine enabled) and false while dashj still owns L1 (pre-cutover /
        // upgrade dual-run). The new SDK-primary auto-commit flips the cutover
        // mid-launch (~15-30s into an upgrade, immediately on a restore), so if
        // the About screen is open when that happens the L1-engine row flips
        // DashJ → Dash Kotlin SDK live. Failures fall back to "dashj owns L1".
        cutoverCoordinator.sdkOwnsL1Flow()
            .catch { e ->
                log.warn("failed to observe cutover ownership; showing dashj as the L1 owner", e)
                emit(false)
            }
            .onEach { sdkOwnsL1 -> _uiState.update { it.copy(sdkOwnsL1 = sdkOwnsL1) } }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val timestamp = exploreRepository.getRemoteTimestamp()
            _uiState.update { it.copy(exploreRemoteTimestamp = timestamp) }
        }

        dataSyncStatus.getSyncProgressFlow()
            .onEach { progress ->
                _uiState.update { it.copy(exploreIsSyncing = progress.status == Status.LOADING) }
            }
            .launchIn(viewModelScope)

        exploreConfig.exploreDatabasePrefs
            .distinctUntilChanged()
            .onEach { prefs -> _uiState.update { it.copy(databasePrefs = prefs) } }
            .launchIn(viewModelScope)
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    private fun loadFirebaseIds() {
        FirebaseInstallations.getInstance().id.addOnCompleteListener { task ->
            val id = if (task.isSuccessful) task.result ?: "" else ""
            _uiState.update { it.copy(firebaseInstallationId = id) }
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result ?: "" else ""
            _uiState.update { it.copy(firebaseCloudMessagingToken = token) }
        }
    }

    fun copyFCMToken() {
        val fcmToken = _uiState.value.firebaseCloudMessagingToken

        if (fcmToken.isNotEmpty()) {
            systemActionsService.copyText(fcmToken, "FCM token")
        }
    }

    fun copyFirebaseInstallationId() {
        val firebaseId = _uiState.value.firebaseInstallationId

        if (firebaseId.isNotEmpty()) {
            systemActionsService.copyText(firebaseId, "Firebase Installation Id")
        }
    }

    fun reviewApp() {
        systemActionsService.reviewApp()
    }
}
