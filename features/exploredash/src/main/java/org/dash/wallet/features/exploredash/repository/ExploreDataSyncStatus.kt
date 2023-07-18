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

package org.dash.wallet.features.exploredash.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.dash.wallet.common.data.Resource
import org.slf4j.LoggerFactory
import java.lang.Exception
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExploreDataSyncStatus @Inject constructor() : DataSyncStatusService {
    companion object {
        private val log = LoggerFactory.getLogger(ExploreDataSyncStatus::class.java)
    }

    private val _syncProgressFlow = MutableStateFlow(Resource.loading(0.00))
    private val _hasObservedLastError = MutableStateFlow(false)

    override fun getSyncProgressFlow(): Flow<Resource<Double>> = _syncProgressFlow

    suspend fun setSyncError(exception: Exception) {
        log.info("sync explore data failure", exception)
        _hasObservedLastError.emit(false)
        _syncProgressFlow.emit(Resource.error(exception))
    }

    override suspend fun setObservedLastError() {
        _hasObservedLastError.emit(true)
    }

    override fun hasObservedLastError(): Flow<Boolean> = _hasObservedLastError

    suspend fun setSyncProgress(progress: Double) {
        log.info("sync explore data progress: {}", progress)
        _syncProgressFlow.emit(
            if (progress < 100.0) {
                Resource.loading(progress)
            } else {
                Resource.success(100.0)
            }
        )
    }
}
