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
package de.schildbach.wallet

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.multidex.MultiDexApplication
import de.schildbach.wallet.rates.ExchangeRatesRepository
import org.bitcoinj.core.Address
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRateData

// TODO: do we need this class?
abstract class BaseWalletApplication : MultiDexApplication(), WalletDataProvider {

    protected val backgroundHandler: Handler

    // TODO remove this ugly casting
    private val walletApplication by lazy { this as WalletApplication }

    init {
        val backgroundThread = HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    override fun freshReceiveAddress(): Address {
        checkWalletCreated()
        return wallet!!.freshReceiveAddress()
    }

    override fun getExchangeRate(currencyCode: String): LiveData<ExchangeRateData> {
        return ExchangeRatesRepository.instance.getRate(currencyCode).switchMap {
            return@switchMap MutableLiveData<ExchangeRateData>().apply {
                value = ExchangeRateData(it.currencyCode, it.rate ?: "0.0", it.getCurrencyName(this@BaseWalletApplication), it.fiat)
            }
        }
    }

    override fun defaultCurrencyCode(): String {
        return walletApplication.configuration.exchangeCurrencyCode!!
    }

    private fun checkWalletCreated() {
        if (wallet == null) {
            throw RuntimeException("this method can't be used before creating the wallet")
        }
    }
}
