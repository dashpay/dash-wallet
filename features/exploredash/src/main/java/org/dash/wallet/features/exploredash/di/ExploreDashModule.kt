/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.features.exploredash.di

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.repository.FirebaseExploreDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.features.exploredash.services.UserLocationState


@Module
@InstallIn(SingletonComponent::class)
abstract class ExploreDashModule {
    @Binds
    abstract fun bindExploreRepository(
        exploreRepository: FirebaseExploreDatabase
    ): ExploreRepository
}

@Module
@InstallIn(SingletonComponent::class)
object LocationProvider {
    @Provides
    fun provideFusedLocationProviderClient(@ApplicationContext context: Context): FusedLocationProviderClient {
        return LocationServices.getFusedLocationProviderClient(context)
    }

    @ExperimentalCoroutinesApi
    @Provides
    fun provideUserLocation(@ApplicationContext context: Context, locationProviderClient: FusedLocationProviderClient):
            UserLocationState = UserLocationState(context, locationProviderClient)
}