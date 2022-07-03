package de.schildbach.wallet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.dash.wallet.common.data.Resource
import org.dash.wallet.features.exploredash.repository.DataSyncStatusService
import org.slf4j.LoggerFactory
import java.lang.Exception
import javax.inject.Inject

class ExploreDataSyncStatus @Inject constructor(): DataSyncStatusService {
    companion object {
        private val log = LoggerFactory.getLogger(ExploreDataSyncStatus::class.java)
    }

    private val _syncProgressFlow = MutableStateFlow(Resource.loading(0.00))
    private val _hasObservedLastError = MutableStateFlow(false)

    override fun getSyncProgressFlow(): Flow<Resource<Double>> = _syncProgressFlow

    override suspend fun setSyncError(exception: Exception) {
        log.info("sync explore data failure", exception)
        _hasObservedLastError.emit(false)
        _syncProgressFlow.emit(Resource.error(exception))
    }

    override suspend fun setObservedLastError() {
        _hasObservedLastError.emit(true)
    }

    override fun hasObservedLastError(): Flow<Boolean> = _hasObservedLastError

    override suspend fun setSyncProgress(progress: Double) {
        log.info("sync explore data progress: {}", progress)
        _syncProgressFlow.emit(if (progress < 100.0) {
            Resource.loading(progress)
        } else {
            Resource.success(100.0)
        })
    }
}