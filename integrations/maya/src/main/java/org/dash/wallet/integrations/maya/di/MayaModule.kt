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
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.integrations.maya.api.ExchangeRateApi
import org.dash.wallet.integrations.maya.api.FiatExchangeRateAggregatedProvider
import org.dash.wallet.integrations.maya.api.FiatExchangeRateProvider
import org.dash.wallet.integrations.maya.api.MayaApi
import org.dash.wallet.integrations.maya.api.MayaApiAggregator
import org.dash.wallet.integrations.maya.api.MayaEndpoint
import org.dash.wallet.integrations.maya.api.MayaLegacyEndpoint
import org.dash.wallet.integrations.maya.api.RemoteDataSource
import org.dash.wallet.integrations.maya.utils.MayaConstants
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
        fun provideMayaLegacyEndpoint(
            remoteDataSource: RemoteDataSource,
            walletDataProvider: WalletDataProvider
        ): MayaLegacyEndpoint {
            val baseUrl = MayaConstants.getLegacyBaseUrl(walletDataProvider.networkParameters)
            return remoteDataSource.buildApi(MayaLegacyEndpoint::class.java, baseUrl)
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
