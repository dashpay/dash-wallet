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
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.wallet.CoinSelector
import org.bitcoinj.wallet.SendRequest
import java.util.function.Consumer
import java.util.function.Predicate

class LeftoverBalanceException(missing: Coin, message: String) : InsufficientMoneyException(missing, message)
class DirectPayException(message: String) : Exception(message)

/**
 * Thrown when a BIP70 payment was submitted but the merchant's response was lost, so the
 * transaction may or may not have been broadcast. The transaction's inputs stay locked while
 * the wallet keeps checking the network in the background; once the transaction is seen it is
 * committed as sent, and if it never appears the inputs are released.
 */
class PaymentSubmissionPendingException(val txId: Sha256Hash, cause: Throwable?) :
    Exception("Payment submission result unknown for $txId; verification pending", cause)

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

    suspend fun payWithDashUrl(dashUri: String, serviceName: String?): Transaction
    fun isFeeTooHigh(tx: Transaction): Boolean

    /** support manual tx creation */
    suspend fun completeTransaction(sendRequest: SendRequest)
    suspend fun signTransaction(sendRequest: SendRequest)
    suspend fun sendTransaction(sendRequest: SendRequest): Transaction
}
