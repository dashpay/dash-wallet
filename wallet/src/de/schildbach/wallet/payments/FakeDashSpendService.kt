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

package de.schildbach.wallet.payments

import android.net.Uri
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.wallet.CoinSelector
import org.bitcoinj.wallet.SendRequest
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.transactions.toTxInfo
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.SpendSelection
import org.dash.wallet.common.transactions.TxInfo
import java.util.function.Consumer
import java.util.function.Predicate
import javax.inject.Inject

class FakeDashSpendService @Inject constructor(
    private val realService: WalletSendPaymentService,
    private val walletDataProvider: WalletData
) : WalletSendPaymentService {
    internal companion object {
        const val DASH_SPEND_SCHEMA = "dashspend://"
    }

    override suspend fun sendCoins(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector?,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean,
        beforeSending: Consumer<Transaction>?,
        canSendLockedOutput: Predicate<TransactionOutput>?
    ): Transaction {
        return realService.sendCoins(
            address,
            amount,
            coinSelector,
            emptyWallet,
            checkBalanceConditions,
            beforeSending,
            canSendLockedOutput
        )
    }

    override suspend fun sendCoins(
        address: String,
        amount: Dash,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean
    ): String {
        return realService.sendCoins(address, amount, emptyWallet, checkBalanceConditions)
    }

    override suspend fun estimateNetworkFee(
        address: Address,
        amount: Coin,
        emptyWallet: Boolean
    ): WalletSendPaymentService.TransactionDetails {
        return realService.estimateNetworkFee(address, amount, emptyWallet)
    }

    override suspend fun estimateNetworkFee(
        address: String,
        amount: Dash,
        emptyWallet: Boolean
    ): SendPaymentService.TransactionEstimate {
        return realService.estimateNetworkFee(address, amount, emptyWallet)
    }

    override suspend fun payWithDashUrlTx(dashUri: String, serviceName: String?): Transaction {
        return if (dashUri.startsWith(DASH_SPEND_SCHEMA)) {
            val uri = Uri.parse(dashUri)
            val amount = Coin.valueOf(uri.getQueryParameter("amount")?.toLong() ?: 0)
            realService.sendCoins(
                Address.fromBase58(walletDataProvider.networkParameters, "yiCvnqNp53bjCReThnPx8ttuhM7JXUUyfQ"),
                amount
            )
        } else {
            realService.payWithDashUrlTx(dashUri, serviceName)
        }
    }

    override suspend fun payWithDashUrl(dashUri: String, serviceName: String?): TxInfo {
        return payWithDashUrlTx(dashUri, serviceName)
            .toTxInfo(walletDataProvider.transactionBag, walletDataProvider.networkParameters)
    }

    override suspend fun sendCoinsSelected(
        address: String,
        amount: Dash,
        selection: SpendSelection,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean,
        lockSentOutputsTo: String?,
        canSpendLockedOutputsTo: String?
    ): TxInfo {
        return realService.sendCoinsSelected(
            address, amount, selection, emptyWallet, checkBalanceConditions, lockSentOutputsTo, canSpendLockedOutputsTo
        )
    }

}
