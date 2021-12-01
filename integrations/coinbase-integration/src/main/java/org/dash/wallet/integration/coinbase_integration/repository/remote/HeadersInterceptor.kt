package org.dash.wallet.integration.coinbase_integration.repository.remote

import okhttp3.Interceptor
import okhttp3.Response
import org.dash.wallet.common.Configuration
import javax.inject.Inject

class HeadersInterceptor @Inject constructor(
    private val userPreferences: Configuration
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val requestBuilder = original.newBuilder()
        requestBuilder.header("Accept", "application/json")

        val accessToken = userPreferences.lastCoinbaseAccessToken
        if (accessToken?.isEmpty()?.not() == true) {
            requestBuilder.header("Authorization", "Bearer $accessToken")
        }

        requestBuilder.method(original.method(), original.body())
        val request = requestBuilder.build()

        return chain.proceed(request)
    }
}
