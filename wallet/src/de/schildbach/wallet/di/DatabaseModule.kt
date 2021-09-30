package de.schildbach.wallet.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.AppDatabase
import org.dash.wallet.features.exploredash.data.MerchantDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getAppDatabase()
    }

    @Provides
    fun provideMerchantDao(database: AppDatabase): MerchantDao {
        return database.merchantDao()
    }
}