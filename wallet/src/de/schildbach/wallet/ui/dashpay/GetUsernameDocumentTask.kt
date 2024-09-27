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

package de.schildbach.wallet.ui.dashpay

import android.os.Handler
import android.os.Looper
import org.dashj.platform.sdk.platform.Platform
import org.dashj.platform.dpp.document.Document

abstract class GetUsernameDocumentTask(private val backgroundHandler: Handler) {

    private val callbackHandler: Handler = Handler(Looper.myLooper()!!)

    fun getUsername(platform: Platform, username: String) {
        backgroundHandler.post {
            try {
                var nameDocument = platform.names.get(username)
                if (nameDocument == null)
                    nameDocument = platform.names.get(username, "")
                callbackHandler.post { onSuccess(nameDocument) }
            } catch (e: Exception) {
                callbackHandler.post { onError(e) }
            }
        }
    }

    protected abstract fun onSuccess(nameDocument: Document?)
    protected abstract fun onError(t: Throwable)

}