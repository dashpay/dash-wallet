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

package org.dash.wallet.common.services

import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.wallet.Wallet

/**
 * Neutral (dashj-free) counterpart of dashj's [InsufficientMoneyException] for feature/integration
 * modules: thrown by [payAndGetTxId] when the wallet balance can't cover the payment.
 */
class InsufficientFundsException(message: String?, cause: Throwable? = null) : Exception(message, cause)

// ---------------------------------------------------------------------------------------------
// Neutral classifiers for send failures. Feature/integration modules can't reference the dashj
// exception types thrown by SendPaymentService, so these helpers classify them instead
// (mirroring `catch (e: <dashj type>)` blocks exactly).
// ---------------------------------------------------------------------------------------------

/** True when this is dashj's [InsufficientMoneyException] (wallet balance can't cover the payment). */
val Throwable.isInsufficientMoney: Boolean
    get() = this is InsufficientMoneyException

/** True when this is dashj's [Wallet.DustySendRequested] or [Wallet.CouldNotAdjustDownwards] (dusty send). */
val Throwable.isDustySend: Boolean
    get() = this is Wallet.DustySendRequested || this is Wallet.CouldNotAdjustDownwards

/** True when this is the [LeftoverBalanceException] thrown by the leftover-balance check. */
val Throwable.isLeftoverBalanceWarning: Boolean
    get() = this is LeftoverBalanceException

/**
 * Neutral counterpart of [SendPaymentService.payWithDashUrl] for modules that must not depend
 * on dashj: pays the given payment URI and returns the created transaction's txId as a hex string.
 *
 * dashj's [InsufficientMoneyException] (including [LeftoverBalanceException]) is rethrown as the
 * neutral [InsufficientFundsException]; all other exceptions propagate unchanged.
 */
suspend fun SendPaymentService.payAndGetTxId(dashUri: String, serviceName: String?): String {
    val transaction = try {
        payWithDashUrl(dashUri, serviceName)
    } catch (e: InsufficientMoneyException) {
        throw InsufficientFundsException(e.message, e)
    }
    return transaction.txId.toString()
}
