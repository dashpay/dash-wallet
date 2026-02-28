package de.schildbach.wallet.ui.dashpay.work

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.work.WorkInfo

class UpdateProfileStatusLiveData(application: Application) : SingleWorkStatusLiveData<Nothing?>(application) {

    override val workInfoList: LiveData<List<WorkInfo>>
        get() = workManager.getWorkInfosForUniqueWorkLiveData(UpdateProfileOperation.WORK_NAME)


    override fun successResult(workInfo: WorkInfo): Nothing? {
        return null
    }
}