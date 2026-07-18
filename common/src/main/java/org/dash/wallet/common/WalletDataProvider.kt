/*
 * Copyright 2020 Dash Core Group.
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
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.TransactionFilter

/**
 * Neutral (dashj-free) facade over the wallet for feature/integration modules.
 *
 * Amounts are neutral [Dash] values, addresses are base58 strings, transaction ids are hex
 * strings and transactions are [TxInfo] snapshots. The wallet module implements this interface
 * (converting to/from its dashj types internally) and additionally exposes the dashj-typed
 * surface through its own `WalletData` interface for wallet-internal consumers.
 */
interface WalletDataProvider {

    /** Network id string, e.g. [org.dash.wallet.common.payments.parsers.AddressNetwork.ID_MAINNET]. */
    val networkId: String

    /** True while a wallet is loaded. Balance accessors return zero (not null) when it is false. */
    val walletLoaded: Boolean

    fun freshReceiveAddressString(): String
    fun currentReceiveAddressString(): String

    /** Estimated wallet balance. */
    fun getWalletBalance(): Dash

    /**
     * Number of spendable unspent outputs coin selection can draw on —
     * `calculateAllSpendCandidates(false, false)`, the exact output set
     * `getBalance(ESTIMATED)` sums (all keychains) — or 0 while no wallet
     * is loaded.
     */
    fun spendableUtxoCount(): Int

    fun observeWalletChanged(): Flow<Unit>

    fun observeWalletReset(): Flow<Unit>

    /** Estimated balance stream (mirrors observing `Wallet.getBalance(ESTIMATED)`). */
    fun observeEstimatedBalance(): Flow<Dash>

    fun canAffordIdentityCreation(): Boolean

    // Treat @withConfidence with care - it may produce a lot of events and affect performance.
    fun observeTransactions(withConfidence: Boolean = false, vararg filters: TransactionFilter): Flow<TxInfo>

    /** The wallet transaction with hex id [txId], or null if the wallet doesn't know it. */
    fun getTransaction(txId: String): TxInfo?

    fun getTransactions(vararg filters: TransactionFilter): Collection<TxInfo>

    fun wrapAllTransactions(vararg wrappers: TransactionWrapperFactory): Collection<TransactionWrapper>

    fun attachOnWalletWipedListener(listener: suspend () -> Unit)

    fun detachOnWalletWipedListener(listener: suspend () -> Unit)

    @Throws(LeftoverBalanceException::class)
    fun checkSendingConditions(address: String?, amount: Dash)

    fun observeTotalBalance(): Flow<Dash>

    /**
     * Locks the outputs of the wallet transaction [txId] that pay the base58 [address]
     * (P2PKH outputs only, mirroring the original CrowdNode account-output locking).
     */
    fun lockOutputsPayingTo(txId: String, address: String)

    /**
     * Suspends until the wallet transaction [txId] is locked (IS-lock, mined, or seen by
     * more than one broadcast peer) — the same condition as the `LockedTransaction` filter.
     * Returns immediately when the transaction already satisfies it.
     */
    suspend fun waitUntilLocked(txId: String)
}
