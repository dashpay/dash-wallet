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
package org.dash.wallet.integration.coinbase_integration.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.dash.wallet.common.Configuration
import org.dash.wallet.integration.coinbase_integration.CoinbaseAddressMapper
import org.dash.wallet.integration.coinbase_integration.CommitBuyOrderMapper
import org.dash.wallet.integration.coinbase_integration.PlaceBuyOrderMapper
import org.dash.wallet.integration.coinbase_integration.SwapTradeMapper
import org.dash.wallet.integration.coinbase_integration.network.RemoteDataSource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseAuthApi
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseServicesApi
import org.dash.wallet.integration.coinbase_integration.utils.CoinbaseConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoinBaseModule {
    @Singleton
    @Provides
    fun provideRemoteDataSource(
        userPreferences: Configuration,
        config: CoinbaseConfig,
        @ApplicationContext context: Context
    ): RemoteDataSource {
        return RemoteDataSource(context, config)
    }

    @Singleton
    @Provides
    fun provideAuthApi(
        remoteDataSource: RemoteDataSource
    ): CoinBaseAuthApi {
        return remoteDataSource.buildApi(CoinBaseAuthApi::class.java)
    }

    @Singleton
    @Provides
    fun provideUserApi(
        remoteDataSource: RemoteDataSource
    ): CoinBaseServicesApi {
        return remoteDataSource.buildApi(CoinBaseServicesApi::class.java)
    }

    @Provides
    fun providePlaceBuyOrderMapper(): PlaceBuyOrderMapper = PlaceBuyOrderMapper()
    @Provides
    fun provideCommitBuyOrderMapper(): CommitBuyOrderMapper = CommitBuyOrderMapper()
    @Provides
    fun provideSwapTradeMapper(): SwapTradeMapper = SwapTradeMapper()

    @Provides
    fun provideCoinbaseAddressMapper(): CoinbaseAddressMapper = CoinbaseAddressMapper()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AbstractBindingProvision {
    @Binds
    @Singleton
    abstract fun bindCoinbaseRepository(coinBaseRepository: CoinBaseRepository): CoinBaseRepositoryInt
}
