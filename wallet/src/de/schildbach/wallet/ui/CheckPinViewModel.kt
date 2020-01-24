/*
 * Copyright 2019 Dash Core Group
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

package de.schildbach.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.CheckPinLiveData
import org.slf4j.LoggerFactory

open class CheckPinViewModel(application: Application) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(CheckPinViewModel::class.java)

    protected val walletApplication = application as WalletApplication

    val pin = StringBuilder()

    internal val checkPinLiveData = CheckPinLiveData()

    open fun checkPin(password: CharSequence) {
        if (walletApplication.wallet.isEncrypted) {
            checkPinLiveData.checkPin(password.toString())
        } else {
            log.warn("Trying to decrypt unencrypted wallet")
        }
    }
}
