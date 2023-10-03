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
package org.dash.wallet.integrations.coinbase.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.repository.remote.CustomCacheInterceptor
import org.dash.wallet.integrations.coinbase.repository.remote.HeadersInterceptor
import org.dash.wallet.integrations.coinbase.repository.remote.TokenAuthenticator
import org.dash.wallet.integrations.coinbase.service.CoinBaseTokenRefreshApi
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class RemoteDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: CoinbaseConfig
) {

    fun <Api> buildApi(api: Class<Api>): Api {
        val authenticator = TokenAuthenticator(buildTokenApi(), config)
        return Retrofit.Builder()
            .baseUrl(CoinbaseConstants.BASE_URL)
            .client(getOkHttpClient(authenticator))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(api)
    }

    private fun buildTokenApi(): CoinBaseTokenRefreshApi {
        return Retrofit.Builder()
            .baseUrl(CoinbaseConstants.BASE_URL)
            .client(getOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CoinBaseTokenRefreshApi::class.java)
    }

    private fun getOkHttpClient(authenticator: Authenticator? = null): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(HeadersInterceptor(config))
            .addInterceptor(CustomCacheInterceptor(context, config))
            .connectTimeout(20.seconds.toJavaDuration())
            .callTimeout(20.seconds.toJavaDuration())
            .readTimeout(20.seconds.toJavaDuration())
            .cache(Cache(
                directory = CoinbaseConstants.getCacheDir(context),
                maxSize = 10L * 1024L * 1024L // 10 MB
            ))
            .also { client ->
                authenticator?.let { client.authenticator(it) }
                if (BuildConfig.DEBUG) {
                    val logging = HttpLoggingInterceptor()
                    logging.level = HttpLoggingInterceptor.Level.BODY
                    client.addInterceptor(logging)
                }
            }.build()
    }
}
