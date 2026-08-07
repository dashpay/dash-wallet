/*
 * Copyright 2026 Dash Core Group.
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

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.wallet.CoinSelector
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Dashj-typed payment service surface for wallet-module consumers — the former dashj half of
 * `org.dash.wallet.common.services.SendPaymentService`, unchanged. Feature/integration modules
 * use the neutral [SendPaymentService] instead.
 */
interface WalletSendPaymentService : SendPaymentService {
    @Throws(LeftoverBalanceException::class)
    suspend fun sendCoins(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector? = null,
        emptyWallet: Boolean = false,
        checkBalanceConditions: Boolean = true,
        beforeSending: Consumer<Transaction>? = null,
        canSendLockedOutput: Predicate<TransactionOutput>? = null
    ): Transaction

    suspend fun estimateNetworkFee(
        address: Address,
        amount: Coin,
        emptyWallet: Boolean = false
    ): TransactionDetails

    data class TransactionDetails(
        val fee: String,
        val amountToSend: Coin,
        val totalAmount: String
    )

    /** The dashj-typed twin of the neutral `payWithDashUrl` (returns the live transaction). */
    suspend fun payWithDashUrlTx(dashUri: String, serviceName: String?): Transaction

    /** support manual tx creation */
    suspend fun completeTransaction(sendRequest: SendRequest)
    suspend fun signTransaction(sendRequest: SendRequest)
    suspend fun sendTransaction(sendRequest: SendRequest): Transaction
}
