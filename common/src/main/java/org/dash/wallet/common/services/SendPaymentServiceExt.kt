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

/**
 * Neutral counterpart of dashj's `InsufficientMoneyException` for feature/integration
 * modules: thrown by [payAndGetTxId] when the wallet balance can't cover the payment.
 */
open class InsufficientFundsException(message: String?, cause: Throwable? = null) : Exception(message, cause)

// ---------------------------------------------------------------------------------------------
// Neutral classifiers for send failures. Feature/integration modules can't reference the dashj
// exception types that wallet-side code may throw, so these helpers classify them by class name
// (mirroring `catch (e: <dashj type>)` blocks exactly) in addition to the neutral types.
// ---------------------------------------------------------------------------------------------

private fun Throwable.hasAncestorNamed(vararg fqcn: String): Boolean {
    var c: Class<*>? = javaClass
    while (c != null) {
        if (fqcn.contains(c.name)) return true
        c = c.superclass
    }
    return false
}

/** True when the wallet balance can't cover the payment (dashj's `InsufficientMoneyException` or the neutral equivalent). */
val Throwable.isInsufficientMoney: Boolean
    get() = this is InsufficientFundsException || hasAncestorNamed("org.bitcoinj.core.InsufficientMoneyException")

/** True when this is dashj's `Wallet.DustySendRequested` or `Wallet.CouldNotAdjustDownwards` (dusty send). */
val Throwable.isDustySend: Boolean
    get() = hasAncestorNamed(
        "org.bitcoinj.wallet.Wallet\$DustySendRequested",
        "org.bitcoinj.wallet.Wallet\$CouldNotAdjustDownwards"
    )

/** True when this is the [LeftoverBalanceException] thrown by the leftover-balance check. */
val Throwable.isLeftoverBalanceWarning: Boolean
    get() = this is LeftoverBalanceException

/**
 * Pays the given payment URI and returns the created transaction's txId as a hex string.
 *
 * Dashj-side insufficient-funds failures (including [LeftoverBalanceException]) are rethrown as
 * the neutral [InsufficientFundsException]; all other exceptions propagate unchanged.
 */
suspend fun SendPaymentService.payAndGetTxId(dashUri: String, serviceName: String?): String {
    val transaction = try {
        payWithDashUrl(dashUri, serviceName)
    } catch (e: Exception) {
        if (e.isInsufficientMoney && e !is InsufficientFundsException) {
            throw InsufficientFundsException(e.message, e)
        }
        throw e
    }
    return transaction.txId
}
