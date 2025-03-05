/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.ctxspend

import androidx.lifecycle.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.services.*
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.discountBy
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.features.exploredash.data.ctxspend.model.GetMerchantResponse
import org.dash.wallet.features.exploredash.data.ctxspend.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.repository.CTXSpendRepositoryInt
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class CTXSpendViewModel @Inject constructor(
    private val walletDataProvider: WalletDataProvider,
    exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration,
    private val sendPaymentService: SendPaymentService,
    private val repository: CTXSpendRepositoryInt,
    private val transactionMetadata: TransactionMetadataProvider,
    private val giftCardDao: GiftCardDao,
    networkState: NetworkStateInt
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(CTXSpendViewModel::class.java)
    }

    val dashFormat: MonetaryFormat
        get() = configuration.format

    private val _balance = MutableLiveData<Coin>()
    val balance: LiveData<Coin>
        get() = _balance

    val balanceWithDiscount: Coin?
        get() = _balance.value?.let {
            val d = giftCardMerchant.savingsFraction
            return Coin.valueOf((it.value / (1.0 - d)).toLong()).minus(Transaction.DEFAULT_TX_FEE.multiply(20))
        }

    val userEmail = repository.userEmail.asLiveData()

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val usdExchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    val isNetworkAvailable = networkState.isConnected.asLiveData()

    lateinit var giftCardMerchant: Merchant
    lateinit var giftCardPaymentValue: Fiat

    var minCardPurchaseCoin: Coin = Coin.ZERO
    var minCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)
    var maxCardPurchaseCoin: Coin = Coin.ZERO
    var maxCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)

    var openedCTXSpendTermsAndConditions = false

    init {
        exchangeRates
            .observeExchangeRate(Constants.USD_CURRENCY)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        walletDataProvider
            .observeTotalBalance()
            .distinctUntilChanged()
            .onEach(_balance::postValue)
            .launchIn(viewModelScope)
    }

    suspend fun purchaseGiftCard(): GiftCardResponse {
        giftCardMerchant.merchantId?.let {
            val amountValue = giftCardPaymentValue

            val response = repository.purchaseGiftCard(
                merchantId = it,
                fiatAmount = amountValue.toBigDecimal().toDouble().toString(),
                fiatCurrency = "USD",
                cryptoCurrency = Constants.DASH_CURRENCY
            )

            when (response) {
                is ResponseResource.Success -> {
                    return response.value!!
                }
                is ResponseResource.Failure -> {
                    log.error("purchaseGiftCard error ${response.errorCode}: ${response.errorBody}")
                    throw CTXSpendException("purchaseGiftCard error ${response.errorCode}: ${response.errorBody}")
                }
            }
        }
        throw CTXSpendException("purchaseGiftCard error")
    }

    suspend fun createSendingRequestFromDashUri(paymentUri: String): Sha256Hash {
        val transaction = sendPaymentService.payWithDashUrl(paymentUri)
        log.info("ctx spend transaction: ${transaction.txId}")
        transactionMetadata.markGiftCardTransaction(transaction.txId, giftCardMerchant.logoLocation)

        return transaction.txId
    }

    suspend fun updateMerchantDetails(merchant: Merchant) {
        if (merchant.minCardPurchase != null && merchant.maxCardPurchase != null) {
            return
        }

        val response = getMerchant(merchant.merchantId!!)

        if (response is ResponseResource.Success) {
            try {
                response.value?.let {
                    merchant.savingsPercentage = it.savingsPercentage
                    merchant.minCardPurchase = it.minimumCardPurchase
                    merchant.maxCardPurchase = it.maximumCardPurchase
                }
            } catch (e: Exception) {
                log.warn("updated merchant details contains unexpected data:", e)
            }
        }
    }

    private suspend fun getMerchant(merchantId: String): ResponseResource<GetMerchantResponse?>? {
        repository.getCTXSpendEmail()?.let { email ->
            return repository.getMerchant(merchantId)
        }
        return null
    }

    fun refreshMinMaxCardPurchaseValues() {
        val minCardPurchase = giftCardMerchant.minCardPurchase ?: 0.0
        val maximumCardPurchase = giftCardMerchant.maxCardPurchase ?: 0.0
        minCardPurchaseFiat = Fiat.parseFiat(Constants.USD_CURRENCY, minCardPurchase.toString())
        maxCardPurchaseFiat = Fiat.parseFiat(Constants.USD_CURRENCY, maximumCardPurchase.toString())
        updatePurchaseLimits()
    }

    private fun updatePurchaseLimits() {
        _exchangeRate.value?.let {
            val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
            minCardPurchaseCoin = myRate.fiatToCoin(minCardPurchaseFiat)
            maxCardPurchaseCoin = myRate.fiatToCoin(maxCardPurchaseFiat)
        }
    }

    fun getDiscountedAmount(fullAmount: Coin, savingsFraction: Double): Fiat? {
        return _exchangeRate.value?.let {
            val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
            return myRate.coinToFiat(fullAmount).discountBy(savingsFraction)
        }
    }

    suspend fun isUserSignedInCTXSpend() = repository.isUserSignedIn()

    suspend fun signInToCTXSpend(email: String) = repository.login(email)

    suspend fun verifyEmail(code: String) = repository.verifyEmail(code)

    suspend fun logout() = repository.logout()

    fun saveGiftCardDummy(txId: Sha256Hash, giftCardId: String) {
        val giftCard = GiftCard(
            txId = txId,
            merchantName = giftCardMerchant.name ?: "",
            price = giftCardPaymentValue.toBigDecimal().toDouble(),
            merchantUrl = giftCardMerchant.website,
            note = giftCardId
        )
        viewModelScope.launch {
            giftCardDao.insertGiftCard(giftCard)
        }
    }

    fun needsCrowdNodeWarning(dashAmount: String): Boolean {
        val outputAmount = Coin.parseCoin(dashAmount)
        return try {
            walletDataProvider.checkSendingConditions(null, outputAmount)
            false
        } catch (ex: LeftoverBalanceException) {
            true
        }
    }

    fun logEvent(event: String) {
    }
}
