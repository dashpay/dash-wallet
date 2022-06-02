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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.ExploreDataSyncStatus
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.TransactionMetadataDao
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet.service.WalletTransactionMetadataService
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.TransactionMetadataService
import org.dash.wallet.features.exploredash.repository.DataSyncStatusService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataProviderModule {
    @Singleton
    @Provides
    fun provideWalletData(
        @ApplicationContext context: Context
    ): WalletDataProvider = context as WalletApplication

    @Singleton
    @Provides
    fun provideExchangeRateRepository(): ExchangeRatesProvider = ExchangeRatesRepository.instance

    @Singleton
    @Provides
    fun provideDataSyncStatus(): DataSyncStatusService = ExploreDataSyncStatus()

    @Singleton
    @Provides
    fun provideTransactionMetadata(transactionMetadataDao: TransactionMetadataDao,
                                   @ApplicationContext context: Context): TransactionMetadataService = WalletTransactionMetadataService(transactionMetadataDao, context as WalletApplication)
}
