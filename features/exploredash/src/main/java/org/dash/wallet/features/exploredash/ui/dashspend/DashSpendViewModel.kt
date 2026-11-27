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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.services.*
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.DenominationType
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.data.dashspend.model.UpdatedMerchantDetails
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepositoryFactory
import org.dash.wallet.features.exploredash.repository.PiggyCardsRepository
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants
import org.dash.wallet.features.exploredash.utils.GiftCardCurrency
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants.SUPPORT_PIGGY_CARDS_TEST_MERCHANT
import org.dash.wallet.features.exploredash.utils.PiggyCardsTestMerchants
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class DashSpendState(
    val email: String? = null,
    val isLoggedIn: Boolean = false
)

data class GiftCardOrderItem(
    val value: Double = 0.00,
    val quantity: Int = 1,
    var productId: Int = 0
) {
    companion object {
        val DEFAULT = GiftCardOrderItem()
    }
}

data class GiftCardShoppingCart constructor(
    private val items: ArrayList<GiftCardOrderItem>
) {
    constructor(items: List<GiftCardOrderItem>) : this(ArrayList(items))
    constructor(value: Double, quantity: Int) :
        this(arrayListOf(GiftCardOrderItem(value, quantity)))

    fun add(item: GiftCardOrderItem) = items.add(item)
    fun copy(): GiftCardShoppingCart {
        return GiftCardShoppingCart(
            items.map { it.copy() }
        )
    }
    fun first() = items.first()
    fun get(index: Int) = items[index]
    fun<R> map(transform: (GiftCardOrderItem) -> R): List<R> = items.map(transform)
    fun<R> mapIndexed(transform: (Int, GiftCardOrderItem) -> R): List<R> = items.mapIndexed(transform)

    fun forEach(function: (GiftCardOrderItem) -> Unit) {
        items.forEach(function)
    }
    fun cardCount(): Int = items.sumOf { it.quantity }
}

/** How far a gift card purchase has got, held by the view model so it survives dialog recreation. */
enum class GiftCardSubmissionState {
    IDLE,

    /** A payment is being submitted right now. */
    IN_PROGRESS,

    /** Submitted with an unknown result. It may have reached the merchant, so never resubmit. */
    PENDING,

    /** Paid for. */
    COMPLETED
}

/** Why a CTX purchase cannot go ahead. The wallet never guesses a card's currency or its Dash rate. */
enum class GiftCardPurchaseBlock {
    /** The merchant has not answered yet. */
    CURRENCY_LOADING,
    CURRENCY_UNKNOWN,

    /** The rate has not been looked up yet. */
    RATE_LOADING,

    /** The rate was looked up and there is none. */
    RATE_MISSING,

    /** The order is not exactly one card with an amount, which is all a CTX order can hold. */
    NO_AMOUNT,

    /** The amount has more decimals than the card currency has. */
    AMOUNT_NOT_REPRESENTABLE
}

/** What is known about the currency the selected CTX merchant sells its cards in. */
sealed class CardCurrencyState {
    object Loading : CardCurrencyState()
    data class Known(val code: String) : CardCurrencyState()

    /** The merchant could not be fetched, or reported no usable currency. */
    object Unknown : CardCurrencyState()
}

/** The Dash rate for the card currency. A lookup that found nothing is not the same as one still running. */
sealed class CardRateState {
    object Loading : CardRateState()
    object Missing : CardRateState()
    data class Available(val rate: ExchangeRate) : CardRateState()
}

/** A second purchase was attempted while one was already submitted or unresolved. */
class DuplicateGiftCardSubmissionException(val state: GiftCardSubmissionState) :
    Exception("A gift card purchase is already $state")

@OptIn(ExperimentalCoroutinesApi::class)
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
    blockchainStateProvider: BlockchainStateProvider,
    private val unresolvedPayments: UnresolvedPaymentsProvider
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(DashSpendViewModel::class.java)
        private const val MERCHANT_ID_KEY = "merchant_id"
        private const val BALANCE_KEY = "balance"
        private const val SELECTED_PROVIDER_KEY = "selected_provider"
    }

    var selectedProvider: GiftCardProviderType? = null
        set(value) {
            if (field != value) {
                // a selection made at one provider must never be shown or ordered at another
                clearOrderSelection()
            }
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

    private val _cardCurrencyState = MutableStateFlow<CardCurrencyState>(CardCurrencyState.Loading)
    val cardCurrencyState: StateFlow<CardCurrencyState> = _cardCurrencyState.asStateFlow()
    private var currencyMerchantId: String? = null
    private var currencyLoad = 0

    /** ISO 4217 code the card is priced in. Null for a CTX merchant whose currency is not confirmed. */
    val cardCurrency: String?
        get() = if (selectedProvider == GiftCardProviderType.CTX) {
            (_cardCurrencyState.value as? CardCurrencyState.Known)?.code
        } else {
            Constants.USD_CURRENCY
        }

    private val _cardRateState = MutableStateFlow<CardRateState>(CardRateState.Loading)
    val cardRateState: StateFlow<CardRateState> = _cardRateState.asStateFlow()

    /** The rate that converts card amounts to Dash for display, limits and the balance check. */
    val cardExchangeRate: org.bitcoinj.utils.ExchangeRate?
        get() = if (selectedProvider == GiftCardProviderType.CTX) {
            (_cardRateState.value as? CardRateState.Available)?.rate
                ?.takeIf { it.currencyCode == cardCurrency }
                ?.let(::usableRate)
        } else {
            usableRate(_exchangeRate.value)
        }

    /** USD until the card turns out to be sold in another currency, so the balance keeps its estimate. */
    val balanceExchangeRate: org.bitcoinj.utils.ExchangeRate?
        get() = cardExchangeRate ?: usableRate(_exchangeRate.value).takeIf {
            (cardCurrency ?: Constants.USD_CURRENCY) == Constants.USD_CURRENCY
        }

    private fun usableRate(rate: ExchangeRate?): org.bitcoinj.utils.ExchangeRate? = try {
        rate?.let { org.bitcoinj.utils.ExchangeRate(it.fiat) }
    } catch (e: Exception) {
        log.error("unusable exchange rate {}", rate, e)
        null
    }

    val purchaseBlock: GiftCardPurchaseBlock?
        get() {
            if (selectedProvider != GiftCardProviderType.CTX) {
                return null
            }
            val currency = when (val state = _cardCurrencyState.value) {
                is CardCurrencyState.Known -> state.code
                CardCurrencyState.Loading -> return GiftCardPurchaseBlock.CURRENCY_LOADING
                CardCurrencyState.Unknown -> return GiftCardPurchaseBlock.CURRENCY_UNKNOWN
            }
            val card = giftCardOrderInfo.value.entries.singleOrNull()
            return when {
                _cardRateState.value == CardRateState.Loading -> GiftCardPurchaseBlock.RATE_LOADING
                cardExchangeRate == null -> GiftCardPurchaseBlock.RATE_MISSING
                card == null || card.value != 1 || !(card.key > 0.0) -> GiftCardPurchaseBlock.NO_AMOUNT
                !GiftCardCurrency.isRepresentable(card.key, currency) ->
                    GiftCardPurchaseBlock.AMOUNT_NOT_REPRESENTABLE
                else -> null
            }
        }

    private val _isFixedDenomination = MutableStateFlow<Boolean?>(null)
    val isFixedDenomination: StateFlow<Boolean?> = _isFixedDenomination.asStateFlow()

    private val _isFixedDenominationMultiple = MutableStateFlow<Boolean?>(null)
    val isFixedDenominationMultiple: StateFlow<Boolean?> = _isFixedDenominationMultiple.asStateFlow()

    private val _giftCardOrderInfo = MutableStateFlow<Map<Double, Int>>(emptyMap())
    val giftCardOrderInfo: StateFlow<Map<Double, Int>> = _giftCardOrderInfo.asStateFlow()

    val isNetworkAvailable = networkState.isConnected.asLiveData()

    private val _giftCardMerchant = MutableStateFlow<Merchant?>(null)
    val giftCardMerchant: StateFlow<Merchant?> = _giftCardMerchant.asStateFlow()

    fun setGiftCardMerchant(merchant: Merchant?) {
        log.info(
            "setGiftCardMerchant called with merchant: ${merchant?.name}, denominations: ${merchant?.denominations}"
        )
        // before anything can render: merchant B must never be shown in merchant A's currency
        if (selectedProvider == GiftCardProviderType.CTX && resetCurrencyFor(merchant?.merchantId)) {
            _isFixedDenomination.value = null
            _giftCardOrderInfo.value = emptyMap()
        }
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

        _cardCurrencyState
            .map { (it as? CardCurrencyState.Known)?.code }
            .distinctUntilChanged()
            .transformLatest { currency ->
                emit(CardRateState.Loading)
                if (currency != null) {
                    emitAll(
                        exchangeRates.observeExchangeRate(currency)
                            .map { rate -> rate?.let { CardRateState.Available(it) } ?: CardRateState.Missing }
                            // caught here, so that the next merchant or currency starts a new lookup
                            .catch { e ->
                                log.error("could not read the {} rate; keeping the purchase blocked", currency, e)
                                emit(CardRateState.Missing)
                            }
                    )
                }
            }
            .onEach { _cardRateState.value = it }
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

    /** [shownCurrency] is the currency the confirmation showed the amount in. */
    suspend fun purchaseGiftCard(shownCurrency: String): List<GiftCardInfo> = withContext(Dispatchers.IO) {
        _giftCardMerchant.value?.merchantId?.let {
            ctxSpendConfig.set(CTXSpendConfig.PREFS_LAST_PURCHASE_START, System.currentTimeMillis())
            val giftCardOrderShoppingCart = GiftCardShoppingCart(
                giftCardOrderInfo.value.map {
                    GiftCardOrderItem(
                        it.key,
                        it.value
                    )
                }
            )
            val provider = giftCardProviderDao.getProviderByMerchantId(it, selectedProvider!!.name)
            when (selectedProvider) {
                GiftCardProviderType.CTX -> {
                    try {
                        val currency = cardCurrency
                        val block = purchaseBlock
                        if (currency == null || block != null || currency != shownCurrency) {
                            throw CTXSpendException(
                                "purchaseGiftCard error: blocked, $block, shown in $shownCurrency",
                                ServiceName.CTXSpend
                            )
                        }
                        ctxSpendRepository.orderGiftcard(
                            merchantId = provider!!.sourceId,
                            fiatCurrency = currency,
                            order = giftCardOrderShoppingCart,
                            cryptoCurrency = Constants.DASH_CURRENCY
                        )
                    } catch (e: CTXSpendException) {
                        log.error("purchaseGiftCard error: CTXSpendException", e)
                        throw e
                    } catch (e: Exception) {
                        log.error("purchaseGiftCard error: {}", e::class.java.simpleName, e)
                        throw CTXSpendException(
                            "purchaseGiftCard error: ${e.message}",
                            ServiceName.CTXSpend,
                            null,
                            null,
                            e
                        )
                    }
                }
                GiftCardProviderType.PiggyCards -> {
                    try {
                        piggyCardsRepository.orderGiftcard(
                            cryptoCurrency = Constants.DASH_CURRENCY,
                            merchantId = provider!!.sourceId,
                            order = giftCardOrderShoppingCart,
                            fiatCurrency = Constants.USD_CURRENCY
                        )
                    } catch (e: CTXSpendException) {
                        log.error("purchaseGiftCard error: CTXSpendException", e)
                        throw e
                    } catch (e: Exception) {
                        log.error("purchaseGiftCard error: {}", e::class.java.simpleName, e)
                        throw CTXSpendException(
                            "purchaseGiftCard error: ${e.message}",
                            ServiceName.PiggyCards,
                            null,
                            null,
                            e
                        )
                    }
                }

                null -> {
                    throw IllegalArgumentException("no giftcard provider")
                }
            }
        } ?: throw CTXSpendException("purchaseGiftCard error: no merchant")
    }

    private val _submissionState = MutableStateFlow(GiftCardSubmissionState.IDLE)

    /**
     * What the wallet has on disk about a payment that outlived the screen which sent it. The
     * in-memory state above cannot answer that: it is created fresh as IDLE every time this view
     * model is, while the merchant may still be holding a transaction from before the process
     * died. Kept as a flow rather than read once, so resolving the payment - committed once the
     * network shows it, or released once it is judged never sent - lifts the block by itself and
     * does not shut the user out of gift cards for good.
     *
     * Null until the store has actually been read. A plain false there would make "we have not
     * looked yet" indistinguishable from "nothing is pending", which is the one direction this
     * flag must never guess in: every reader below treats anything other than an explicit false
     * as a reason to refuse. A read that fails keeps it null for the same reason, and is caught
     * rather than left to reach the scope's handler.
     */
    private val unresolvedPurchaseOnDisk: StateFlow<Boolean?> = unresolvedPayments
        .observeUnresolvedGiftCardPurchase()
        .catch { e -> log.error("could not read pending payments; keeping gift card purchases blocked", e) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Survives dialog recreation because this view model is scoped to the navigation graph, not
     * to a fragment, so a dialog rebuilt after the activity is destroyed still sees that a
     * purchase is outstanding, and survives process death through the durable record above.
     *
     * An in-memory state other than IDLE is reported as it stands: it describes this session's own
     * submission, which the store has nothing to say about while it is still under way.
     */
    val submissionState: StateFlow<GiftCardSubmissionState> =
        combine(_submissionState, unresolvedPurchaseOnDisk) { state, unresolved ->
            if (state == GiftCardSubmissionState.IDLE && unresolved != false) {
                GiftCardSubmissionState.PENDING
            } else {
                state
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GiftCardSubmissionState.PENDING)

    /**
     * Starts a new purchase from a clean state. Called only when the confirmation screen is
     * genuinely created by the user, never when it is rebuilt after activity recreation, so an
     * outstanding purchase stays blocked while a later, separate order is still allowed.
     *
     * A submission that is still running is left alone: it belongs to a purchase already in
     * flight, and this view model outlives the screen that started it.
     *
     * One this session left unresolved is cleared, though, because this field is not what decides
     * whether the user may buy - [unresolvedPurchaseOnDisk] is, and it goes on saying so through
     * both [submissionState] and [tryStartSubmission] whatever is written here. Keeping the field
     * at PENDING as well would add nothing and take something away: this view model is scoped to
     * the navigation graph while the payment is not, so a transaction the verifier commits seconds
     * later, or releases after the grace period, would leave every later purchase dead until the
     * user happened to leave the graph. Clearing it here lets the block lift with the record that
     * justifies it, and not before.
     */
    fun beginNewPurchase() {
        if (_submissionState.value == GiftCardSubmissionState.IN_PROGRESS) {
            log.info("not resetting submission state, a purchase is still in progress")
            return
        }
        _submissionState.value = GiftCardSubmissionState.IDLE
    }

    /**
     * Claims the right to submit, atomically, returning false when a purchase is already under
     * way or unresolved.
     *
     * Callers must claim before creating the order at the merchant. Leaving the claim to
     * [payAndRecordOrder] let two quick taps both get past the check and each create an order,
     * because the first had not reached that point yet. Release an unused claim with
     * [releaseUnusedSubmission].
     *
     * Needs the store to have said, in so many words, that nothing is outstanding. While the first
     * read is still in flight the honest answer is that we do not know, and handing out a claim on
     * that is how the same order gets paid for twice.
     */
    fun tryStartSubmission(): Boolean =
        unresolvedPurchaseOnDisk.value == false &&
            _submissionState.compareAndSet(GiftCardSubmissionState.IDLE, GiftCardSubmissionState.IN_PROGRESS)

    /** Gives back a claim that never reached submission, so the user can try again. */
    fun releaseUnusedSubmission() {
        _submissionState.compareAndSet(GiftCardSubmissionState.IN_PROGRESS, GiftCardSubmissionState.IDLE)
    }

    /**
     * Submits the payment and records the ordered cards as one operation that neither the
     * caller's lifecycle nor process death can pull apart.
     *
     * The cards are written while the transaction is built and before anything is sent, because
     * from that moment the payment can outlive this process: [PendingDirectPayment] keeps the
     * transaction and the provider, not the order ids or redemption challenges, and those exist
     * nowhere else. If the send then definitively fails, the rows are removed again.
     *
     * @throws DuplicateGiftCardSubmissionException if a purchase is already under way or
     *   unresolved, so a recreated dialog cannot pay for the same order twice.
     * @throws PaymentSubmissionPendingException if the submission result is unknown. The state
     *   stays [GiftCardSubmissionState.PENDING], blocking any further purchase.
     */
    suspend fun payAndRecordOrder(
        paymentUri: String,
        giftCards: List<GiftCardInfo>
    ): Sha256Hash {
        // The wallet the user has just confirmed this purchase on, taken before the first
        // suspension. Everything below outlives the screen, and the invoice fetch alone can take
        // many seconds, so a wipe can finish and a funded replacement be installed while this is
        // still running; reading the wallet once the invoice arrives would pay from that one.
        val authorizedWallet = walletDataProvider.wallet
            ?: throw IllegalStateException("there is no wallet to pay for this purchase from")
        return withContext(NonCancellable) {
            payAndRecordOrderFrom(authorizedWallet, paymentUri, giftCards)
        }
    }

    private suspend fun payAndRecordOrderFrom(
        authorizedWallet: Wallet,
        paymentUri: String,
        giftCards: List<GiftCardInfo>
    ): Sha256Hash {
        // Read the durable record here rather than trust the flow above: this runs on a claim that
        // may have been taken moments after the view model was built, before the store had been
        // read once, and the in-memory state says nothing about a payment from before the process
        // died. A claim is no protection against a purchase this process never saw.
        if (unresolvedPayments.hasUnresolvedGiftCardPurchase()) {
            throw DuplicateGiftCardSubmissionException(GiftCardSubmissionState.PENDING)
        }

        // Accepts a claim already made by the caller, and claims one itself otherwise, so this
        // stays a guard of last resort for any caller that does not pre-claim.
        if (_submissionState.value != GiftCardSubmissionState.IN_PROGRESS &&
            !_submissionState.compareAndSet(GiftCardSubmissionState.IDLE, GiftCardSubmissionState.IN_PROGRESS)
        ) {
            throw DuplicateGiftCardSubmissionException(_submissionState.value)
        }

        var recordedTxId: Sha256Hash? = null
        return try {
            val txId = createSendingRequestFromDashUri(paymentUri, authorizedWallet) { newTxId ->
                saveGiftCardDummy(newTxId, giftCards)
                recordedTxId = newTxId
            }
            _submissionState.value = GiftCardSubmissionState.COMPLETED
            txId
        } catch (ex: PaymentSubmissionPendingException) {
            // The merchant may hold this payment, so the purchase stays blocked for good.
            _submissionState.value = GiftCardSubmissionState.PENDING
            throw ex
        } catch (e: Exception) {
            // Nothing was sent, or the send definitively failed, so any rows written for this
            // transaction describe an order that was never placed.
            recordedTxId?.let { txId ->
                try {
                    transactionMetadata.forgetTransaction(txId)
                } catch (ce: Exception) {
                    log.error("could not discard the order recorded for failed payment {}", txId, ce)
                }
            }
            _submissionState.value = GiftCardSubmissionState.IDLE
            throw e
        }
    }

    suspend fun createSendingRequestFromDashUri(
        paymentUri: String,
        originWallet: Wallet? = null,
        onTransactionCreated: (suspend (Sha256Hash) -> Unit)? = null
    ): Sha256Hash = withContext(Dispatchers.IO) {
        // Snapshot before suspending. The selection lives on the navigation-scoped view model and
        // stays writable, while payAndRecordOrder is NonCancellable and can outlive the screen
        // that started it. Re-reading afterwards would attribute this payment to whatever the
        // user picked in the meantime, and overwrite the attribution recovery had got right.
        //
        // The provider, not the merchant's source field: those disagree for a PiggyCards order on
        // a CTX-sourced merchant, and the provider is what GiftCardDetailsViewModel routes by.
        val provider = selectedProvider?.serviceName ?: ServiceName.CTXSpend
        val merchantIconUrl = _giftCardMerchant.value?.logoLocation

        val transaction = sendPaymentService.payWithDashUrl(
            paymentUri,
            provider,
            // The purchase screen cannot record this itself for a payment that stays unresolved:
            // marking a gift card transaction needs the transaction to be in the wallet. Carry it
            // so recovery can restore the expense category and merchant icon after committing.
            PaymentRecoveryMetadata(isGiftCardPurchase = true, merchantIconUrl = merchantIconUrl),
            originWallet = originWallet,
            onTransactionCreated = onTransactionCreated
        )
        log.info("ctx spend transaction: ${transaction.txId}")
        transactionMetadata.markGiftCardTransaction(transaction.txId, provider, merchantIconUrl)
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
        val load = if (selectedProvider == GiftCardProviderType.CTX) beginCurrencyLoad(merchant.merchantId) else null
        // previously this API call would only be made if we didn't have the min and max card values,
        // but now we need to call this every time to get an updated savings percentage and to see if
        // the merchant is enabled
        val response = try {
            getMerchant(merchant, selectedProvider!!.name)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.error("failed to get merchant ${merchant.merchantId}", ex)
            null
        }

        try {
            response?.let { apiResponse ->
                // Refresh the provider row that matches the selected provider. The cached
                // denominationsType from the explore dataset can disagree with what the
                // provider's API currently sells (e.g. a merchant switching between fixed
                // cards and a min-max range card), so it must be replaced here — the
                // purchase screen decides the Fixed/Flexible mode from this row.
                val updatedProviders = merchant.giftCardProviders.map { giftCardProvider ->
                    if (giftCardProvider.provider == selectedProvider?.name) {
                        giftCardProvider.copy(
                            savingsPercentage = apiResponse.savingsPercentage,
                            active = apiResponse.enabled,
                            denominationsType = apiResponse.denominationsType
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
                    copy.quantities = apiResponse.quantity
                    // last, so that only a response that mapped cleanly names the currency of its limits
                    load?.let { finishCurrencyLoad(it, GiftCardCurrency.normalize(apiResponse.currency)) }
                }
            }
        } catch (e: Exception) {
            log.warn("updated merchant details contains unexpected data:", e)
        }

        load?.let { finishCurrencyLoad(it, null, isAnswered = response != null) }
        return@withContext merchant
    }

    @Synchronized
    private fun resetCurrencyFor(merchantId: String?): Boolean {
        if (merchantId == currencyMerchantId) {
            return false
        }
        currencyMerchantId = merchantId
        currencyLoad++
        _cardCurrencyState.value = CardCurrencyState.Loading
        return true
    }

    /** A currency already known for the same merchant stays in place while it is refreshed. */
    @Synchronized
    private fun beginCurrencyLoad(merchantId: String?): Int {
        if (merchantId != currencyMerchantId || _cardCurrencyState.value == CardCurrencyState.Unknown) {
            _cardCurrencyState.value = CardCurrencyState.Loading
        }
        currencyMerchantId = merchantId
        return ++currencyLoad
    }

    @Synchronized
    private fun finishCurrencyLoad(load: Int, currency: String?, isAnswered: Boolean = true) {
        if (load != currencyLoad) {
            log.info("ignoring a superseded merchant load")
            return
        }
        val previous = _cardCurrencyState.value
        if (!isAnswered && previous is CardCurrencyState.Known) {
            // a refresh that got no answer says nothing new about a currency CTX already confirmed
            return
        }
        val current = currency?.let { CardCurrencyState.Known(it) } ?: CardCurrencyState.Unknown
        if (previous is CardCurrencyState.Known && previous != current) {
            // amounts chosen in the old currency must not be ordered in the new one
            _giftCardOrderInfo.value = emptyMap()
        }
        _cardCurrencyState.value = current
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
                    savingsPercentage = if (this.first.isNotEmpty()) {
                        this.first.maxOf { it.savingsPercentage }
                    } else {
                        0
                    },
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
            // check for PiggyCards test cards
            if (SUPPORT_PIGGY_CARDS_TEST_MERCHANT && it.provider == GiftCardProviderType.PiggyCards.name) {
                val sourceId = merchant.sourceId!!
                // if (sourceId == PiggyCardsConstants.PIGGY_CARDS_TEST_BRAND_ID) {
                PiggyCardsTestMerchants.ALL.find { it.merchantId == merchant.merchantId }?.let {
                    return (piggyCardsRepository as PiggyCardsRepository).getMerchant(
                        sourceId,
                        DenominationType.fromString(it.providerDenominationsType)
                    )
                }
            }
            providers[GiftCardProviderType.fromProviderName(it.provider)]?.getMerchant(
                giftCardProvider.sourceId
            )
        }
    }

    private suspend fun getMerchants(merchant: Merchant): Pair<List<UpdatedMerchantDetails>, List<GiftCardProvider>> {
        val providers = giftCardProviderDao.getProvidersByMerchantId(merchant.merchantId!!)
        val merchantResponseList = arrayListOf<UpdatedMerchantDetails>()
        val providerResponseList = arrayListOf<GiftCardProvider>()
        providers.forEach { provider ->
            val details: UpdatedMerchantDetails? = when (provider.provider) {
                "CTX" -> {
                    if (ctxSpendRepository.isUserSignedIn()) {
                        ctxSpendRepository.getMerchant(provider.sourceId)
                    } else {
                        null
                    }
                }

                "PiggyCards" -> {
                    if (piggyCardsRepository.isUserSignedIn()) {
                        if (SUPPORT_PIGGY_CARDS_TEST_MERCHANT) {
                            PiggyCardsTestMerchants.ALL.find { it.merchantId == merchant.merchantId }?.let { testData ->
                                (piggyCardsRepository as PiggyCardsRepository).getMerchant(
                                    provider.sourceId,
                                    DenominationType.fromString(testData.providerDenominationsType)
                                )
                            }
                        } else {
                            piggyCardsRepository.getMerchant(provider.sourceId)
                        }
                    } else {
                        null
                    }
                }

                else -> null
            }

            if (details != null) {
                merchantResponseList.add(details)
                providerResponseList.add(
                    provider.copy(
                        savingsPercentage = details.savingsPercentage,
                        active = details.enabled,
                        // The explore dataset's cached type goes stale when a merchant switches
                        // between fixed cards and a range card. ItemDetails picks the range or
                        // fixed subtitle from this row, so leaving it cached would disagree with
                        // the purchase screen, which resolves the mode from the same live data.
                        denominationsType = details.denominationsType
                    )
                )
            } else {
                providerResponseList.add(provider)
            }
        }
        return Pair(merchantResponseList, providerResponseList)
    }

    fun refreshMinMaxCardPurchaseValues() {
        _giftCardMerchant.value?.let { merchant ->
            val currency = cardCurrency
            if (currency == null) {
                minCardPurchaseCoin = Coin.ZERO
                maxCardPurchaseCoin = Coin.ZERO
                return
            }
            val minCardPurchase = merchant.minCardPurchase ?: 0.0
            val maximumCardPurchase = merchant.maxCardPurchase ?: 0.0
            minCardPurchaseFiat = GiftCardCurrency.toFiat(minCardPurchase, currency)
            maxCardPurchaseFiat = GiftCardCurrency.toFiat(maximumCardPurchase, currency)
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
                purchaseAmount.currencyCode == minCardPurchaseFiat.currencyCode &&
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

    /**
     * Records the ordered cards against [txId]. Suspends until the rows are written: the caller
     * may be about to dismiss this screen, and on the payment-pending path nothing else holds the
     * order details, so losing the write would strand the purchase with no way back to the order.
     */
    suspend fun saveGiftCardDummy(txId: Sha256Hash, giftCards: List<GiftCardInfo>) {
        log.info("saving {} dummy gift cards: {}", giftCards.size, txId)
        var index = 0
        val giftCard = giftCards.map {
            GiftCard(
                txId = txId,
                merchantName = _giftCardMerchant.value?.name ?: "",
                price = it.fiatAmount?.toDouble() ?: 0.0,
                currency = GiftCardCurrency.normalize(it.fiatCurrency),
                merchantUrl = it.redeemUrl,
                redeemUrlChallenge = it.redeemUrlChallenge,
                note = it.id,
                index = index++
            )
        }
        giftCardDao.insertGiftCards(giftCard)
    }

    fun setIsFixedDenomination(isFixed: Boolean?) {
        val previous = _isFixedDenomination.value
        val isModeChange = previous != null && isFixed != null && previous != isFixed
        if (selectedProvider == GiftCardProviderType.CTX && isModeChange) {
            // an amount typed for a range is not a card the merchant sells once it only has fixed ones
            _giftCardOrderInfo.value = emptyMap()
        }
        _isFixedDenomination.value = isFixed
    }

    fun setIsFixedDenominationMultiple(isMultiple: Boolean?) {
        _isFixedDenominationMultiple.value = isMultiple
    }

    fun setGiftCardOrderInfo(fiat: Fiat, quantity: Int) {
        _giftCardOrderInfo.value = mapOf(fiat.toBigDecimal().toDouble() to quantity)
    }

    /** Takes an amount typed for a single card. One in any other currency than the card's is dropped. */
    fun setEnteredAmount(fiat: Fiat): Boolean {
        if (fiat.currencyCode != cardCurrency) {
            _giftCardOrderInfo.value = emptyMap()
            return false
        }
        setGiftCardOrderInfo(fiat, getFirstCardQuantity())
        return true
    }

    fun setGiftCardOrderQuantities(quantities: Map<Double, Int>) {
        _giftCardOrderInfo.value = quantities
    }

    fun resetSelectedDenomination() {
        _giftCardOrderInfo.value = emptyMap()
    }

    fun clearOrderSelection() {
        _giftCardOrderInfo.value = emptyMap()
        _isFixedDenominationMultiple.value = null
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    suspend fun checkToken(): Boolean {
        return try {
            if (!ctxSpendRepository.isUserSignedIn()) {
                true
            } else {
                (ctxSpendRepository as CTXSpendRepository).checkToken()
            }
        } catch (ex: Exception) {
            false
        }
    }

    private fun getSupportEmail(sendToService: Boolean, serviceName: String) = when {
        sendToService && serviceName == ServiceName.CTXSpend -> arrayOf(
            CTXSpendConstants.REPORT_EMAIL,
            "support@dash.org"
        )
        sendToService && serviceName == ServiceName.PiggyCards -> arrayOf(
            PiggyCardsConstants.REPORT_EMAIL,
            "support@dash.org"
        )
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
                .append("quantities: ").append(merchant.quantities).append("\n")
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
        report.append("amount(s) entered: ").append(giftCardOrderInfo.value.keys.joinToString(", ")).append("\n")
        report.append("currency: ").append(cardCurrency ?: "unknown").append("\n")
        report.append("quantity: ").append(giftCardOrderInfo.value.values.joinToString(", ")).append("\n")
        report.append("order: ").append(ex?.orderId).append("\n")
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
        val merchantId = giftCardMerchant.value?.giftCardProviders?.find {
            it.provider == selectedProvider?.name
        }?.sourceId
        return merchantId?.let {
            when (selectedProvider) {
                GiftCardProviderType.CTX -> ctxSpendRepository.getGiftCardDiscount(merchantId, denomination)
                GiftCardProviderType.PiggyCards -> piggyCardsRepository.getGiftCardDiscount(merchantId, denomination)
                else -> 0.0
            }
        } ?: 0.0
    }

    private fun updatePurchaseLimits() {
        if (selectedProvider != GiftCardProviderType.CTX) {
            _exchangeRate.value?.let {
                val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
                minCardPurchaseCoin = myRate.fiatToCoin(minCardPurchaseFiat)
                maxCardPurchaseCoin = myRate.fiatToCoin(maxCardPurchaseFiat)
            }
            return
        }
        val rate = cardExchangeRate
        minCardPurchaseCoin = rate?.fiatToCoin(minCardPurchaseFiat) ?: Coin.ZERO
        maxCardPurchaseCoin = rate?.fiatToCoin(maxCardPurchaseFiat) ?: Coin.ZERO
    }

    /** True when the wallet cannot cover the selected card, or cannot tell because there is no rate. */
    fun exceedsBalance(): Boolean {
        val rate = cardExchangeRate ?: return true
        val paymentValue = getFirstCardValueAsFiat() ?: return true
        val balanceWithDiscount = balanceWithDiscount ?: return false
        return rate.fiatToCoin(paymentValue).isGreaterThan(balanceWithDiscount)
    }

    fun logError(ctxSpendException: Throwable, message: String) {
        analytics.logError(ctxSpendException, message)
    }

    /** The first card's value in the card currency, or null while that currency is unknown. */
    fun getFirstCardValueAsFiat(): Fiat? {
        val currency = cardCurrency ?: return null
        val firstCardValue = giftCardOrderInfo.value.keys.firstOrNull() ?: 0.0
        return GiftCardCurrency.toFiat(firstCardValue, currency)
    }

    fun getFirstCardQuantity(): Int {
        return giftCardOrderInfo.value.values.firstOrNull() ?: 1
    }
}
