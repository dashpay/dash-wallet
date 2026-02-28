package de.schildbach.wallet

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import de.schildbach.wallet.di.AnalyticsModule
import javax.inject.Singleton
import org.dash.wallet.common.services.analytics.AnalyticsService

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AnalyticsModule::class]
)
object TestAnalyticsModule {
    @Provides
    @Singleton
    fun provideAnalyticsService(): AnalyticsService = FakeAnalyticsService()
}
