/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.schildbach.wallet.ui.dashpay.work

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.work.WorkInfo
import org.bitcoinj.core.Sha256Hash

class SendInviteStatusLiveData(application: Application, val inviteId: String) : SingleWorkStatusLiveData<Pair<String, Sha256Hash>>(application) {

    override val workInfoList: LiveData<List<WorkInfo>>
        get() = workManager.getWorkInfosForUniqueWorkLiveData(SendInviteOperation.uniqueWorkName(inviteId))


    override fun successResult(workInfo: WorkInfo): Pair<String, Sha256Hash> {
        val userId = SendInviteWorker.extractUserId(workInfo.outputData)!!
        val txid = SendInviteWorker.extractTxId(workInfo.outputData)!!
        return Pair(userId, Sha256Hash.wrap(txid))
    }
}