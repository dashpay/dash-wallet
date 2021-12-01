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
    private val context: Context,
    private val userPreferences: Configuration
) {

    companion object {
        private const val BASE_URL = "https://api.coinbase.com/"
    }

    fun <Api> buildApi(
        api: Class<Api>,
    ): Api {
        val authenticator = TokenAuthenticator(context, buildTokenApi(),userPreferences)
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
