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

package org.dash.wallet.features.exploredash.network.service.stubs

import android.net.Uri
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.CoinSelector
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.SendPaymentService
import javax.inject.Inject

class FakeDashSpendService @Inject constructor(
    private val realService: SendPaymentService,
    private val walletDataProvider: WalletDataProvider
) : SendPaymentService {
    internal companion object {
        const val DASH_SPEND_SCHEMA = "dashspend://"
    }

    override suspend fun sendCoins(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector?,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean
    ): Transaction {
        return realService.sendCoins(address, amount, coinSelector, emptyWallet, checkBalanceConditions)
    }

    override suspend fun estimateNetworkFee(
        address: Address,
        amount: Coin,
        emptyWallet: Boolean
    ): SendPaymentService.TransactionDetails {
        return realService.estimateNetworkFee(address, amount, emptyWallet)
    }

    override suspend fun payWithDashUrl(dashUri: String): Transaction {
        return if (dashUri.startsWith(DASH_SPEND_SCHEMA)) {
            val uri = Uri.parse(dashUri)
            val amount = Coin.valueOf(uri.getQueryParameter("amount")?.toLong() ?: 0)
            realService.sendCoins(
                Address.fromBase58(walletDataProvider.networkParameters, "yiCvnqNp53bjCReThnPx8ttuhM7JXUUyfQ"),
                amount
            )
        } else {
            realService.payWithDashUrl(dashUri)
        }
    }
}
