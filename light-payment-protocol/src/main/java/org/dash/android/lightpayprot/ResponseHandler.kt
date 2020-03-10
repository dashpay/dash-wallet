package org.dash.android.lightpayprot

import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import retrofit2.HttpException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

enum class ErrorCodes(val code: Int) {
    SocketTimeOut(-1)
}

open class ResponseHandler {

    companion object {
        private val TAG = ResponseHandler::class.java.simpleName
    }

    fun <T : Any> handleSuccess(data: T): Resource<T> {
        return Resource.success(data)
    }

    fun <T : Any> handleException(ex: Exception): Resource<T> {
        return when (ex) {
            is HttpException -> {
                val apiError = getApiError(ex)
                when {
                    apiError != null -> Resource.error(apiError.message, null)
                    ex.message != null -> Resource.error(ex.message!!, null)
                    else -> Resource.error(getErrorMessage(ex.code()), null)
                }
            }
            is SocketTimeoutException -> Resource.error(getErrorMessage(ErrorCodes.SocketTimeOut.code), null)
            else -> Resource.error(ex.message!!, null)
        }
    }

    private fun getErrorMessage(code: Int): String {
        return when (code) {
            ErrorCodes.SocketTimeOut.code -> "HTTP_CLIENT_TIMEOUT"
            HttpURLConnection.HTTP_CLIENT_TIMEOUT -> "HTTP_CLIENT_TIMEOUT"
            HttpURLConnection.HTTP_UNAUTHORIZED -> "HTTP_UNAUTHORIZED"
            HttpURLConnection.HTTP_NOT_FOUND -> "HTTP_NOT_FOUND"
            else -> "Unknown error"
        }
    }

    private fun getApiError(e: HttpException): ApiError? {
        try {
            val errorBody = e.response()?.errorBody()?.string()
            if (errorBody != null) {
                val moshi = Moshi.Builder().build()
                val jsonAdapter: JsonAdapter<ApiError> = moshi.adapter(ApiError::class.java)
                return jsonAdapter.fromJson(errorBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
        }
        return null
    }
}