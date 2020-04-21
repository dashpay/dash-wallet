/*
 * Copyright 2020 Dash Core Group
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

package de.schildbach.wallet.livedata

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.dashpay.GetUsernameDocumentTask
import org.dashevo.dpp.document.Document

class GetUsernameLiveData(application: Application) : MutableLiveData<Resource<Document>>() {

    val backgroundHandler: Handler

    init {
        val backgroundThread = HandlerThread("getUsernameBackgroundThread", Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private var getUsernameDocumentTask: GetUsernameDocumentTask? = null
    private var walletApplication = application as WalletApplication

    fun getUsername(username: String) {
        if (getUsernameDocumentTask != null) {
            backgroundHandler.removeCallbacks(null) // cancel previous call
            getUsernameDocumentTask = null
        }
        if (getUsernameDocumentTask == null) {
            getUsernameDocumentTask = object : GetUsernameDocumentTask(backgroundHandler) {

                override fun onError(t: Throwable) {
                    value = Resource.error(t.localizedMessage, null)
                    getUsernameDocumentTask = null
                }

                override fun onSuccess(document: Document?) {
                    value = Resource.success(document)
                    getUsernameDocumentTask = null
                }
            }
            value = Resource.loading(null)
            getUsernameDocumentTask!!.getUsername(walletApplication.platform, username)
        }
    }
}