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

import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.features.exploredash.network.authenticator.PiggyCardsAuthenticator
import org.dash.wallet.features.exploredash.network.interceptor.ErrorHandlingInterceptor
import org.dash.wallet.features.exploredash.network.interceptor.PiggyCardsHeadersInterceptor
import org.dash.wallet.features.exploredash.network.service.piggycards.PiggyCardsTokenApi
import org.dash.wallet.features.exploredash.utils.PiggyCardsConfig
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants
import org.slf4j.LoggerFactory
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class PiggyCardsRemoteDataSource @Inject constructor(
    private val config: PiggyCardsConfig,
    private val walletData: WalletDataProvider
) {
    companion object {
        private val log = LoggerFactory.getLogger(PiggyCardsRemoteDataSource::class.java)
    }

    fun <Api> buildApi(api: Class<Api>): Api {
        return Retrofit.Builder()
            .baseUrl(
                if (walletData.networkParameters.id == NetworkParameters.ID_MAINNET) {
                    PiggyCardsConstants.BASE_URL_PROD
                } else {
                    PiggyCardsConstants.BASE_URL_DEV
                }
            )
            .client(getOkHttpClient(PiggyCardsAuthenticator(buildTokenApi(), config)))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(api)
    }

    private fun buildTokenApi(): PiggyCardsTokenApi {
        return Retrofit.Builder()
            .baseUrl(
                if (walletData.networkParameters.id == NetworkParameters.ID_MAINNET) {
                    PiggyCardsConstants.BASE_URL_PROD
                } else {
                    PiggyCardsConstants.BASE_URL_DEV
                }
            )
            .client(getOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PiggyCardsTokenApi::class.java)
    }

    private fun getOkHttpClient(authenticator: Authenticator? = null): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            log.info(message)
        }
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY)
        loggingInterceptor.redactHeader("Authorization")

        return OkHttpClient.Builder()
            .connectTimeout(20.seconds.toJavaDuration())
            .readTimeout(20.seconds.toJavaDuration())
            .addInterceptor(PiggyCardsHeadersInterceptor(config))
            .addInterceptor(ErrorHandlingInterceptor(ServiceName.PiggyCards))
            .also { client ->
                authenticator?.let { client.authenticator(it) }
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }
}
