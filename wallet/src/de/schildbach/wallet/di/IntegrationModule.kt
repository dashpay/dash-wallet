/*
 * Copyright 2023 Dash Core Group.
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

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.BuildConfig
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.integration.uphold.api.TopperClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IntegrationModule {
    @Singleton
    @Provides
    fun provideTopperClient() = TopperClient().apply {
        init(
            BuildConfig.TOPPER_KEY_ID,
            BuildConfig.TOPPER_WIDGET_ID,
            BuildConfig.TOPPER_PRIVATE_KEY,
            Constants.NETWORK_PARAMETERS.id != NetworkParameters.ID_MAINNET
        )
    }
}
