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

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.dash.wallet.features.exploredash.data.explore.ExploreDataSource
import org.dash.wallet.features.exploredash.data.explore.MerchantAtmDataSource
import org.dash.wallet.features.exploredash.network.RemoteDataSource
import org.dash.wallet.features.exploredash.network.service.DashDirectAuthApi
import org.dash.wallet.features.exploredash.network.service.DashDirectServicesApi
import org.dash.wallet.features.exploredash.repository.*
import org.dash.wallet.features.exploredash.services.UserLocationState
import org.dash.wallet.features.exploredash.services.UserLocationStateInt
import org.dash.wallet.features.exploredash.utils.DashDirectConfig

@Module
@InstallIn(SingletonComponent::class)
abstract class ExploreDashModule {
    companion object {
        @Provides
        fun provideContext(@ApplicationContext context: Context): Context {
            return context
        }

        @Provides
        fun provideFusedLocationProviderClient(context: Context): FusedLocationProviderClient {
            return LocationServices.getFusedLocationProviderClient(context)
        }

        @Provides fun provideFirebaseAuth() = Firebase.auth

        @Provides fun provideFirebaseStorage() = Firebase.storage

        @Provides
        fun provideRemoteDataSource(config: DashDirectConfig): RemoteDataSource {
            return RemoteDataSource(config)
        }

        @Provides
        fun provideAuthApi(remoteDataSource: RemoteDataSource): DashDirectAuthApi {
            return remoteDataSource.buildApi(DashDirectAuthApi::class.java)
        }

        @Provides
        fun provideDashDirectApi(remoteDataSource: RemoteDataSource): DashDirectServicesApi {
            return remoteDataSource.buildApi(DashDirectServicesApi::class.java)
        }
    }

    @Binds
    abstract fun bindExploreRepository(exploreRepository: GCExploreDatabase): ExploreRepository

    @Binds
    abstract fun bindUserLocationState(userLocationState: UserLocationState): UserLocationStateInt

    @Binds
    abstract fun bindExploreDataSource(exploreDatabase: MerchantAtmDataSource): ExploreDataSource

    @Binds
    abstract fun bindDataSyncService(exploreDatabase: ExploreDataSyncStatus): DataSyncStatusService

    @Binds
    abstract fun provideDashDirectRepository(dashDirectRepository: DashDirectRepository): DashDirectRepositoryInt
}
