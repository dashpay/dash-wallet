package de.schildbach.wallet.ui.dashpay.work

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.work.WorkInfo

class SendContactRequestStatusLiveData(application: Application, val toUserId: String) : SingleWorkStatusLiveData<Pair<String, String>>(application) {

    override val workInfoList: LiveData<List<WorkInfo>>
        get() = workManager.getWorkInfosForUniqueWorkLiveData(SendContactRequestOperation.uniqueWorkName(toUserId))


    override fun successResult(workInfo: WorkInfo): Pair<String, String> {
        val userIdOut = SendContactRequestWorker.extractUserId(workInfo.outputData)!!
        val toUserIdOut = SendContactRequestWorker.extractToUserId(workInfo.outputData)!!
        return Pair(userIdOut, toUserIdOut)
    }
}