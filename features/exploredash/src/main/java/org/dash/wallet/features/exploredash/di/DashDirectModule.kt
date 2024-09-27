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

package org.dash.wallet.features.exploredash.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.Constants
import org.dash.wallet.features.exploredash.network.RemoteDataSource
import org.dash.wallet.features.exploredash.network.service.DashDirectAuthApi
import org.dash.wallet.features.exploredash.network.service.DashDirectServicesApi
import org.dash.wallet.features.exploredash.network.service.stubs.FakeDashDirectApi
import org.dash.wallet.features.exploredash.repository.*
import org.dash.wallet.features.exploredash.utils.DashDirectConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DashDirectModule {
    companion object {
        @Provides
        fun provideRemoteDataSource(config: DashDirectConfig): RemoteDataSource {
            return RemoteDataSource(config)
        }

        @Provides
        fun provideAuthApi(remoteDataSource: RemoteDataSource): DashDirectAuthApi {
            return remoteDataSource.buildApi(DashDirectAuthApi::class.java)
        }

        @Provides
        @Singleton
        fun provideDashDirectApi(
            remoteDataSource: RemoteDataSource,
            exchangeRatesProvider: ExchangeRatesProvider
        ): DashDirectServicesApi {
            val realApi = remoteDataSource.buildApi(DashDirectServicesApi::class.java)

            return if (Constants.BUILD_FLAVOR.lowercase() == "prod") {
                realApi
            } else {
                FakeDashDirectApi(realApi, exchangeRatesProvider)
            }
        }
    }

    @Binds
    abstract fun provideDashDirectRepository(dashDirectRepository: DashDirectRepository): DashDirectRepositoryInt
}
