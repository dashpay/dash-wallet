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

package de.schildbach.wallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.AppExploreDatabase
import org.dash.wallet.features.exploredash.data.AtmDao
import org.dash.wallet.features.exploredash.data.MerchantDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExploreDatabaseModule {

    @Singleton
    @Provides
    fun provideDatabase(): AppExploreDatabase {
        return AppExploreDatabase.getAppDatabase()
    }

    @Provides
    fun provideMerchantDao(a2Database: AppExploreDatabase): MerchantDao {
        return a2Database.merchantDao()
    }

    @Provides
    fun provideAtmDao(a2Database: AppExploreDatabase): AtmDao {
        return a2Database.atmDao()
    }
}