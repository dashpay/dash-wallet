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

import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.ui.security.SecurityGuard

class CheckPinLiveData : MutableLiveData<Resource<String>>() {

    private val securityGuard = SecurityGuard()

    fun checkPin(pin: String) {
        value = if (securityGuard.checkPin(pin))
            Resource.success(pin)
        else
            Resource.error("", pin)
    }
}