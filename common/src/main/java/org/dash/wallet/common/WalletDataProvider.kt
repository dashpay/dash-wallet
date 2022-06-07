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

import android.app.Activity
import androidx.lifecycle.LiveData
import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.ExchangeRateData
import org.dash.wallet.common.transactions.TransactionFilter
import org.dash.wallet.common.transactions.TransactionWrapper

interface WalletDataProvider {
    // The wallet is in here temporary. Do not use from the feature modules.
    val wallet: Wallet?

    val networkParameters: NetworkParameters

    fun currentReceiveAddress(): Address

    fun freshReceiveAddress(): Address

    @Deprecated("Inject ExchangeRatesProvider instead")
    fun getExchangeRate(currencyCode: String): LiveData<ExchangeRateData>

    @Deprecated("Inject Configuration instead")
    fun defaultCurrencyCode(): String

    fun startSendCoinsForResult(activity: Activity, requestCode: Int, address: Address, amount: Coin?)

    fun observeBalance(balanceType: Wallet.BalanceType = Wallet.BalanceType.ESTIMATED): Flow<Coin>

    fun observeTransactions(vararg filters: TransactionFilter): Flow<Transaction>

    fun getTransactions(vararg filters: TransactionFilter): Collection<Transaction>

    fun wrapAllTransactions(vararg wrappers: TransactionWrapper): Iterable<TransactionWrapper>

    fun attachOnWalletWipedListener(listener: () -> Unit)

    fun detachOnWalletWipedListener(listener: () -> Unit)

    fun processDirectTransaction(tx: Transaction)
}