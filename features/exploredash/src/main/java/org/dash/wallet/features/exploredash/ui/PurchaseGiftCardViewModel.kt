/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.Constants
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.merchent.GetDataMerchantIdResponse
import org.dash.wallet.features.exploredash.data.model.merchants.GetMerchantByIdResponse
import org.dash.wallet.features.exploredash.data.model.purchase.PurchaseGiftCardResponse
import org.dash.wallet.features.exploredash.repository.DashDirectRepositoryInt
import java.util.*
import javax.inject.Inject

@HiltViewModel
class PurchaseGiftCardViewModel @Inject constructor(
    walletDataProvider: WalletDataProvider,
    exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration,
    private val repository: DashDirectRepositoryInt
) : ViewModel() {

    val isUserSettingFaitIsNotUSD = (configuration.exchangeCurrencyCode != Constants.USD_CURRENCY)

    val dashFormat: MonetaryFormat
        get() = configuration.format

    private val _balance = MutableLiveData<Coin>()
    val balance: LiveData<Coin>
        get() = _balance

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val usdExchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    var purchaseGiftCardDataMerchant: Merchant? = null
    var purchaseGiftCardDataPaymentValue: Pair<Coin, Fiat>? = null

    var minCardPurchaseCoin: Coin = Coin.ZERO
    var minCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)
    var maxCardPurchaseCoin: Coin = Coin.ZERO
    var maxCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)

    init {
        exchangeRates
            .observeExchangeRate(Constants.USD_CURRENCY)
            .onEach { updateMinMaxPurchase() }
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        walletDataProvider.observeBalance()
            .distinctUntilChanged()
            .onEach(_balance::postValue)
            .launchIn(viewModelScope)
    }

    suspend fun purchaseGiftCard(): ResponseResource<PurchaseGiftCardResponse?>? {
        purchaseGiftCardDataMerchant?.merchantId?.let {
            purchaseGiftCardDataPaymentValue?.let { amountValue ->
                repository.getDashDirectEmail()?.let { email ->
                    return repository.purchaseGiftCard(
                        merchantId = it,
                        giftCardAmount = amountValue.first.toPlainString().toDouble(),
                        currency = Constants.DASH_CURRENCY,
                        deviceID = UUID.randomUUID().toString(),
                        userEmail = email
                    )
                }
            }
        }
        return null
    }

    suspend fun getMerchantById(): ResponseResource<GetDataMerchantIdResponse?>? {
        purchaseGiftCardDataMerchant?.merchantId?.let { id ->
            repository.getDashDirectEmail()?.let { email ->
                return repository.getMerchantById(
                    merchantId = id,
                    includeLocations = false,
                    deviceID = UUID.randomUUID().toString(),
                    userEmail = email
                )
            }
        }
        return null
    }

    suspend fun getMerchantById(id: Long): ResponseResource<GetMerchantByIdResponse?>? {
        return repository.getMerchantById(
            merchantId = id,
        )
    }

    fun setMinMaxCardPurchaseValues(
        minCardPurchase: Double,
        maximumCardPurchase: Double
    ) {
        minCardPurchaseFiat = Fiat.parseFiat(
            Constants.USD_CURRENCY,
            minCardPurchase.toString()
        )

        maxCardPurchaseFiat = Fiat.parseFiat(
            Constants.USD_CURRENCY,
            maximumCardPurchase.toString()
        )

        updateMinMaxPurchase()
    }

    private fun updateMinMaxPurchase() {
        _exchangeRate.value?.let {
            val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
            minCardPurchaseCoin = myRate.fiatToCoin(minCardPurchaseFiat)
            maxCardPurchaseCoin = myRate.fiatToCoin(maxCardPurchaseFiat)
        }
    }

    fun getDiscountedAmount(fullAmount: Coin, savingsPercentage: Double): Fiat? {
        return _exchangeRate.value?.let {
            val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
            val discountedAmount =
                Coin.valueOf((fullAmount.value * (100.0 - savingsPercentage) / 100).toLong())
            return myRate.coinToFiat(discountedAmount)
        }
    }
}
