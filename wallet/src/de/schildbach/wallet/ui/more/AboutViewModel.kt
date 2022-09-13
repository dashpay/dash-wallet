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

import androidx.core.os.bundleOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val analytics: AnalyticsService,
    private val exploreRepository: ExploreRepository
): ViewModel() {

    private val _exploreRemoteTimestamp = MutableLiveData<Long>()
    val exploreRemoteTimestamp: LiveData<Long>
        get() = _exploreRemoteTimestamp

    val exploreLastSync: Long
        get() {
            val lastSync = exploreRepository.lastSyncTimestamp
            return if (lastSync > 0) {
                lastSync
            } else {
                // If no sync run yet, show the timestamp of the preloaded db
                exploreRepository.localTimestamp
            }
        }

    private val _firebaseInstallationId = MutableLiveData<String>()
    val firebaseInstallationId: LiveData<String>
        get() = _firebaseInstallationId

    private val _firebaseCloudMessagingToken = MutableLiveData<String>()
    val firebaseCloudMessagingToken: LiveData<String>
        get() = _firebaseCloudMessagingToken

    init {
        loadFirebaseIds()
        viewModelScope.launch {
            _exploreRemoteTimestamp.value = exploreRepository.getRemoteTimestamp()
        }
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, bundleOf())
    }

    private fun loadFirebaseIds() {
        FirebaseInstallations.getInstance().id.addOnCompleteListener { task ->
            _firebaseInstallationId.value = if (task.isSuccessful) task.result else ""
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            _firebaseCloudMessagingToken.value = if (task.isSuccessful) task.result else ""
        }
    }
}