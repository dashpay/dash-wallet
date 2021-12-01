package org.dash.wallet.integration.coinbase_integration.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.network.RemoteDataSource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseAuthRepository
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseAuthApi
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseServicesApi
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoinBaseModule {
    @Singleton
    @Provides
    fun provideRemoteDataSource(
        @ApplicationContext context: Context,
        userPreferences: Configuration
    ): RemoteDataSource {
        return RemoteDataSource(context, userPreferences)
    }

    @Singleton
    @Provides
    fun provideAuthApi(
        remoteDataSource: RemoteDataSource,
    ): CoinBaseAuthApi {
        return remoteDataSource.buildApi(CoinBaseAuthApi::class.java)
    }

    @Singleton
    @Provides
    fun provideUserApi(
        remoteDataSource: RemoteDataSource,
        @ApplicationContext context: Context
    ): CoinBaseServicesApi {
        return remoteDataSource.buildApi(CoinBaseServicesApi::class.java)
    }

//    @Provides
//    fun provideCoinBaseAuthRepository(
//        authApi: CoinBaseAuthApi,
//        userPreferences: Configuration
//    ): CoinBaseAuthRepository {
//        return CoinBaseAuthRepository(authApi, userPreferences)
//    }
}
