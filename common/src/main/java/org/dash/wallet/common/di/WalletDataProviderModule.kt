package org.dash.wallet.common.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.dash.wallet.common.WalletDataProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WalletDataProviderModule {
    @Singleton
    @Provides
    fun provideWallet(application: Application): WalletDataProvider {
        return application as WalletDataProvider
    }
}