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

/**
 * Neutral (dashj-free) counterpart of dashj's [InsufficientMoneyException] for feature/integration
 * modules: thrown by [payAndGetTxId] when the wallet balance can't cover the payment.
 */
class InsufficientFundsException(message: String?, cause: Throwable? = null) : Exception(message, cause)

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
