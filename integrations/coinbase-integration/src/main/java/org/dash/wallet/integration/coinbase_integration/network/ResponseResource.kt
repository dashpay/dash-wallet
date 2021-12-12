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
package org.dash.wallet.integration.coinbase_integration.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.HttpException

sealed class ResponseResource<out T> {
    data class Success<out T>(val value: T) : ResponseResource<T>()
    data class Failure(
        val isNetworkError: Boolean,
        val errorCode: Int?,
        val errorBody: ResponseBody?
    ) : ResponseResource<Nothing>()
    object Loading : ResponseResource<Nothing>()
}

suspend fun <T> safeApiCall(
    apiCall: suspend () -> T
): ResponseResource<T> {
    return withContext(Dispatchers.IO) {
        try {
            ResponseResource.Loading
            ResponseResource.Success(apiCall.invoke())
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
            when (throwable) {
                is HttpException -> {
                    ResponseResource.Failure(
                        false,
                        throwable.code(),
                        throwable.response()?.errorBody()
                    )
                }
                else -> {
                    ResponseResource.Failure(true, null, null)
                }
            }
        }
    }
}
