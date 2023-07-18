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
package org.dash.wallet.common.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

sealed class ResponseResource<out T> {
    data class Success<out T>(val value: T) : ResponseResource<T>()
    data class Failure(
        val throwable: Throwable,
        val isNetworkError: Boolean,
        val errorCode: Int?,
        val errorBody: String?
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
                        throwable,
                        false,
                        throwable.code(),
                        throwable.response()?.errorBody()?.string()
                    )
                }
                else -> {
                    ResponseResource.Failure(throwable, true, null, throwable.message)
                }
            }
        }
    }
}

fun <T> ResponseResource<T>.unwrap(): T {
    return when (this) {
        is ResponseResource.Success -> {
            this.value
        }
        is ResponseResource.Failure -> {
            throw this.throwable
        }
    }
}
