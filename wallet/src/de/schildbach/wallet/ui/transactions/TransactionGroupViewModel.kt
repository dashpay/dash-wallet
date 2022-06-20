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

package de.schildbach.wallet.ui.transactions

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.transactions.TransactionWrapper
import javax.inject.Inject

@HiltViewModel
class TransactionGroupViewModel @Inject constructor(
    val walletData: WalletDataProvider,
    val config: Configuration
) : ViewModel() {
    val dashFormat: MonetaryFormat = config.format.noCode()

    private val _dashValue = MutableLiveData<Coin>()
    val dashValue: LiveData<Coin>
        get() = _dashValue

    private val _exchangeRate = MutableLiveData<ExchangeRate?>()
    val exchangeRate: LiveData<ExchangeRate?>
        get() = _exchangeRate

    private val _transactions = MutableLiveData<List<Transaction>>()
    val transactions: LiveData<List<Transaction>>
        get() = _transactions

    fun init(transactionWrapper: TransactionWrapper) {
        _exchangeRate.value = transactionWrapper.transactions.first().exchangeRate
        refreshTransactions(transactionWrapper)

        walletData.observeTransactions()
            .onEach { tx ->
                if (transactionWrapper.tryInclude(tx)) {
                    refreshTransactions(transactionWrapper)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun refreshTransactions(transactionWrapper: TransactionWrapper) {
        _transactions.value = transactionWrapper.transactions.toList()
        _dashValue.value = transactionWrapper.getValue(walletData.transactionBag)
    }
}