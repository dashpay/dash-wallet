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

package org.dash.wallet.features.exploredash.ui.dashdirect

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
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.discountBy
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.features.exploredash.data.dashdirect.GiftCardDao
import org.dash.wallet.features.exploredash.data.dashdirect.model.GiftCard
import org.dash.wallet.features.exploredash.data.dashdirect.model.merchant.GetMerchantByIdResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.purchase.PurchaseGiftCardResponse
import org.dash.wallet.features.exploredash.data.explore.ExploreDataSource
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.data.explore.model.MerchantType
import org.dash.wallet.features.exploredash.repository.DashDirectException
import org.dash.wallet.features.exploredash.repository.DashDirectRepositoryInt
import org.dash.wallet.features.exploredash.utils.DashDirectConstants
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Inject

@HiltViewModel
class DashDirectViewModel @Inject constructor(
    private val walletDataProvider: WalletDataProvider,
    exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration,
    private val sendPaymentService: SendPaymentService,
    private val repository: DashDirectRepositoryInt,
    private val transactionMetadata: TransactionMetadataProvider,
    private val exploreData: ExploreDataSource,
    private val giftCardDao: GiftCardDao,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(DashDirectViewModel::class.java)
    }

    val isUserSettingFiatIsNotUSD = (configuration.exchangeCurrencyCode != Constants.USD_CURRENCY)

    val dashFormat: MonetaryFormat
        get() = configuration.format

    private val _balance = MutableLiveData<Coin>()
    val balance: LiveData<Coin>
        get() = _balance

    val userEmail = repository.userEmail.asLiveData()

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val usdExchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    lateinit var giftCardMerchant: Merchant
    lateinit var giftCardPaymentValue: Fiat

    var minCardPurchaseCoin: Coin = Coin.ZERO
    var minCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)
    var maxCardPurchaseCoin: Coin = Coin.ZERO
    var maxCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)

    init {
        exchangeRates
            .observeExchangeRate(Constants.USD_CURRENCY)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        walletDataProvider
            .observeBalance()
            .distinctUntilChanged()
            .onEach(_balance::postValue)
            .launchIn(viewModelScope)
    }

    suspend fun purchaseGiftCard(): PurchaseGiftCardResponse.Data? {
        giftCardMerchant.merchantId?.let {
            val amountValue = giftCardPaymentValue
            repository.getDashDirectEmail()?.let { email ->
                val response = repository.purchaseGiftCard(
                    merchantId = it,
                    amountUSD = amountValue.toBigDecimal().toDouble(),
                    paymentCurrency = Constants.DASH_CURRENCY,
                    deviceID = UUID.randomUUID().toString(), // TODO
                    userEmail = email
                )

                when (response) {
                    is ResponseResource.Success -> {
                        if (response.value?.successful != true && !response.value?.errorMessage.isNullOrEmpty()) {
                            throw DashDirectException(response.value?.errorMessage!!)
                        }

                        if (response.value?.data?.success == true) {
                            return response.value?.data!!
                        }
                    }
                    is ResponseResource.Failure -> {
                        log.error("purchaseGiftCard error ${response.errorCode}: ${response.errorBody}")
                    }
                }
            }
        }

        return null
    }

    suspend fun createSendingRequestFromDashUri(paymentUri: String): Sha256Hash {
        val transaction = sendPaymentService.payWithDashUrl(paymentUri)
        log.info("dash direct transaction: ${transaction.txId}")
        transactionMetadata.markGiftCardTransaction(transaction.txId, giftCardMerchant.logoLocation)

        return transaction.txId
    }

    suspend fun getMerchantById(merchantId: Long): ResponseResource<GetMerchantByIdResponse?>? {
        repository.getDashDirectEmail()?.let { email ->
            return repository.getMerchantById(merchantId = merchantId, includeLocations = false, userEmail = email)
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

    fun getDiscountedAmount(fullAmount: Coin, savingsPercentage: Double): Fiat? {
        return _exchangeRate.value?.let {
            val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
            return myRate.coinToFiat(fullAmount).discountBy(savingsPercentage)
        }
    }

    fun isUserSignInDashDirect() = repository.isUserSignIn()

    suspend fun signInToDashDirect(email: String) = repository.signIn(email)

    suspend fun createUserToDashDirect(email: String) = repository.createUser(email)

    suspend fun verifyEmail(code: String) = repository.verifyEmail(code)

    suspend fun logout() = repository.logout()

    fun saveGiftCardDummy(txId: Sha256Hash, orderId: String, paymentId: String) {
        val giftCard = GiftCard(
            id = UUID.randomUUID().toString(),
            service = ServiceName.DashDirect,
            merchantName = giftCardMerchant.name ?: "",
            price = giftCardPaymentValue.value,
            discount = giftCardMerchant.savingsPercentage ?: DashDirectConstants.DEFAULT_DISCOUNT,
            currency = giftCardPaymentValue.currencyCode,
            transactionId = txId,
            currentBalanceUrl = giftCardMerchant.website,
            note = "$orderId+$paymentId"
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
        analyticsService.logEvent(event, mapOf())
    }

    // TODO Remove the test merchent
    suspend fun insertTestMerchent() {
        repository.getDashDirectEmail()?.let { email ->
            val response = repository.getMerchantById(merchantId = 318, includeLocations = false, userEmail = email)
            if (response is ResponseResource.Success) {
                response.value?.data?.merchant?.let {
                    var merchant =
                        Merchant().apply {
                            id = 318
                            name = "Cray Pay"
                            website = "http://www.craypay.com"
                            deeplink = "http://www.craypay.com"
                            merchantId = 318
                            coverImage = "https://craypaystorage.blob.core.windows.net/prod/content/craypay.png"
                            source = "DashDirect"
                            type = MerchantType.ONLINE
                            maxCardPurchase = it.maximumCardPurchase
                            minCardPurchase = it.minimumCardPurchase
                            savingsPercentage = it.savingsPercentage
                        }
                    exploreData.insertMerchant(merchant)
                }
            }
        }
    }
}
