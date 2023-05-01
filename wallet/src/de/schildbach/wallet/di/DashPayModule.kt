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

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import de.schildbach.wallet.service.platform.PlatformDocumentBroadcastService
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.platform.PlatformSynchronizationService

import de.schildbach.wallet.ui.dashpay.PlatformRepo
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DashPayModule {
    companion object {
        @Provides
        @Singleton
        fun providePlatformRepo(): PlatformRepo = PlatformRepo.getInstance()
    }

    @Singleton // only want one of PlatformSyncService created
    @Binds
    abstract fun bindsPlatformSyncService(platformSyncService: PlatformSynchronizationService): PlatformSyncService

    @Binds
    abstract fun bindsPlatformBroadcastService(platformBroadcastService: PlatformDocumentBroadcastService): PlatformBroadcastService
}