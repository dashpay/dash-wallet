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
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.wallet.CoinSelector
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory
import org.dash.wallet.common.transactions.filters.TransactionFilter

interface WalletDataProvider {
    @Deprecated("The wallet is in here temporary and will be moved to a separate holder, limited to the the wallet module. In feature modules, use transactionBag instead.")
    val wallet: Wallet?

    val transactionBag: TransactionBag

    val networkParameters: NetworkParameters
    val authenticationGroupExtension: AuthenticationGroupExtension?
    fun freshReceiveAddress(): Address
    fun currentReceiveAddress(): Address

    fun getWalletBalance(): Coin
    fun getMixedBalance(): Coin

    fun observeWalletChanged(): Flow<Unit>

    fun observeWalletReset(): Flow<Unit>

    fun observeBalance(
        balanceType: Wallet.BalanceType = Wallet.BalanceType.ESTIMATED,
        coinSelector: CoinSelector? = null
    ): Flow<Coin>

    fun observeSpendableBalance(): Flow<Coin>

    fun canAffordIdentityCreation(): Boolean

    // Treat @withConfidence with care - it may produce a lot of events and affect performance.
    fun observeTransactions(withConfidence: Boolean = false, vararg filters: TransactionFilter): Flow<Transaction>

    fun observeAuthenticationKeyUsage(): Flow<List<AuthenticationKeyUsage>>

    fun getTransaction(hash: Sha256Hash): Transaction?

    fun getTransactions(vararg filters: TransactionFilter): Collection<Transaction>

    fun wrapAllTransactions(vararg wrappers: TransactionWrapperFactory): Collection<TransactionWrapper>

    fun attachOnWalletWipedListener(listener: () -> Unit)

    fun detachOnWalletWipedListener(listener: () -> Unit)

    fun processDirectTransaction(tx: Transaction)

    @Throws(LeftoverBalanceException::class)
    fun checkSendingConditions(address: Address?, amount: Coin)

    fun observeMostRecentTransaction(): Flow<Transaction>
    fun observeMixedBalance(): Flow<Coin>
    fun observeTotalBalance(): Flow<Coin>
    fun lockOutput(outPoint: TransactionOutPoint): Boolean
}
