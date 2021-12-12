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
