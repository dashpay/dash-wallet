/*
 * Copyright 2024 Dash Core Group.
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

package de.schildbach.wallet.ui.more.tools
import com.google.gson.GsonBuilder
import de.schildbach.wallet_test.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.dash.wallet.common.util.Constants
import org.slf4j.LoggerFactory
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject

class RemoteDataSource {
    fun <Api> buildApi(api: Class<Api>, baseUrl: String, client: OkHttpClient): Api {
        return Retrofit.Builder()
            .client(client)
            .baseUrl(baseUrl)
            .addConverterFactory(
                GsonConverterFactory.create(
                    GsonBuilder()
                        .setLenient()
                        .create()
                )
            )
            .build()
            .create(api)
    }
}

interface ZenLedgerApi {
    suspend fun getToken()
    suspend fun getSignupUrl(portfolioRequest: ZenLedgerCreatePortfolioRequest): String?
}

class ZenLedgerClient @Inject constructor(): ZenLedgerApi {
    companion object {
        private const val BASE_URL = "https://api.zenledger.io/"
        private val log = LoggerFactory.getLogger(ZenLedgerClient::class.java)
    }

    private val keyId: String = BuildConfig.ZENLEDGER_CLIENT_ID
    private val privateKey: String = BuildConfig.ZENLEDGER_CLIENT_SECRET
    private val zenLedgerService: ZenLedgerService = RemoteDataSource().buildApi(
        ZenLedgerService::class.java,
        BASE_URL,
        Constants.HTTP_CLIENT.newBuilder()
            .addInterceptor(HttpLoggingInterceptor { log.info(it) }.setLevel(HttpLoggingInterceptor.Level.BODY))
            .build()
    )
    private var token: String? = null

    val hasValidCredentials: Boolean
        get() = keyId.isNotEmpty() && privateKey.isNotEmpty()

    override suspend fun getToken() {
        token = zenLedgerService.getAccessToken(keyId, privateKey)?.accessToken
    }

    override suspend fun getSignupUrl(portfolioRequest: ZenLedgerCreatePortfolioRequest): String? {
        return zenLedgerService.createPortfolio("Bearer ${token!!}", portfolioRequest)?.data?.signupUrl
    }
}
