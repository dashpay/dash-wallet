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

package org.dash.wallet.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.toCoin
import org.dash.wallet.common.money.toDash
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.transactions.filters.LockedTransaction

// ---------------------------------------------------------------------------------------------
// Neutral (dashj-free) adapters over WalletDataProvider for feature/integration modules.
// They delegate to the dashj-typed interface methods, so behavior is identical.
// ---------------------------------------------------------------------------------------------

/** [WalletDataProvider.observeTotalBalance] as neutral [Dash] amounts. */
fun WalletDataProvider.observeTotalDashBalance(): Flow<Dash> = observeTotalBalance().map { it.toDash() }

/** [WalletDataProvider.observeBalance] (with its default estimated balance type) as neutral [Dash] amounts. */
fun WalletDataProvider.observeDashBalance(): Flow<Dash> = observeBalance().map { it.toDash() }

/** [WalletDataProvider.getWalletBalance] as a neutral [Dash] amount. */
fun WalletDataProvider.getDashBalance(): Dash = getWalletBalance().toDash()

/** Estimated wallet balance (mirrors `wallet.getBalance(BalanceType.ESTIMATED)`), or null when no wallet is loaded. */
@Suppress("DEPRECATION")
fun WalletDataProvider.getEstimatedDashBalance(): Dash? =
    wallet?.getBalance(Wallet.BalanceType.ESTIMATED)?.toDash()

/**
 * Emits the hex tx id once the wallet transaction with hex id [txId] is IS-locked or confirmed
 * (mirrors [WalletDataProvider.observeTransactions] with a [LockedTransaction] filter).
 */
fun WalletDataProvider.observeTransactionLocked(txId: String): Flow<String> =
    observeTransactions(true, LockedTransaction(Sha256Hash.wrap(txId))).map { it.txId.toString() }

/**
 * Whether the wallet transaction with hex id [txId] is pending (mirrors `Transaction.isPending`);
 * false if the wallet doesn't know the transaction.
 */
fun WalletDataProvider.isTransactionPending(txId: String): Boolean =
    getTransaction(Sha256Hash.wrap(txId))?.isPending ?: false

/**
 * Net wallet value of the transaction with hex id [txId] (mirrors
 * `Transaction.getValue(transactionBag)`), or null if the wallet doesn't know the transaction.
 */
fun WalletDataProvider.getTransactionValue(txId: String): Dash? =
    getTransaction(Sha256Hash.wrap(txId))?.getValue(transactionBag)?.toDash()

/** Serialized hex of the wallet transaction with hex id [txId], or null if unknown. Useful for logging. */
fun WalletDataProvider.getTransactionHex(txId: String): String? =
    getTransaction(Sha256Hash.wrap(txId))?.toStringHex()

/**
 * True when sending [amount] would trip the leftover-balance check
 * (i.e. [WalletDataProvider.checkSendingConditions] would throw [LeftoverBalanceException]).
 */
fun WalletDataProvider.needsLeftoverBalanceWarning(amount: Dash): Boolean {
    return try {
        checkSendingConditions(null, amount.toCoin())
        false
    } catch (_: LeftoverBalanceException) {
        true
    }
}
