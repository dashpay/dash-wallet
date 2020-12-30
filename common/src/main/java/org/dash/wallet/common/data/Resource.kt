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

package org.dash.wallet.common.data

data class Resource<out T>(val status: Status, val data: T?, val message: String?, val exception: Exception?) {

    companion object {
        fun <T> success(data: T?): Resource<T> {
            return Resource(Status.SUCCESS, data, null, null)
        }

        fun <T> error(msg: String): Resource<T> {
            return Resource(Status.ERROR, null, msg, null)
        }

        fun <T> error(msg: String, data: T?): Resource<T> {
            return Resource(Status.ERROR, data, msg, null)
        }

        fun <T> error(exception: Exception): Resource<T> {
            return Resource(Status.ERROR, null, null, exception)
        }

        fun <T> error(exception: Exception, data: T?): Resource<T> {
            return Resource(Status.ERROR, data, null, exception)
        }

        fun <T> error(exception: Exception, msg: String): Resource<T> {
            return Resource(Status.ERROR, null, msg, exception)
        }

        fun <T> loading(data: T? = null): Resource<T> {
            return Resource(Status.LOADING, data, null, null)
        }

        fun <T> canceled(): Resource<T> {
            return Resource(Status.CANCELED, null, null, null)
        }

        fun <T> canceled(data: T?): Resource<T> {
            return Resource(Status.CANCELED, null, null, null)
        }
    }
}