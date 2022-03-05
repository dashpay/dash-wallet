/*
 * Copyright 2021 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
}

suspend fun <T> safeApiCall(
    apiCall: suspend () -> T
): ResponseResource<T> {
    return withContext(Dispatchers.IO) {
        try {
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
