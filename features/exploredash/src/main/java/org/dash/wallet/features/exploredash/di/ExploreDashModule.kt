package org.dash.wallet.features.exploredash.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.dash.wallet.features.exploredash.repository.FirebaseMerchantTable
import org.dash.wallet.features.exploredash.repository.MerchantRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class ExploreDashModule {
    @Binds
    abstract fun bindMerchantRepository(
        analyticsService: FirebaseMerchantTable
    ): MerchantRepository
}