/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.integrations.maya.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.integrations.maya.api.CurrencyBeaconApi
import org.dash.wallet.integrations.maya.api.DispatchingSwapProvider
import org.dash.wallet.integrations.maya.api.ExchangeRateApi
import org.dash.wallet.integrations.maya.api.FiatExchangeRateAggregatedProvider
import org.dash.wallet.integrations.maya.api.FiatExchangeRateProvider
import org.dash.wallet.integrations.maya.api.FreeCurrencyApi
import org.dash.wallet.integrations.maya.api.MayaApi
import org.dash.wallet.integrations.maya.api.MayaApiAggregator
import org.dash.wallet.integrations.maya.api.MayaBlockchainApi
import org.dash.wallet.integrations.maya.api.MayaBlockchainApiImpl
import org.dash.wallet.integrations.maya.api.MayaEndpoint
import org.dash.wallet.integrations.maya.api.RemoteDataSource
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.swapkit.SwapKitAuthInterceptor
import org.dash.wallet.integrations.maya.swapkit.SwapKitConstants
import org.dash.wallet.integrations.maya.swapkit.SwapKitEndpoint
import org.dash.wallet.integrations.maya.utils.MayaConstants
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MayaModule {
    companion object {
        @Provides
        fun provideMayaEndpoint(
            remoteDataSource: RemoteDataSource,
            walletDataProvider: WalletDataProvider
        ): MayaEndpoint {
            val baseUrl = MayaConstants.getBaseUrl(walletDataProvider.networkParameters)
            return remoteDataSource.buildApi(MayaEndpoint::class.java, baseUrl)
        }

        @Provides
        fun provideExchangeRateEndpoint(
            remoteDataSource: RemoteDataSource
        ): ExchangeRateApi {
            val baseUrl = MayaConstants.EXCHANGERATE_BASE_URL
            return remoteDataSource.buildApi(ExchangeRateApi::class.java, baseUrl)
        }

        @Provides
        fun provideCurrencyBeaconEndpoint(
            remoteDataSource: RemoteDataSource
        ): CurrencyBeaconApi {
            val baseUrl = MayaConstants.CURRENCYBEACON_BASE_URL
            return remoteDataSource.buildApi(CurrencyBeaconApi::class.java, baseUrl)
        }

        @Provides
        fun provideFreeCurrencyApiEndpoint(
            remoteDataSource: RemoteDataSource
        ): FreeCurrencyApi {
            val baseUrl = MayaConstants.FREE_CURRENCY_API_BASE_URL
            return remoteDataSource.buildApi(FreeCurrencyApi::class.java, baseUrl)
        }

        @Provides
        @Singleton
        fun provideSwapKitEndpoint(): SwapKitEndpoint {
            val client = OkHttpClient.Builder()
                .addInterceptor(SwapKitAuthInterceptor(SwapKitConstants.API_KEY))
                .also { builder ->
                    if (BuildConfig.DEBUG) {
                        val logging = HttpLoggingInterceptor()
                        logging.level = HttpLoggingInterceptor.Level.BODY
                        builder.addInterceptor(logging)
                    }
                }
                .build()
            return Retrofit.Builder()
                .baseUrl(SwapKitConstants.BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SwapKitEndpoint::class.java)
        }
    }

    @Binds
    @Singleton
    abstract fun bindMayaApi(mayaApi: MayaApiAggregator): MayaApi

    @Binds
    @Singleton
    abstract fun bindMayaBlockchainApi(mayaApi: MayaBlockchainApiImpl): MayaBlockchainApi

    @Binds
    @Singleton
    abstract fun bindFiatExchangeRateApi(fiatApi: FiatExchangeRateAggregatedProvider): FiatExchangeRateProvider

    /**
     * Single dispatch point for the cross-chain swap surface. The same singleton
     * instance is also injectable as [DispatchingSwapProvider] for callers that
     * need to switch the active backend at runtime (e.g.
     * `BuyAndSellViewModel.setSwapBackend`).
     */
    @Binds
    @Singleton
    abstract fun bindSwapProvider(impl: DispatchingSwapProvider): SwapProvider
}
