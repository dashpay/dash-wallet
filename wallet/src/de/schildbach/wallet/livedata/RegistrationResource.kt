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

import de.schildbach.wallet.livedata.Status.*
import java.lang.Exception

data class RegistrationResource<out T>(val status: Status, val step: RegistrationStep, val data: T?, val exception: Exception?) {
    companion object {
        fun <T> success(step: RegistrationStep, data: T?): RegistrationResource<T> {
            return RegistrationResource(SUCCESS, step, data, null)
        }

        fun <T> error(step: RegistrationStep, exception: Exception?, data: T?): RegistrationResource<T> {
            return RegistrationResource(ERROR, step, data, exception)
        }

        fun <T> loading(step: RegistrationStep, data: T?): RegistrationResource<T> {
            return RegistrationResource(LOADING, step, data, null)
        }
    }
}