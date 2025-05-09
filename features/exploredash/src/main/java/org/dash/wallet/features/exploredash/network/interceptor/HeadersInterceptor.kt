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
package org.dash.wallet.features.exploredash.network.interceptor

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants
import javax.inject.Inject

class HeadersInterceptor @Inject constructor(private val config: CTXSpendConfig) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val requestBuilder = original.newBuilder()
        requestBuilder.header("Accept", "application/json")
        requestBuilder.header(CTXSpendConstants.CLIENT_ID_PARAM_NAME, CTXSpendConstants.CLIENT_ID)

        val accessToken = runBlocking {
            config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN)
        }

        if (accessToken?.isNotEmpty() == true) {
            requestBuilder.header("Authorization", "Bearer $accessToken")
        }

        requestBuilder.method(original.method, original.body)
        val request = requestBuilder.build()

        return chain.proceed(request)
    }
}
