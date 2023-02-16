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
package org.dash.wallet.integration.coinbase_integration.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.CoinbaseConstants
import org.dash.wallet.integration.coinbase_integration.repository.remote.CustomCacheInterceptor
import org.dash.wallet.integration.coinbase_integration.repository.remote.HeadersInterceptor
import org.dash.wallet.integration.coinbase_integration.repository.remote.TokenAuthenticator
import org.dash.wallet.integration.coinbase_integration.service.CloseCoinbasePortalBroadcaster
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseTokenRefreshApi
import org.dash.wallet.integration.coinbase_integration.utils.CoinbaseConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class RemoteDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: Configuration,
    private val config: CoinbaseConfig,
    private val broadcaster: CloseCoinbasePortalBroadcaster
) {

    fun <Api> buildApi(api: Class<Api>): Api {
        val authenticator = TokenAuthenticator(buildTokenApi(), userPreferences, config, broadcaster)
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
            .addInterceptor(HeadersInterceptor(userPreferences))
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
