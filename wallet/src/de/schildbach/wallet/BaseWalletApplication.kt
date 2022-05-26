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

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.multidex.MultiDexApplication
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet.ui.send.SendCoinsActivity
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRateData

abstract class BaseWalletApplication : MultiDexApplication(), WalletDataProvider {

    protected abstract fun getWalletData(): Wallet?

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
        return getWalletData()!!.freshReceiveAddress()
    }

    override fun getExchangeRate(currencyCode: String): LiveData<ExchangeRateData> {
        return ExchangeRatesRepository.instance.getRate(currencyCode).switchMap {
            return@switchMap MutableLiveData<ExchangeRateData>().apply {
                value = ExchangeRateData(it.currencyCode, it.rate ?: "0.0", it.getCurrencyName(this@BaseWalletApplication), it.fiat)
            }
        }
    }

    override fun getExchangeRates(): LiveData<List<ExchangeRateData>> {
        return ExchangeRatesRepository.instance.rates.switchMap {
            return@switchMap MutableLiveData<List<ExchangeRateData>>().apply {
                value = it.map { exchangeRate ->
                    ExchangeRateData(exchangeRate.currencyCode, exchangeRate.rate ?: "0.0", exchangeRate.getCurrencyName(this@BaseWalletApplication), exchangeRate.fiat)
                }
            }
        }
    }

    override fun defaultCurrencyCode(): String {
        return walletApplication.configuration.exchangeCurrencyCode!!
    }

    override fun startSendCoinsForResult(activity: Activity, requestCode: Int, address: Address, amount: Coin?) {
        val finalAmount = amount ?: Coin.ZERO
        val uriStr = "dash:${address.toBase58()}?amount=${finalAmount.toPlainString()}"
        val sendCoinsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr), activity, SendCoinsActivity::class.java)
        activity.startActivityForResult(sendCoinsIntent, requestCode)
    }

    private fun checkWalletCreated() {
        if (getWalletData() == null) {
            throw RuntimeException("this method can't be used before creating the wallet")
        }
    }
}
