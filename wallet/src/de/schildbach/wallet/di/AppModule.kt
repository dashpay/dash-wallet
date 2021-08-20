package de.schildbach.wallet.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.dash.wallet.common.services.AnalyticsService
import org.dash.wallet.common.services.FirebaseAnalyticsServiceImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    abstract fun bindAnalyticsService(
        analyticsService: FirebaseAnalyticsServiceImpl
    ): AnalyticsService
}