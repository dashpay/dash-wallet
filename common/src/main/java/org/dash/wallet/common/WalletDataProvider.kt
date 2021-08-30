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
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.Resource

interface WalletDataProvider {

    fun freshReceiveAddress(): Address

    fun getExchangeRate(currencyCode: String): LiveData<ExchangeRate>

    fun getExchangeRates(): LiveData<List<ExchangeRate>>

    fun currencyCodes(): LiveData<List<String>>

    fun defaultCurrencyCode(): String

    fun sendCoins(address: Address, amount: Coin): LiveData<Resource<Transaction>>

    fun startSendCoinsForResult(activity: Activity, requestCode: Int, address: Address, amount: Coin?)

}