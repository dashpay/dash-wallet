/*
 * Copyright 2025 Dash Core Group.
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

package org.dash.wallet.features.exploredash.repository

import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.network.PiggyCardsRemoteDataSource
import org.dash.wallet.features.exploredash.network.RemoteDataSource
import org.dash.wallet.features.exploredash.network.authenticator.TokenAuthenticator
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendTokenApi
import org.dash.wallet.features.exploredash.network.service.piggycards.PiggyCardsApi
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.dash.wallet.features.exploredash.utils.PiggyCardsConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashSpendRepositoryFactory @Inject constructor(
    private val ctxSpendConfig: CTXSpendConfig,
    private val piggyCardsConfig: PiggyCardsConfig,
    private val walletDataProvider: WalletDataProvider // only to get the network
) {
    fun create(provider: GiftCardProviderType): DashSpendRepository {
        return when (provider) {
            GiftCardProviderType.CTX -> createCTXSpend()
            GiftCardProviderType.PiggyCards -> createPiggyCardsRepository()
        }
    }

    private fun createCTXSpend(): CTXSpendRepository {
        val remoteDataSource = RemoteDataSource(ctxSpendConfig)
        val api = remoteDataSource.buildApi(CTXSpendApi::class.java)
        val tokenApi = remoteDataSource.buildApi(CTXSpendTokenApi::class.java)
        val tokenAuthenticator = TokenAuthenticator(tokenApi, ctxSpendConfig)

        return CTXSpendRepository(api, ctxSpendConfig, tokenAuthenticator)
    }

    private fun createPiggyCardsRepository(): PiggyCardsRepository {
        val remoteDataSource = PiggyCardsRemoteDataSource(piggyCardsConfig, walletDataProvider)
        val api = remoteDataSource.buildApi(PiggyCardsApi::class.java)

        return PiggyCardsRepository(api, piggyCardsConfig)
    }
}
