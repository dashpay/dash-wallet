package de.schildbach.wallet

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.dash.wallet.common.data.Resource
import org.dash.wallet.features.exploredash.repository.DataSyncStatus
import org.slf4j.LoggerFactory
import java.lang.Exception
import javax.inject.Inject

class ExploreDataSyncProgress @Inject constructor(): DataSyncStatus {
    companion object {
        val log = LoggerFactory.getLogger(ExploreDataSyncProgress::class.java)
    }

    private val _syncProgress = MutableLiveData<Resource<Double>>()

    override fun getSyncProgress(): LiveData<Resource<Double>> {
        return _syncProgress
    }

    override fun setSyncError(exception: Exception) {
        log.info("progress failure", exception)
        _syncProgress.postValue(Resource.error(exception))
    }

    override fun setSyncProgress(progress: Double) {
        log.info("progress {}", progress)
        _syncProgress.postValue(if (progress < 100.0) {
            Resource.loading(progress)
        } else {
            Resource.success(100.0)
        })
    }
}