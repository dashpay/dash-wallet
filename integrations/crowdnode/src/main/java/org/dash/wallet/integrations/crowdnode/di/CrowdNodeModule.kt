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

package org.dash.wallet.integrations.crowdnode.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.integrations.crowdnode.api.*
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import javax.inject.Singleton
import kotlin.time.ExperimentalTime

@Module
@InstallIn(SingletonComponent::class)
@ExperimentalCoroutinesApi
@ExperimentalTime
@FlowPreview
abstract class CrowdNodeModule {
    companion object {
        @Provides
        fun provideEndpoint(
            remoteDataSource: RemoteDataSource,
            walletDataProvider: WalletDataProvider
        ): CrowdNodeEndpoint {
            val baseUrl = CrowdNodeConstants.getCrowdNodeBaseUrl(walletDataProvider.networkParameters)
            return remoteDataSource.buildApi(CrowdNodeEndpoint::class.java, baseUrl)
        }
    }

    @Binds
    @Singleton
    abstract fun bindCrowdNodeApi(crowdNodeApi: CrowdNodeApiAggregator): CrowdNodeApi
}
