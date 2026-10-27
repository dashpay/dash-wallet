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
import org.bitcoinj.wallet.Wallet
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.PaymentRecoveryMetadata
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.SpendSelection
import org.dash.wallet.common.services.WalletSession
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
        canSendLockedOutput: Predicate<TransactionOutput>?,
        originWallet: Wallet?
    ): Transaction {
        return realService.sendCoins(
            address,
            amount,
            coinSelector,
            emptyWallet,
            checkBalanceConditions,
            beforeSending,
            canSendLockedOutput,
            originWallet
        )
    }

    override fun currentWalletSession(): WalletSession? = realService.currentWalletSession()

    override fun walletOf(session: WalletSession?): Wallet? = realService.walletOf(session)

    override suspend fun sendCoins(
        address: String,
        amount: Dash,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean,
        session: WalletSession?
    ): String {
        return realService.sendCoins(address, amount, emptyWallet, checkBalanceConditions, session)
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

    override suspend fun payWithDashUrlTx(
        dashUri: String,
        serviceName: String?,
        recovery: PaymentRecoveryMetadata?,
        session: WalletSession?,
        onTransactionCreated: (suspend (String) -> Unit)?
    ): Transaction {
        return if (dashUri.startsWith(DASH_SPEND_SCHEMA)) {
            val uri = Uri.parse(dashUri)
            val amount = Coin.valueOf(uri.getQueryParameter("amount")?.toLong() ?: 0)
            val transaction = realService.sendCoins(
                Address.fromBase58(walletDataProvider.networkParameters, "yiCvnqNp53bjCReThnPx8ttuhM7JXUUyfQ"),
                amount,
                originWallet = realService.walletOf(session)
            )
            // Callers persist the order from this callback, so skipping it leaves a completed
            // purchase with nothing for the details screen to load. Later than the real path,
            // which calls it before submitting, but sendCoins gives no pre-send suspension point.
            onTransactionCreated?.invoke(transaction.txId.toString())
            transaction
        } else {
            realService.payWithDashUrlTx(dashUri, serviceName, recovery, session, onTransactionCreated)
        }
    }

    override suspend fun payWithDashUrl(
        dashUri: String,
        serviceName: String?,
        recovery: PaymentRecoveryMetadata?,
        session: WalletSession?,
        onTransactionCreated: (suspend (String) -> Unit)?
    ): TxInfo {
        return sentTxInfo(payWithDashUrlTx(dashUri, serviceName, recovery, session, onTransactionCreated), walletDataProvider)
    }

    override suspend fun sendCoinsSelected(
        address: String,
        amount: Dash,
        selection: SpendSelection,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean,
        lockSentOutputsTo: String?,
        canSpendLockedOutputsTo: String?,
        session: WalletSession?
    ): TxInfo {
        return realService.sendCoinsSelected(
            address, amount, selection, emptyWallet, checkBalanceConditions, lockSentOutputsTo, canSpendLockedOutputsTo,
            session
        )
    }

}
