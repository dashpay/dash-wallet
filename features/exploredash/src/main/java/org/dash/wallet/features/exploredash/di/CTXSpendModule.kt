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

package org.dash.wallet.features.exploredash.di
//
//import dagger.Binds
//import dagger.Module
//import dagger.Provides
//import dagger.hilt.InstallIn
//import dagger.hilt.components.SingletonComponent
//import org.dash.wallet.common.Configuration
//import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
//import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendDataSource
//import org.dash.wallet.features.exploredash.repository.*
//import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
//
//@Module
//@InstallIn(SingletonComponent::class)
//abstract class CTXSpendModule {
//    companion object {
//        @Provides
//        fun provideRemoteDataSource(
//            userPreferences: Configuration,
//            config: CTXSpendConfig
//        ): CTXSpendDataSource {
//            return CTXSpendDataSource(userPreferences, config)
//        }
//
//        @Provides
//        fun provideApi(ctxSpendDataSource: CTXSpendDataSource): CTXSpendApi {
//            return ctxSpendDataSource.buildApi(CTXSpendApi::class.java)
//        }
//    }
//
//    @Binds
//    abstract fun provideCTXSpendRepository(ctxSpendRepository: CTXSpendRepository): CTXSpendRepositoryInt
//}
