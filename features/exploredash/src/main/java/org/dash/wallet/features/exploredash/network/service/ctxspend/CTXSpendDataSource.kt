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
package org.dash.wallet.features.exploredash.network.service.ctxspend

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.dash.wallet.common.Configuration
import org.dash.wallet.features.exploredash.repository.remote.CTXSpendAuthInterceptor
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants
import org.slf4j.LoggerFactory
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class CTXSpendDataSource @Inject constructor(
    private val userPreferences: Configuration,
    private val config: CTXSpendConfig,
) {
    companion object {
        private val log = LoggerFactory.getLogger(CTXSpendDataSource::class.java)
    }

    fun <Api> buildApi(api: Class<Api>): Api {
        val authInterceptor = CTXSpendAuthInterceptor(buildTokenApi(), userPreferences, config)
        val client = getOkHttpClientBuilder().addInterceptor(authInterceptor).build()

        return Retrofit.Builder()
            .baseUrl(CTXSpendConstants.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(api)
    }

    private fun buildTokenApi(): CTXSpendTokenApi {
        return Retrofit.Builder()
            .baseUrl(CTXSpendConstants.BASE_URL)
            .client(getOkHttpClientBuilder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CTXSpendTokenApi::class.java)
    }

    private fun getOkHttpClientBuilder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .connectTimeout(20.seconds.toJavaDuration())
            .callTimeout(20.seconds.toJavaDuration())
            .readTimeout(20.seconds.toJavaDuration())
            .also { client ->
                val logging = HttpLoggingInterceptor { message -> log.info(message) }
                logging.level = HttpLoggingInterceptor.Level.BODY
                client.addInterceptor(logging)
            }
    }
}
