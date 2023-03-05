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
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.util.Constants
import org.dash.wallet.features.exploredash.data.ExploreDataSource
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.MerchantType
import org.dash.wallet.features.exploredash.data.model.dashdirectgiftcard.GetGiftCardResponse
import org.dash.wallet.features.exploredash.data.model.merchant.GetMerchantByIdResponse
import org.dash.wallet.features.exploredash.data.model.paymentstatus.PaymentStatusResponse
import org.dash.wallet.features.exploredash.data.model.purchase.PurchaseGiftCardResponse
import org.dash.wallet.features.exploredash.repository.DashDirectRepositoryInt

@HiltViewModel
class DashDirectViewModel
@Inject
constructor(
    walletDataProvider: WalletDataProvider,
    exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration,
    private val sendPaymentService: SendPaymentService,
    private val repository: DashDirectRepositoryInt,
    private val transactionMetadata: TransactionMetadataProvider,
    private val exploreData: ExploreDataSource
) : ViewModel() {

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

    var purchaseGiftCardDataMerchant: Merchant? = null
    var purchaseGiftCardDataPaymentValue: Pair<Coin, Fiat>? = null

    var minCardPurchaseCoin: Coin = Coin.ZERO
    var minCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)
    var maxCardPurchaseCoin: Coin = Coin.ZERO
    var maxCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)

    init {
        exchangeRates
            .observeExchangeRate(Constants.USD_CURRENCY)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        walletDataProvider.observeBalance().distinctUntilChanged().onEach(_balance::postValue).launchIn(viewModelScope)
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

    suspend fun getPaymentStatus(paymentId: String, orderId: String): ResponseResource<PaymentStatusResponse?>? {
        repository.getDashDirectEmail()?.let { email ->
            return repository.getPaymentStatus(
                userEmail = email,
                paymentId = paymentId,
                orderId = orderId
            )
        }
        return null
    }

    suspend fun getGiftCardDetails(giftCardId: Long): ResponseResource<GetGiftCardResponse?>? {
        repository.getDashDirectEmail()?.let { email ->
            return repository.getGiftCardDetails(
                userEmail = email,
                giftCardId = giftCardId
            )
        }
        return null
    }

    suspend fun createSendingRequestFromDashUri(paymentURi: String): Transaction {
        val transaction = sendPaymentService.createSendingRequestFromDashUri(paymentURi)
        transactionMetadata.markGiftCardTransaction(transaction.txId, purchaseGiftCardDataMerchant?.iconBitmap)
        purchaseGiftCardDataMerchant?.iconBitmap = null

        return transaction
    }

    suspend fun getMerchantById(merchantId: Long): ResponseResource<GetMerchantByIdResponse?>? {
        repository.getDashDirectEmail()?.let { email ->
            return repository.getMerchantById(merchantId = merchantId, includeLocations = false, userEmail = email)
        }
        return null
    }

    fun setMinMaxCardPurchaseValues(minCardPurchase: Double, maximumCardPurchase: Double) {
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
            return getDiscountedAmount(myRate.coinToFiat(fullAmount), savingsPercentage)
        }
    }

    fun getDiscountedAmount(fullAmount: Fiat, savingsPercentage: Double): Fiat {
        return Fiat.valueOf(Constants.USD_CURRENCY, (fullAmount.value * (100.0 - savingsPercentage) / 100).toLong())
    }

    fun isUserSignInDashDirect() = repository.isUserSignIn()

    suspend fun signInToDashDirect(email: String) = repository.signIn(email)

    suspend fun createUserToDashDirect(email: String) = repository.createUser(email)

    suspend fun verifyEmail(code: String) = repository.verifyEmail(code)

    suspend fun logout() = repository.logout()

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
