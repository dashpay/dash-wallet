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
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.repository.remote.HeadersInterceptor
import org.dash.wallet.integration.coinbase_integration.repository.remote.TokenAuthenticator
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseTokenRefreshApi
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject

class RemoteDataSource @Inject constructor(
    private val userPreferences: Configuration,
    private val context: Context
) {

    companion object {
        private const val BASE_URL = "https://api.coinbase.com/"
    }

    fun <Api> buildApi(
        api: Class<Api>,
    ): Api {
        val authenticator = TokenAuthenticator(buildTokenApi(), userPreferences, context)
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(getRetrofitClient(authenticator))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(api)
    }

    private fun buildTokenApi(): CoinBaseTokenRefreshApi {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(getRetrofitClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CoinBaseTokenRefreshApi::class.java)
    }

    private fun getRetrofitClient(authenticator: Authenticator? = null): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(HeadersInterceptor(userPreferences))
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
