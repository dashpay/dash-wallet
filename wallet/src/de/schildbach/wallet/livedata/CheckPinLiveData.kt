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

package de.schildbach.wallet.livedata

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.CheckWalletPasswordTask
import de.schildbach.wallet.ui.security.SecurityGuard

class CheckPinLiveData(application: Application) : MutableLiveData<Resource<String>>() {

    val backgroundHandler: Handler

    init {
        val backgroundThread = HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private var checkPinTask: CheckWalletPasswordTask? = null
    private var walletApplication = application as WalletApplication
    private val securityGuard = SecurityGuard()

    fun checkPin(pin: String) {
        if (securityGuard.isConfigured) {
            value = if (securityGuard.checkPin(pin))
                Resource.success(pin)
            else
                Resource.error("", pin)
        } else {
            setupSecurityGuard(pin)
        }
    }

    private fun setupSecurityGuard(pin: String) {
        if (checkPinTask == null) {
            checkPinTask = object : CheckWalletPasswordTask(backgroundHandler) {

                override fun onBadPassword() {
                    value = Resource.error("", pin)
                    checkPinTask = null
                }

                override fun onSuccess() {
                    securityGuard.savePin(pin)
                    securityGuard.savePassword(pin)
                    value = Resource.success(pin)
                    checkPinTask = null
                }
            }
            value = Resource.loading(null)
            checkPinTask!!.checkPassword(walletApplication.wallet, pin)
        }
    }
}