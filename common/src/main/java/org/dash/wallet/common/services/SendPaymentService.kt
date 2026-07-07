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

package org.dash.wallet.common.services

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.wallet.CoinSelector
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.money.Dash
import java.util.function.Consumer
import java.util.function.Predicate

class LeftoverBalanceException(missing: Coin, message: String) : InsufficientMoneyException(missing, message)
class DirectPayException(message: String) : Exception(message)

interface SendPaymentService {
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

    /**
     * Neutral (dashj-free) counterpart of [sendCoins] for feature/integration modules:
     * sends [amount] to the base58 [address] and returns the created transaction's txId
     * as a hex string. Behaves exactly like the dashj-typed overload, including thrown
     * exceptions (use the neutral `Throwable.is*` helpers to classify them).
     */
    @Throws(LeftoverBalanceException::class)
    suspend fun sendCoins(
        address: String,
        amount: Dash,
        emptyWallet: Boolean = false,
        checkBalanceConditions: Boolean = true
    ): String

    /** Neutral (dashj-free) counterpart of [estimateNetworkFee] for feature/integration modules. */
    suspend fun estimateNetworkFee(
        address: String,
        amount: Dash,
        emptyWallet: Boolean = false
    ): TransactionEstimate

    /** Neutral counterpart of [TransactionDetails] for modules that don't depend on dashj. */
    data class TransactionEstimate(
        val fee: String,
        val amountToSend: Dash,
        val totalAmount: String
    )

    suspend fun payWithDashUrl(dashUri: String, serviceName: String?): Transaction

    /** support manual tx creation */
    suspend fun completeTransaction(sendRequest: SendRequest)
    suspend fun signTransaction(sendRequest: SendRequest)
    suspend fun sendTransaction(sendRequest: SendRequest): Transaction
}
