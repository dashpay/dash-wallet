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
 * Thrown when a BIP70 payment was submitted but the merchant's response was lost, so the
 * transaction may or may not have been broadcast. The transaction's inputs stay locked while
 * the wallet keeps checking the network in the background; once the transaction is seen it is
 * committed as sent, and if it never appears the inputs are released.
 *
 * Open so the wallet module can say more precisely what is unknown (an acknowledged payment
 * whose local record could not be written yet), while every caller that must keep the order
 * and withhold a retry still catches this one type.
 *
 * @property txId the transaction id, as hex
 */
open class PaymentSubmissionPendingException(
    val txId: String,
    cause: Throwable?,
    message: String = "Payment submission result unknown for $txId; verification pending"
) : Exception(message, cause)

/**
 * Thrown instead of building a payment while an earlier payment's outcome is still unknown and its
 * inputs cannot be kept out of coin selection. Nothing was built, signed or sent. It lifts by
 * itself once that payment is confirmed on the network or released; the screen should say so
 * (`R.string.payment_still_confirming`) rather than report a failure.
 */
class PaymentStillConfirmingException(message: String) : IllegalStateException(message)

/**
 * Facts a caller wants kept with a payment so one recovered long afterwards looks like the
 * original. Only for payments whose submission result may be unknown; ordinary payments record
 * their metadata directly.
 */
data class PaymentRecoveryMetadata(
    val isGiftCardPurchase: Boolean = false,
    val merchantIconUrl: String? = null
)

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

    /**
     * Pays the given `dash:` payment URI and returns the created transaction.
     *
     * The payment is made from the wallet installed when this is called, and refused if a wipe
     * takes that wallet away before it is submitted.
     *
     * @param recovery facts to keep with a payment whose submission result may turn out unknown
     * @param onTransactionCreated invoked with the hex transaction id once the transaction is
     *   built and signed, before it is submitted wherever the payment path allows it, so a caller
     *   can persist anything it will need to recover the payment later. Runs while the payment is
     *   still recoverable: after this point the transaction may reach the payee even if this
     *   process dies.
     * @throws PaymentSubmissionPendingException if the payment may have been sent but its outcome
     *   is not known yet
     */
    suspend fun payWithDashUrl(
        dashUri: String,
        serviceName: String?,
        recovery: PaymentRecoveryMetadata? = null,
        onTransactionCreated: (suspend (String) -> Unit)? = null
    ): TxInfo
}
