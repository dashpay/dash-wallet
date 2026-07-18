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

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.transactions.TxInfo

/**
 * Thrown when a send would leave less than the required leftover balance in the wallet
 * (e.g. for CrowdNode withdrawals). [missing] is the amount that is missing to satisfy
 * the requirement.
 */
class LeftoverBalanceException(val missing: Dash, message: String) : InsufficientFundsException(message)

class DirectPayException(message: String) : Exception(message)

/**
 * Describes which outputs a send may draw on. The wallet module maps these onto its
 * dashj coin selectors.
 */
sealed class SpendSelection {
    /** Default selection over all spendable outputs. */
    object Any : SpendSelection()

    /** Only outputs paying the given base58 address (mirrors `ByAddressCoinSelector`). */
    data class ByAddress(val address: String) : SpendSelection()

    /** Exactly the given output of the given transaction (mirrors `ExactOutputsSelector`). */
    data class ExactOutput(val txId: String, val outputIndex: Int) : SpendSelection()
}

/**
 * Neutral (dashj-free) payment service facade: amounts are [Dash], addresses are base58
 * strings, created transactions are returned as [TxInfo] snapshots or hex tx ids.
 */
interface SendPaymentService {

    /**
     * Sends [amount] to the base58 [address] and returns the created transaction's txId
     * as a hex string. Failures surface as exceptions classifiable with the neutral
     * `Throwable.is*` helpers.
     */
    @Throws(LeftoverBalanceException::class)
    suspend fun sendCoins(
        address: String,
        amount: Dash,
        emptyWallet: Boolean = false,
        checkBalanceConditions: Boolean = true
    ): String

    /**
     * Full-control send used by integrations that steer coin selection and output locking
     * (CrowdNode). Returns the created transaction as a [TxInfo].
     *
     * @param selection which outputs the send may draw on.
     * @param lockSentOutputsTo before broadcasting, lock the created transaction's P2PKH outputs
     *        paying this base58 address (mirrors the CrowdNode account-output locking).
     * @param canSpendLockedOutputsTo allow spending locked outputs that pay this base58 address.
     */
    @Throws(LeftoverBalanceException::class)
    suspend fun sendCoinsSelected(
        address: String,
        amount: Dash,
        selection: SpendSelection = SpendSelection.Any,
        emptyWallet: Boolean = false,
        checkBalanceConditions: Boolean = true,
        lockSentOutputsTo: String? = null,
        canSpendLockedOutputsTo: String? = null
    ): TxInfo

    /** Fee/total estimate for sending [amount] to [address]. */
    suspend fun estimateNetworkFee(
        address: String,
        amount: Dash,
        emptyWallet: Boolean = false
    ): TransactionEstimate

    data class TransactionEstimate(
        val fee: String,
        val amountToSend: Dash,
        val totalAmount: String
    )

    /** Pays the given `dash:` payment URI and returns the created transaction. */
    suspend fun payWithDashUrl(dashUri: String, serviceName: String?): TxInfo
}
