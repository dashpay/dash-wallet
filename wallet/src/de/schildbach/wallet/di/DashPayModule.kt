/*
 * Copyright 2022 Dash Core Group.
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
import de.schildbach.wallet.service.CoinJoinMixingService
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import de.schildbach.wallet.service.platform.PlatformDocumentBroadcastService
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.service.platform.PlatformServiceImplementation
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.platform.PlatformSynchronizationService
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.service.platform.TopUpRepositoryImpl
import de.schildbach.wallet.ui.dashpay.utils.GoogleDriveService
import de.schildbach.wallet.ui.dashpay.utils.ImgurService
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DashPayModule {
    companion object {
        @Provides
        fun provideGoogleDrive(
            @ApplicationContext context: Context
        ): GoogleDriveService = GoogleDriveService(context)

        @Provides
        fun provideImgurService(
            analyticsService: AnalyticsService,
            config: Configuration
        ): ImgurService = ImgurService(analyticsService, config)
    }

    @Singleton // only want one of PlatformSyncService created
    @Binds
    abstract fun bindsPlatformService(platformService: PlatformServiceImplementation): PlatformService

    @Singleton // only want one of PlatformSyncService created
    @Binds
    abstract fun bindsPlatformSyncService(platformSyncService: PlatformSynchronizationService): PlatformSyncService

    @Binds
    abstract fun bindsPlatformBroadcastService(platformBroadcastService: PlatformDocumentBroadcastService): PlatformBroadcastService

    @Binds
    @Singleton
    abstract fun bindsCoinJoinService(coinJoinMixingService: CoinJoinMixingService): CoinJoinService

    @Singleton // only want one of PlatformSyncService created
    @Binds
    abstract fun bindsTopupRepository(topUpRepositoryImpl: TopUpRepositoryImpl): TopUpRepository

    //@Binds
    //@Singleton
    //abstract fun bindsBlockchainIdentityConfig(blockchainIdentityConfig: BlockchainIdentityConfig): BlockchainIdentityConfig
}
