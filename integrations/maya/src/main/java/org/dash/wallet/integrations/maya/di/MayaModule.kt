package org.dash.wallet.integrations.maya.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.integrations.maya.MayaEndpoint
import org.dash.wallet.integrations.maya.api.ExchangeRateApi
import org.dash.wallet.integrations.maya.api.FiatExchangeRateAggregatedProvider
import org.dash.wallet.integrations.maya.api.FiatExchangeRateProvider
import org.dash.wallet.integrations.maya.api.MayaApi
import org.dash.wallet.integrations.maya.api.MayaApiAggregator
import org.dash.wallet.integrations.maya.api.RemoteDataSource
import org.dash.wallet.integrations.maya.utils.MayaConstants
import javax.inject.Singleton
import kotlin.time.ExperimentalTime

@Module
@InstallIn(SingletonComponent::class)
@ExperimentalCoroutinesApi
@ExperimentalTime
@FlowPreview
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
            remoteDataSource: RemoteDataSource,
            walletDataProvider: WalletDataProvider
        ): ExchangeRateApi {
            val baseUrl = MayaConstants.EXCHANGERATE_BASE_URL
            return remoteDataSource.buildApi(ExchangeRateApi::class.java, baseUrl)
        }
    }

    @Binds
    @Singleton
    abstract fun bindMayaApi(mayaApi: MayaApiAggregator): MayaApi

    @Binds
    @Singleton
    abstract fun bindFiatExchangeRateApi(fiatApi: FiatExchangeRateAggregatedProvider): FiatExchangeRateProvider
}