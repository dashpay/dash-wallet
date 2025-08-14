/*
 * Copyright 2025 Dash Core Group.
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
package org.dash.wallet.features.exploredash.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.features.exploredash.network.interceptor.ErrorHandlingInterceptor
import org.dash.wallet.features.exploredash.network.interceptor.PiggyCardsHeadersInterceptor
import org.dash.wallet.features.exploredash.utils.PiggyCardsConfig
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants
import org.slf4j.LoggerFactory
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class PiggyCardsRemoteDataSource @Inject constructor(private val config: PiggyCardsConfig) {
    companion object {
        private val log = LoggerFactory.getLogger(PiggyCardsRemoteDataSource::class.java)
    }

    fun <Api> buildApi(api: Class<Api>): Api {
        return Retrofit.Builder()
            .baseUrl(PiggyCardsConstants.BASE_URL)
            .client(getOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(api)
    }

    private fun getOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            log.info(message)
        }
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY)
        loggingInterceptor.redactHeader("Authorization")

        return OkHttpClient.Builder()
            .connectTimeout(60.seconds.toJavaDuration())
            .readTimeout(60.seconds.toJavaDuration())
            .addInterceptor(PiggyCardsHeadersInterceptor(config))
            .addInterceptor(ErrorHandlingInterceptor(ServiceName.CTXSpend))
            .addInterceptor(loggingInterceptor)
            .build()
    }
}