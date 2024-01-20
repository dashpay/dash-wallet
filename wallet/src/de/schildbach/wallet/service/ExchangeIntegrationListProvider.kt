/*
 * Copyright 2024 Dash Core Group.
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

package de.schildbach.wallet.service

import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.dash.wallet.common.integrations.ExchangeIntegration
import org.dash.wallet.common.integrations.ExchangeIntegrationProvider
import javax.inject.Inject

class ExchangeIntegrationListProvider @Inject constructor() : ExchangeIntegrationProvider {

    private val exchangeList = MutableStateFlow(listOf<ExchangeIntegration>())

    init {
        val list = listOf(
            ExchangeIntegration("coinbase", false, null, null, R.string.coinbase, R.drawable.ic_coinbase),
            ExchangeIntegration("uphold", false, null, null, R.string.uphold_account, R.drawable.ic_uphold)
        )
        exchangeList.value = list
    }

    override fun observeDepositAddresses(currency: String): Flow<List<ExchangeIntegration>> {
        val list = listOf(
            ExchangeIntegration(
                "coinbase",
                false,
                "183axN6F7ZjwayiJPjjwJgWGas6J9mtfi",
                currency,
                R.string.coinbase,
                R.drawable.ic_coinbase
            ),
            ExchangeIntegration(
                "uphold",
                false,
                "bc1qxhgnnp745zryn2ud8hm6k3mygkkpkm35020js0",
                currency,
                R.string.uphold_account,
                R.drawable.ic_uphold
            )
        )
        exchangeList.value = list
        return exchangeList
    }

    override fun connectToIntegration(name: String) {
        TODO("Not yet implemented")
    }
}
