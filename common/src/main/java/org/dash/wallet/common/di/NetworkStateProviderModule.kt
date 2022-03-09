package org.dash.wallet.common.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.dash.wallet.common.livedata.ConnectionLiveData
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkStateProviderModule {
    @Provides
    @Singleton
    fun provideConnectivityManager(@ApplicationContext context: Context)
        = ConnectionLiveData(context)
}