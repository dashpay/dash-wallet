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

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.database.AppDatabase
import de.schildbach.wallet.database.AppDatabaseMigrations
import de.schildbach.wallet.database.dao.*
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "dash-wallet-database")
            .addMigrations(AppDatabaseMigrations.migration11To12, AppDatabaseMigrations.migration12to13)
            // destructive migrations are used from versions 1 to 11
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideBlockchainStateDao(appDatabase: AppDatabase): BlockchainStateDao {
        return appDatabase.blockchainStateDao()
    }

    @Provides
    fun provideExchangeRatesDao(appDatabase: AppDatabase): ExchangeRatesDao {
        return appDatabase.exchangeRatesDao()
    }

    @Provides
    fun provideTransactionMetadata(appDatabase: AppDatabase): TransactionMetadataDao {
        return appDatabase.transactionMetadataDao()
    }

    @Provides
    fun provideAddressMetadata(appDatabase: AppDatabase): AddressMetadataDao {
        return appDatabase.addressMetadataDao()
    }

    @Provides
    fun provideIconBitmaps(appDatabase: AppDatabase): IconBitmapDao {
        return appDatabase.iconBitmapDao()
    }

    @Provides
    fun provideGiftCardDao(appDatabase: AppDatabase): GiftCardDao {
        return appDatabase.giftCardDao()
    }
}
