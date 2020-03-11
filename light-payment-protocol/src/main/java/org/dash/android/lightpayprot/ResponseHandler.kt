package org.dash.android.lightpayprot

import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import retrofit2.HttpException

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
                    apiError != null -> Resource.error(apiError.toString(), null)
                    ex.message != null -> Resource.error(ex.message!!, null)
                    else -> Resource.error("error: ${ex.code()}", null)
                }
            }
            else -> Resource.error(ex.message ?: "Unknown error", null)
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