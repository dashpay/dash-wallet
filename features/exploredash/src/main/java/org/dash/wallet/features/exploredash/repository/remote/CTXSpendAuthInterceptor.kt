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
package org.dash.wallet.features.exploredash.repository.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.safeApiCall
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendTokenApi
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class CTXSpendAuthInterceptor constructor(
    private val authApi: CTXSpendTokenApi,
    private val userPreferences: Configuration,
    private val config: CTXSpendConfig
) : Interceptor {
    var log: Logger = LoggerFactory.getLogger(CTXSpendAuthInterceptor::class.java)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()

        builder.header(CTXSpendConstants.CLIENT_ID_PARAM_NAME, CTXSpendConstants.CLIENT_ID)

        val accessToken = runBlocking { config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN) }
        if (accessToken?.isNotEmpty() == true) {
            builder.header("Authorization", "Bearer $accessToken")
        }

        var response = chain.proceed(builder.build())
        if (response.code == 401 && request.url.encodedPath != "/refresh-token") {
            val refreshToken = runBlocking { config.getSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN) }
            if (!refreshToken.isNullOrEmpty()) {
                runBlocking {
                    var tokenResponse = safeApiCall { authApi.refreshToken(refreshToken) }
                    when (tokenResponse) {
                        is ResponseResource.Success -> {
                            userPreferences.lastCTXSpendAccessToken = tokenResponse.value?.accessToken
                            userPreferences.lastCTXSpendRefreshToken = tokenResponse.value?.refreshToken

                            val retryRequest = request.newBuilder()
                                .header("Authorization", "Bearer ${tokenResponse.value?.accessToken}")
                                .build()

                            response.close()
                            response = chain.proceed(retryRequest)
                        }

                        else -> {
                            userPreferences.lastCTXSpendAccessToken = null
                            userPreferences.lastCTXSpendRefreshToken = null
                            config.clearAll()
                            null
                        }
                    }
                }
            }
        }

        return response
    }
}
