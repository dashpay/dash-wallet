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
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.repository.FirebaseExploreDatabase
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