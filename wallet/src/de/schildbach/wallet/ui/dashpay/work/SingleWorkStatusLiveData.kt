package de.schildbach.wallet.ui.dashpay.work

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.schildbach.wallet.livedata.Resource

abstract class SingleWorkStatusLiveData<T>(val application: Application) : LiveData<Resource<T>>(), Observer<List<WorkInfo>> {

    override fun onActive() {
        super.onActive()
        workInfoList.observeForever(this)
    }

    override fun onInactive() {
        super.onInactive()
        workInfoList.removeObserver(this)
    }

    protected val workManager = WorkManager.getInstance(application)

    abstract val workInfoList: LiveData<List<WorkInfo>>

    abstract fun successResult(workInfo: WorkInfo): T

    override fun onChanged(it: List<WorkInfo>) {

        if (it.isNullOrEmpty()) {
            return
        }
        if (it.size > 1) {
            throw RuntimeException("there should never be more than one unique work")
        }

        val workInfo = it[0]
        when (workInfo.state) {
            WorkInfo.State.SUCCEEDED -> {
                value = Resource.success(successResult(workInfo))
            }
            WorkInfo.State.FAILED -> {
                val errorMessage = BaseWorker.extractError(workInfo.outputData)
                value = if (errorMessage != null) {
                    Resource.error(errorMessage)
                } else {
                    Resource.error(Exception())
                }
            }
            WorkInfo.State.CANCELLED -> {
                value = Resource.canceled(null)
            }
            WorkInfo.State.RUNNING -> {
                val progress = BaseWorker.extractProgress(workInfo.progress)
                if (progress >= 0) {
                    value = Resource.loading(null, progress)
                }
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                // ignore
            }
        }
    }
}