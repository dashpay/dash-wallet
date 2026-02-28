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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.dash.wallet.features.exploredash.ExploreDatabase
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.explore.AtmDao
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExploreDatabaseModule {
    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context, config: ExploreConfig): ExploreDatabase {
        return runBlocking { ExploreDatabase.getAppDatabase(context, config) }
    }

    @Provides
    fun provideMerchantDao(database: ExploreDatabase): MerchantDao {
        return database.merchantDao()
    }

    @Provides
    fun provideAtmDao(database: ExploreDatabase): AtmDao {
        return database.atmDao()
    }

    @Provides
    fun provideGiftCardDao(database: ExploreDatabase): GiftCardProviderDao {
        return database.giftCardProviderDao()
    }
}
