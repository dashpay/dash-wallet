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

package org.dash.wallet.features.exploredash.ui.dashspend

import android.content.Intent
import androidx.lifecycle.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.services.*
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.DenominationType
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.dashspend.model.UpdatedMerchantDetails
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.repository.DashSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepositoryFactory
import org.dash.wallet.features.exploredash.repository.CTXSpendRepositoryInt
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class DashSpendState(
    val email: String? = null,
    val isLoggedIn: Boolean = false
)

@HiltViewModel
class DashSpendViewModel @Inject constructor(
    private val walletDataProvider: WalletDataProvider,
    exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration,
    private val sendPaymentService: SendPaymentService,
    private val repositoryFactory: DashSpendRepositoryFactory,
    private val transactionMetadata: TransactionMetadataProvider,
    private val giftCardDao: GiftCardDao,
    private val giftCardProviderDao: GiftCardProviderDao,
    networkState: NetworkStateInt,
    private val analytics: AnalyticsService,
    private val savedStateHandle: SavedStateHandle,
    private val exploreDao: MerchantDao,
    private val ctxSpendConfig: CTXSpendConfig,
    blockchainStateProvider: BlockchainStateProvider
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(DashSpendViewModel::class.java)
        private const val MERCHANT_ID_KEY = "merchant_id"
        private const val BALANCE_KEY = "balance"
        private const val SELECTED_PROVIDER_KEY = "selected_provider"
    }

    var selectedProvider: GiftCardProviderType? = null
        set(value) {
            field = value
            savedStateHandle[SELECTED_PROVIDER_KEY] = value?.name
        }
    private var stateTrackingJob: Job? = null

    private val providers: Map<GiftCardProviderType, DashSpendRepository> by lazy {
        mapOf(
            GiftCardProviderType.CTX to repositoryFactory.create(GiftCardProviderType.CTX),
            GiftCardProviderType.PiggyCards to repositoryFactory.create(GiftCardProviderType.PiggyCards)
        )
    }
    private val ctxSpendRepository = providers[GiftCardProviderType.CTX]!!
    private val piggyCardsRepository = providers[GiftCardProviderType.PiggyCards]!!

    val dashFormat: MonetaryFormat
        get() = configuration.format

    private val _balance = MutableLiveData<Coin>().apply {
        savedStateHandle.get<Long>(BALANCE_KEY)?.let {
            value = Coin.valueOf(it)
        }
    }
    val balance: LiveData<Coin>
        get() = _balance

    val balanceWithDiscount: Coin?
        get() = _balance.value?.let { balance ->
            _giftCardMerchant.value?.let { merchant ->
                val d = merchant.savingsFraction
                Coin.valueOf((balance.value / (1.0 - d)).toLong()).minus(Transaction.DEFAULT_TX_FEE.multiply(20))
            }
        }

    private val _dashSpendState = MutableStateFlow(DashSpendState())
    val dashSpendState: StateFlow<DashSpendState>
        get() = _dashSpendState.asStateFlow()

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val usdExchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _isFixedDenomination = MutableStateFlow<Boolean?>(null)
    val isFixedDenomination: StateFlow<Boolean?> = _isFixedDenomination.asStateFlow()

    private val _giftCardPaymentValue = MutableStateFlow<Fiat>(Fiat.valueOf(Constants.USD_CURRENCY, 0))
    val giftCardPaymentValue: StateFlow<Fiat> = _giftCardPaymentValue.asStateFlow()

    val isNetworkAvailable = networkState.isConnected.asLiveData()

    private val _giftCardMerchant = MutableStateFlow<Merchant?>(null)
    val giftCardMerchant: StateFlow<Merchant?> = _giftCardMerchant.asStateFlow()

    fun setGiftCardMerchant(merchant: Merchant?) {
        log.info("setGiftCardMerchant called with merchant: ${merchant?.name}, denominations: ${merchant?.denominations}")
        _giftCardMerchant.value = merchant
        // Save merchant ID to state handle for restoration
        savedStateHandle[MERCHANT_ID_KEY] = merchant?.merchantId
    }

    var minCardPurchaseCoin: Coin = Coin.ZERO
    var minCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)
    var maxCardPurchaseCoin: Coin = Coin.ZERO
    var maxCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)

    var openedCTXSpendTermsAndConditions = false

    private val _isBlockchainReplaying = MutableStateFlow(false)
    val isBlockchainReplaying = _isBlockchainReplaying.asStateFlow()

    init {
        exchangeRates
            .observeExchangeRate(Constants.USD_CURRENCY)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        walletDataProvider
            .observeSpendableBalance()
            .distinctUntilChanged()
            .onEach(_balance::postValue)
            .launchIn(viewModelScope)

        // Save balance changes to SavedStateHandle
        _balance.observeForever { coin ->
            savedStateHandle[BALANCE_KEY] = coin?.value
        }

        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach { state ->
                _isBlockchainReplaying.value = state.replaying
            }
            .launchIn(viewModelScope)
    }

    suspend fun purchaseGiftCard(): GiftCardInfo = withContext(Dispatchers.IO) {
        _giftCardMerchant.value?.merchantId?.let {
            ctxSpendConfig.set(CTXSpendConfig.PREFS_LAST_PURCHASE_START, System.currentTimeMillis())
            val amountValue = _giftCardPaymentValue.value
            val provider = giftCardProviderDao.getProviderByMerchantId(it, selectedProvider!!.name)
            when (selectedProvider) {
                GiftCardProviderType.CTX -> {
                    try {
                        ctxSpendRepository.orderGiftcard(
                            merchantId = provider!!.sourceId,
                            fiatAmount = MonetaryFormat.FIAT.noCode().format(amountValue).toString(),
                            fiatCurrency = Constants.USD_CURRENCY,
                            cryptoCurrency = Constants.DASH_CURRENCY
                        )
                    } catch (e: CTXSpendException) {
                        log.error("purchaseGiftCard error: CTXSpendException", e)
                        throw e
                    } catch (e: Exception) {
                        log.error("purchaseGiftCard error: {}", e::class.java.simpleName, e)
                        throw CTXSpendException("purchaseGiftCard error: ${e.message}", ServiceName.CTXSpend, null, null, e)
                    }
                }
                GiftCardProviderType.PiggyCards -> {
                    try {
                        piggyCardsRepository.orderGiftcard(
                            cryptoCurrency = Constants.DASH_CURRENCY,
                            merchantId = provider!!.sourceId,
                            fiatAmount = MonetaryFormat.FIAT.noCode().format(amountValue).toString(),
                            fiatCurrency = Constants.USD_CURRENCY
                        )
                    } catch (e: CTXSpendException) {
                        log.error("purchaseGiftCard error: CTXSpendException", e)
                        throw e
                    } catch (e: Exception) {
                        log.error("purchaseGiftCard error: {}", e::class.java.simpleName, e)
                        throw CTXSpendException("purchaseGiftCard error: ${e.message}", ServiceName.PiggyCards, null, null, e)
                    }
                }

                null -> {
                    throw IllegalArgumentException("no giftcard provider")
                }
            }
        } ?: throw CTXSpendException("purchaseGiftCard error: no merchant")
    }

    suspend fun createSendingRequestFromDashUri(paymentUri: String): Sha256Hash = withContext(Dispatchers.IO) {
        val transaction = sendPaymentService.payWithDashUrl(
            paymentUri,
            _giftCardMerchant.value?.source?.lowercase() ?: ServiceName.CTXSpend
        )
        log.info("ctx spend transaction: ${transaction.txId}")
        transactionMetadata.markGiftCardTransaction(transaction.txId, selectedProvider!!.serviceName, _giftCardMerchant.value?.logoLocation)
//        BitcoinURI(paymentUri).message?.let { memo ->
//            if (memo.isNotBlank()) {
//                transactionMetadata.setTransactionMemo(transaction.txId, memo)
//            }
//        }
        transaction.txId
    }

    /** updates merchant details according to the currently selected provider [selectedProvider]
     *
     */
    suspend fun updateMerchantDetails(merchant: Merchant): Merchant = withContext(Dispatchers.IO) {
        // previously this API call would only be made if we didn't have the min and max card values,
        // but now we need to call this every time to get an updated savings percentage and to see if
        // the merchant is enabled
        val response = try {
            getMerchant(merchant, selectedProvider!!.name)
        } catch (ex: Exception) {
            log.error("failed to get merchant ${merchant.merchantId}", ex)
            null
        }

        try {
            response?.let { apiResponse ->
                val updatedProviders = merchant.giftCardProviders.mapIndexed { index, giftCardProvider ->
                    if (index == 0 && selectedProvider?.name == "CTX") {
                        giftCardProvider.copy(
                            savingsPercentage = apiResponse.savingsPercentage,
                            active = apiResponse.enabled
                        )
                    } else if (index == 0 && selectedProvider?.name == "PiggyCards") {
                        giftCardProvider.copy(
                            savingsPercentage = apiResponse.savingsPercentage,
                            active = apiResponse.enabled
                        )
                    } else {
                        giftCardProvider
                    }
                }

                return@withContext merchant.deepCopy(
                    savingsPercentage = apiResponse.savingsPercentage,
                    giftCardProviders = updatedProviders
                ).also { copy ->
                    copy.minCardPurchase = apiResponse.minimumCardPurchase
                    copy.maxCardPurchase = apiResponse.maximumCardPurchase
                    copy.active = apiResponse.enabled
                    copy.fixedDenomination = apiResponse.denominationType == DenominationType.Fixed
                    copy.denominations = apiResponse.denominations
                    copy.denominationsType = apiResponse.denominationsType
                }
            }
        } catch (e: Exception) {
            log.warn("updated merchant details contains unexpected data:", e)
        }
        
        return@withContext merchant
    }

    /**
     * obtains updated merchant data from all providers
     *
     * returns a new [Merchant] object
      */

    suspend fun updateMerchantDetailsForAllProviders(merchant: Merchant): Merchant {
        val response = try {
            getMerchants(merchant)
        } catch (ex: Exception) {
            log.error("failed to get merchant ${merchant.merchantId}", ex)
            null
        }

         try {
            response?.apply {
                return merchant.deepCopy(
                    savingsPercentage = this.first.maxOf { it.savingsPercentage },
                    giftCardProviders = this.second
                )
            }
        } catch (e: Exception) {
            log.warn("updated merchant details contains unexpected data:", e)
        }
        return merchant
    }

    private suspend fun getMerchant(merchant: Merchant, provider: String): UpdatedMerchantDetails? {
        val giftCardProvider = giftCardProviderDao.getProviderByMerchantId(merchant.merchantId!!, provider)
        return giftCardProvider?.let {
            providers[GiftCardProviderType.fromProviderName(giftCardProvider.provider)]?.getMerchant(giftCardProvider.sourceId)
        }
    }

    private suspend fun getMerchants(merchant: Merchant): Pair<List<UpdatedMerchantDetails>, List<GiftCardProvider>> {
        val providers = giftCardProviderDao.getProvidersByMerchantId(merchant.merchantId!!)
        val merchantResponseList = arrayListOf<UpdatedMerchantDetails>()
        val providerResponseList = arrayListOf<GiftCardProvider>()
        providers.forEach { provider ->
            when (provider.provider) {
                "CTX" -> {
                    if (ctxSpendRepository.isUserSignedIn()) {
                        ctxSpendRepository.getMerchant(provider.sourceId)?.let {
                            merchantResponseList.add(it)
                            providerResponseList.add(
                                provider.copy(
                                    savingsPercentage = it.savingsPercentage,
                                    active = it.enabled
                                )
                            )
                        }
                    } else {
                        providerResponseList.add(provider)
                    }
                }

                "PiggyCards" -> {
                    if (piggyCardsRepository.isUserSignedIn()) {
                        piggyCardsRepository.getMerchant(provider.sourceId)?.let {
                            merchantResponseList.add(it)
                            providerResponseList.add(
                                provider.copy(
                                    savingsPercentage = it.savingsPercentage,
                                    active = it.enabled
                                )
                            )
                        }
                    } else {
                        providerResponseList.add(provider)
                    }
                }

                else -> {

                }
            }
        }
        return Pair(merchantResponseList, providerResponseList)
    }

    fun refreshMinMaxCardPurchaseValues() {
        _giftCardMerchant.value?.let { merchant ->
            val minCardPurchase = merchant.minCardPurchase ?: 0.0
            val maximumCardPurchase = merchant.maxCardPurchase ?: 0.0
            minCardPurchaseFiat = Fiat.parseFiat(Constants.USD_CURRENCY, minCardPurchase.toString())
            maxCardPurchaseFiat = Fiat.parseFiat(Constants.USD_CURRENCY, maximumCardPurchase.toString())
            updatePurchaseLimits()
        }
    }

    fun withinLimits(purchaseAmount: Coin): Boolean {
        return _giftCardMerchant.value?.let { merchant ->
            if (merchant.fixedDenomination) {
                true
            } else {
                !purchaseAmount.isLessThan(minCardPurchaseCoin) &&
                    !purchaseAmount.isGreaterThan(maxCardPurchaseCoin)
            }
        } ?: false
    }

    fun withinLimits(purchaseAmount: Fiat): Boolean {
        return _giftCardMerchant.value?.let { merchant ->
            if (merchant.fixedDenomination) {
                true
            } else {
                !purchaseAmount.isLessThan(minCardPurchaseFiat) &&
                    !purchaseAmount.isGreaterThan(maxCardPurchaseFiat)
            }
        } ?: false
    }

    fun observeDashSpendState(provider: GiftCardProviderType?) {
        val serviceRepository = providers[provider]
        stateTrackingJob?.cancel()

        if (provider == null || serviceRepository == null) {
            return
        }

        stateTrackingJob = serviceRepository.userEmail
            .distinctUntilChanged()
            .onEach { email ->
                val isLoggedIn = serviceRepository.isUserSignedIn()
                _dashSpendState.value = _dashSpendState.value.copy(
                    email = email,
                    isLoggedIn = isLoggedIn
                )
            }.launchIn(viewModelScope)
    }

    suspend fun isUserSignedInService(provider: GiftCardProviderType): Boolean {
        return providers[provider]?.isUserSignedIn() == true
    }

    suspend fun signUp(provider: GiftCardProviderType, email: String): Boolean {
        return providers[provider]?.signup(email) == true
    }

    suspend fun signIn(provider: GiftCardProviderType, email: String): Boolean {
        return providers[provider]?.login(email) == true
    }

    suspend fun verifyEmail(provider: GiftCardProviderType, code: String): Boolean {
        return providers[provider]?.verifyEmail(code) == true
    }

    suspend fun logout(provider: GiftCardProviderType) {
        providers[provider]?.logout()
    }

    fun saveGiftCardDummy(txId: Sha256Hash, giftCardResponse: GiftCardInfo) {
        val giftCard = GiftCard(
            txId = txId,
            merchantName = _giftCardMerchant.value?.name ?: "",
            price = giftCardResponse.fiatAmount?.toDouble() ?: 0.0,
            merchantUrl = giftCardResponse.redeemUrl,
            note = giftCardResponse.id
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
        } catch (_: LeftoverBalanceException) {
            true
        }
    }

    fun setIsFixedDenomination(isFixed: Boolean) {
        _isFixedDenomination.value = isFixed
    }

    fun setGiftCardPaymentValue(fiat: Fiat) {
        _giftCardPaymentValue.value = fiat
    }

    fun setGiftCardPaymentValue(coin: Coin) {
        _exchangeRate.value?.let {
            val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
            _giftCardPaymentValue.value = myRate.coinToFiat(coin)
        }
    }

    fun resetSelectedDenomination() {
        _giftCardPaymentValue.value = Fiat.valueOf(Constants.USD_CURRENCY, 0)
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    suspend fun checkToken(): Boolean {
        return try {
            !ctxSpendRepository.isUserSignedIn() || ctxSpendRepository.refreshToken()
        } catch (ex: Exception) {
            false
        }
    }

    private fun getSupportEmail(sendToService: Boolean, serviceName: String) = when {
        sendToService && serviceName == ServiceName.CTXSpend -> arrayOf(CTXSpendConstants.REPORT_EMAIL, "support@dash.org")
        sendToService && serviceName == ServiceName.PiggyCards -> arrayOf(PiggyCardsConstants.REPORT_EMAIL, "support@dash.org")
        else -> arrayOf("support@dash.org")
    }

    fun createEmailIntent(
        subject: String,
        sendToService: Boolean,
        ex: CTXSpendException?
    ) = Intent(Intent.ACTION_SEND).apply {
        setType("message/rfc822")
        putExtra(Intent.EXTRA_EMAIL, getSupportEmail(sendToService, ex?.serviceName ?: ""))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, createReportEmail(ex))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun getSavedMerchantId(): String? {
        return savedStateHandle.get<String>(MERCHANT_ID_KEY)
    }

    fun getSavedProvider(): String? {
        return savedStateHandle.get<String>(SELECTED_PROVIDER_KEY)
    }

    private fun createReportEmail(ex: CTXSpendException?): String {
        val report = StringBuilder()
        report.append("${ex?.serviceName ?: "DashSpend"} Issue Report").append("\n")
        _giftCardMerchant.value?.let { merchant ->
            report.append("Merchant details").append("\n")
                .append("name: ").append(merchant.name).append("\n")
                .append("id: ").append(merchant.merchantId).append("\n")
                .append("min: ").append(merchant.minCardPurchase).append("\n")
                .append("max: ").append(merchant.maxCardPurchase).append("\n")
                .append("discount: ").append(merchant.savingsFraction).append("\n")
                .append("denominations type: ").append(merchant.denominationsType).append("\n")
                .append("denominations: ").append(merchant.denominations).append("\n")
                .append("\n")
        } ?: run {
            report.append("No merchant selected").append("\n")
        }

        ex?.txId?.let {
            report.append("\n")
            report.append("Transaction (base58): ").append(it).append("\n")
        }

        report.append("\n")
        report.append("Purchase Details").append("\n")
        report.append("amount entered: ").append(_giftCardPaymentValue.value.toFriendlyString()).append("\n")
        report.append("\n")
        ex?.let { exception ->
            exception.message?.let {
                report.append(it).append("\n")
            }
            exception.errorCode?.let {
                report.append("code: ").append(it).append("\n")
            }
            exception.errorBody?.let {
                report.append("body:\n").append(it).append("\n")
            }
            exception.giftCardResponse?.let { giftCard ->
                report.append("Gift Card Information: ").append("\n")
                    .append("id: ").append(giftCard.id).append("\n")
                    .append("status: ").append(giftCard.status).append("\n")
                    .append("barcodeUrl: ").append(giftCard.barcodeUrl ?: "N/A").append("\n")
                    .append("cardNumber: ").append(giftCard.cardNumber ?: "N/A").append("\n")
                    .append("cardPin: ").append(giftCard.cardPin ?: "N/A").append("\n")
                    .append("cryptoAmount: ").append(giftCard.cryptoAmount ?: "N/A").append("\n")
                    .append("cryptoCurrency: ").append(giftCard.cryptoCurrency ?: "N/A").append("\n")
                    .append("paymentCryptoNetwork: ").append(giftCard.paymentCryptoNetwork).append("\n")
                    .append("paymentId: ").append(giftCard.paymentId).append("\n")
                    .append("percentDiscount: ").append(giftCard.percentDiscount).append("\n")
                    .append("rate: ").append(giftCard.rate).append("\n")
                    .append("redeemUrl: ").append(giftCard.redeemUrl).append("\n")
                    .append("fiatAmount: ").append(giftCard.fiatAmount ?: "N/A").append("\n")
                    .append("fiatCurrency: ").append(giftCard.fiatCurrency ?: "N/A").append("\n")
                    .append("paymentUrls: ").append(giftCard.paymentUrls?.toString() ?: "N/A").append("\n")
            }
            exception.cause?.let {
                report.append("Stack trace\n").append(exception.stackTraceToString())
            }
        }
        return report.toString()
    }

    suspend fun getMerchantById(merchantId: String): Merchant? = withContext(Dispatchers.IO) {
        exploreDao.getMerchantById(merchantId)
    }

    fun getGiftCardDiscount(denomination: Double): Double {
        val merchantId = giftCardMerchant.value?.giftCardProviders?.find { it.provider == selectedProvider?.name}?.sourceId
        return merchantId?.let {
            when (selectedProvider) {
                GiftCardProviderType.CTX -> ctxSpendRepository.getGiftCardDiscount(merchantId, denomination)
                GiftCardProviderType.PiggyCards -> piggyCardsRepository.getGiftCardDiscount(merchantId, denomination)
                else -> 0.0
            }
        } ?: 0.0
    }


    private fun updatePurchaseLimits() {
        _exchangeRate.value?.let {
            val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
            minCardPurchaseCoin = myRate.fiatToCoin(minCardPurchaseFiat)
            maxCardPurchaseCoin = myRate.fiatToCoin(maxCardPurchaseFiat)
        }
    }

    fun logError(ctxSpendException: Throwable, message: String) {
        analytics.logError(ctxSpendException, message)
    }
}
