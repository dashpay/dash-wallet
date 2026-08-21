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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.transactions.filters.LockedTransaction

// ---------------------------------------------------------------------------------------------
// Convenience adapters over the neutral WalletDataProvider facade.
// ---------------------------------------------------------------------------------------------

/**
 * Issue a fresh receive address OFF the calling thread.
 *
 * The wallet-module implementation of [WalletDataProvider.freshReceiveAddressString]
 * (dashj `freshReceiveAddress()`) issues a key and then forces a SYNCHRONOUS
 * full-wallet save — measured 1.2s of main-thread block on a wallet with 215
 * DashPay friend key chains — so it must never run on the main thread. This
 * helper imposes [Dispatchers.IO] at the seam; feature/integration callers in
 * coroutines (which typically run on the Main dispatcher) should use it
 * instead of calling [WalletDataProvider.freshReceiveAddressString] directly.
 *
 * An extension (not an interface method) so test fakes/mocks that stub
 * `freshReceiveAddressString()` keep working unchanged.
 */
suspend fun WalletDataProvider.freshReceiveAddressStringOffMain(): String =
    withContext(Dispatchers.IO) { freshReceiveAddressString() }

/** [WalletDataProvider.observeTotalBalance] as neutral [Dash] amounts. */
fun WalletDataProvider.observeTotalDashBalance(): Flow<Dash> = observeTotalBalance()

/** [WalletDataProvider.observeEstimatedBalance] as neutral [Dash] amounts. */
fun WalletDataProvider.observeDashBalance(): Flow<Dash> = observeEstimatedBalance()

/** [WalletDataProvider.getWalletBalance] as a neutral [Dash] amount. */
fun WalletDataProvider.getDashBalance(): Dash = getWalletBalance()

/**
 * Estimated wallet balance, or null when no wallet is loaded (the old
 * `wallet?.getBalance(ESTIMATED)` contract that callers like Maya's ConvertViewViewModel rely on).
 */
fun WalletDataProvider.getEstimatedDashBalance(): Dash? =
    if (walletLoaded) getWalletBalance() else null

/**
 * Emits the hex tx id once the wallet transaction with hex id [txId] is IS-locked or confirmed
 * (mirrors [WalletDataProvider.observeTransactions] with a [LockedTransaction] filter).
 */
fun WalletDataProvider.observeTransactionLocked(txId: String): Flow<String> =
    observeTransactions(true, LockedTransaction(txId)).map { it.txId }

/**
 * Whether the wallet transaction with hex id [txId] is pending (mirrors `Transaction.isPending`);
 * false if the wallet doesn't know the transaction.
 */
fun WalletDataProvider.isTransactionPending(txId: String): Boolean =
    getTransaction(txId)?.isPending ?: false

/**
 * Net wallet value of the transaction with hex id [txId] (mirrors
 * `Transaction.getValue(transactionBag)`), or null if the wallet doesn't know the transaction.
 */
fun WalletDataProvider.getTransactionValue(txId: String): Dash? =
    getTransaction(txId)?.let { Dash(it.netValueDuffs) }

/** Serialized hex of the wallet transaction with hex id [txId], or null if unknown. Useful for logging. */
fun WalletDataProvider.getTransactionHex(txId: String): String? =
    getTransaction(txId)?.rawHex

/**
 * True when sending [amount] would trip the leftover-balance check
 * (i.e. [WalletDataProvider.checkSendingConditions] would throw [LeftoverBalanceException]).
 */
fun WalletDataProvider.needsLeftoverBalanceWarning(amount: Dash): Boolean {
    return try {
        checkSendingConditions(null, amount)
        false
    } catch (_: LeftoverBalanceException) {
        true
    }
}
