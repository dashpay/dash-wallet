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
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.WalletApplication
import org.dash.wallet.common.WalletDataProvider
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet.service.WalletTransactionMetadataProvider
import de.schildbach.wallet.service.BlockchainStateDataProvider
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataProviderModule {
    companion object {
        @Singleton
        @Provides
        fun provideWalletData(
            @ApplicationContext context: Context
        ): WalletDataProvider = context as WalletApplication
    }

    @Binds
    abstract fun bindBlockchainStateProvider(
        blockchainStateService: BlockchainStateDataProvider
    ): BlockchainStateProvider

    @Binds
    abstract fun bindTransactionMetadata(
        transactionMetadataService: WalletTransactionMetadataProvider
    ): TransactionMetadataProvider

    @Singleton
    @Binds
    abstract fun bindExchangeRateRepository(
        exchangeRatesRepository: ExchangeRatesRepository
    ): ExchangeRatesProvider
}
