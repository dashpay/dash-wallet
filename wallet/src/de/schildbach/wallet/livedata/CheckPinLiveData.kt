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

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.ui.CheckWalletPasswordTask
import de.schildbach.wallet.security.SecurityGuard
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory

class CheckPinLiveData(
    private val wallet: Wallet
) : MutableLiveData<Resource<String>>() {
    companion object {
        private val log = LoggerFactory.getLogger(CheckPinLiveData::class.java)
    }

    val backgroundHandler: Handler

    init {
        val backgroundThread = HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private var checkPinTask: CheckWalletPasswordTask? = null

    fun checkPin(pin: String) {
        try {
            val securityGuard = SecurityGuard.getInstance()
            if (securityGuard.isConfigured) {
                value = if (securityGuard.checkPin(pin))
                    Resource.success(pin)
                else
                    Resource.error("", pin)
            } else {
                setupSecurityGuard(pin)
            }
        } catch (e: Exception) {
            log.error("Failed to check PIN", e)
            value = Resource.error("Security system error", pin)
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
                    try {
                        val securityGuard = SecurityGuard.getInstance()
                        securityGuard.savePin(pin)
                        securityGuard.savePassword(pin)
                        value = Resource.success(pin)
                    } catch (e: Exception) {
                        log.error("Failed to save security credentials", e)
                        value = Resource.error("Failed to save credentials", pin)
                    }
                    checkPinTask = null
                }
            }
            value = Resource.loading(null)
            checkPinTask!!.checkPassword(wallet, pin)
        }
    }
}