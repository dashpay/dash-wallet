package org.dash.wallet.common.di

import android.content.Context
import android.net.ConnectivityManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.livedata.NetworkState
import org.dash.wallet.common.livedata.NetworkStateInt

@Module
@InstallIn(SingletonComponent::class)
object ConnectivityProviderModule {
    @Provides
    fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
}

@Module
@InstallIn(SingletonComponent::class)
@ExperimentalCoroutinesApi
abstract class NetworkStateProviderModule {
    @Binds
    abstract fun bindNetworkState(networkState: NetworkState) : NetworkStateInt
}