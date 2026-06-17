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
import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.services.SystemActionsService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.repository.DataSyncStatusService
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.features.exploredash.utils.ExploreDatabasePrefs
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val analytics: AnalyticsService,
    private val exploreRepository: ExploreRepository,
    exploreConfig: ExploreConfig,
    dataSyncStatus: DataSyncStatusService,
    val walletApplication: WalletApplication,
    private val systemActionsService: SystemActionsService
): ViewModel() {

    private val _exploreRemoteTimestamp = MutableStateFlow<Long?>(null)
    val exploreRemoteTimestamp: StateFlow<Long?>
        get() = _exploreRemoteTimestamp.asStateFlow()

    private val _exploreIsSyncing = MutableStateFlow(false)
    val exploreIsSyncing: StateFlow<Boolean>
        get() = _exploreIsSyncing.asStateFlow()

    private val _firebaseInstallationId = MutableStateFlow("")
    val firebaseInstallationId: StateFlow<String>
        get() = _firebaseInstallationId.asStateFlow()

    private val _firebaseCloudMessagingToken = MutableStateFlow("")
    val firebaseCloudMessagingToken: StateFlow<String>
        get() = _firebaseCloudMessagingToken.asStateFlow()

    var databasePrefs: ExploreDatabasePrefs = ExploreDatabasePrefs()
        private set

    init {
        loadFirebaseIds()

        viewModelScope.launch {
            _exploreRemoteTimestamp.value = exploreRepository.getRemoteTimestamp()
        }

        dataSyncStatus.getSyncProgressFlow()
            .onEach { _exploreIsSyncing.value = it.status == Status.LOADING }
            .launchIn(viewModelScope)

        exploreConfig.exploreDatabasePrefs
            .distinctUntilChanged()
            .onEach { databasePrefs = it }
            .launchIn(viewModelScope)
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    private fun loadFirebaseIds() {
        FirebaseInstallations.getInstance().id.addOnCompleteListener { task ->
            _firebaseInstallationId.value = if (task.isSuccessful) task.result ?: "" else ""
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            _firebaseCloudMessagingToken.value = if (task.isSuccessful) task.result ?: "" else ""
        }
    }

    fun copyFCMToken() {
        val fcmToken = _firebaseCloudMessagingToken.value

        if (fcmToken.isNotEmpty()) {
            systemActionsService.copyText(fcmToken, "FCM token")
        }
    }

    fun copyFirebaseInstallationId() {
        val firebaseId = _firebaseInstallationId.value

        if (firebaseId.isNotEmpty()) {
            systemActionsService.copyText(firebaseId, "Firebase Installation Id")
        }
    }

    fun reviewApp() {
        systemActionsService.reviewApp()
    }
}
