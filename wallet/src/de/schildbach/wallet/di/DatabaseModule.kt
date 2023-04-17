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
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.data.*
import de.schildbach.wallet.rates.ExchangeRatesDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Singleton
    @Provides
    fun provideDatabase(): AppDatabase {
        return AppDatabase.getAppDatabase()
    }

    @Provides
    fun provideBlockchainStateDao(appDatabase: AppDatabase): BlockchainStateDao {
        return appDatabase.blockchainStateDao()
    }

    @Provides
    fun provideBlockchainIdentityDao(appDatabase: AppDatabase): BlockchainIdentityDataDao {
        return appDatabase.blockchainIdentityDataDao()
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
    fun provideDashPayProfileDao(appDatabase: AppDatabase): DashPayProfileDao {
        return appDatabase.dashPayProfileDao()
    }

    @Provides
    fun provideUserAlertDaoAsync(appDatabase: AppDatabase): UserAlertDao {
        return appDatabase.userAlertDao()
    }

    @Provides
    fun provideInvitationsDaoAsync(appDatabase: AppDatabase): InvitationsDao {
        return appDatabase.invitationsDao()
    }

    @Provides
    fun provideDashPayContactRequestDao(appDatabase: AppDatabase): DashPayContactRequestDao {
        return appDatabase.dashPayContactRequestDao()
    }
}
