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
