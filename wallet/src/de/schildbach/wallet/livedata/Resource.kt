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

import de.schildbach.wallet.livedata.Status.*

data class Resource<out T>(val status: Status, val data: T?, val message: String?, val exception: Exception?) {
    companion object {
        @JvmStatic
        fun <T> success(data: T?): Resource<T> {
            return Resource(SUCCESS, data, null, null)
        }

        @JvmStatic
        fun <T> error(msg: String): Resource<T> {
            return Resource(ERROR, null, msg, null)
        }

        @JvmStatic
        fun <T> error(msg: String, data: T?): Resource<T> {
            return Resource(ERROR, data, msg, null)
        }

        @JvmStatic
        fun <T> error(exception: Exception): Resource<T> {
            return Resource(ERROR, null, null, exception)
        }

        @JvmStatic
        fun <T> error(exception: Exception, data: T?): Resource<T> {
            return Resource(ERROR, data, null, exception)
        }

        @JvmStatic
        fun <T> error(exception: Exception, msg: String): Resource<T> {
            return Resource(ERROR, null, msg, exception)
        }

        @JvmStatic
        fun <T> loading(data: T? = null, progress: Int? = null): Resource<T> {
            return Resource(LOADING, data, progress?.toString(), null)
        }

        @JvmStatic
        fun <T> canceled(): Resource<T> {
            return Resource(CANCELED, null, null, null)
        }

        @JvmStatic
        fun <T> canceled(data: T?): Resource<T> {
            return Resource(CANCELED, data, null, null)
        }
    }

    val progress: Int
        get() {
            if (status == LOADING) {
                return message?.toInt() ?: -1
            }
            throw IllegalStateException("Progress can only be used in LOADING state")
        }
}