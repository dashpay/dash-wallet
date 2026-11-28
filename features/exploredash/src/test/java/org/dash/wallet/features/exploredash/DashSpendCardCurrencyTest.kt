/*
 * Copyright 2026 Dash Core Group.
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

package org.dash.wallet.features.exploredash

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.FiatValue
import org.dash.wallet.common.money.toDash
import org.dash.wallet.common.money.toFiatValue
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.UnresolvedPaymentsProvider
import org.dash.wallet.common.services.WalletSession
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.ui.enter_amount.AmountInputRules
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GetMerchantResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.data.dashspend.model.UpdatedMerchantDetails
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.network.authenticator.TokenAuthenticator
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepositoryFactory
import org.dash.wallet.features.exploredash.repository.PiggyCardsRepository
import org.dash.wallet.features.exploredash.ui.dashspend.CardCurrencyState
import org.dash.wallet.features.exploredash.ui.dashspend.CardRateState
import org.dash.wallet.features.exploredash.ui.dashspend.DashSpendViewModel
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardPriceChange
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardPurchaseBlock
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardQuote
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardShoppingCart
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardSubmissionState
import org.dash.wallet.features.exploredash.ui.dashspend.PaidOrder
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.dash.wallet.features.exploredash.utils.GiftCardCurrency
import org.dash.wallet.features.exploredash.utils.GiftCardPaymentGuard
import org.dash.wallet.features.exploredash.utils.PiggyCardsTestMerchants
import org.dash.wallet.features.exploredash.utils.toCardFormattedString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/**
 * A CTX card is priced in its merchant's currency and paid for in Dash. These cover the money
 * rules of that: the order carries the merchant's currency and an exact, locale-proof amount, and
 * nothing is bought while the currency or its Dash rate is unknown.
 */
@ExperimentalCoroutinesApi
class DashSpendCardCurrencyTest {
    @get:Rule var rule: TestRule = InstantTaskExecutorRule()

    @get:Rule var mainCoroutineRule = MainCoroutineRule()

    private val merchantId = "merchant-1"
    private val ctxRow = GiftCardProvider(
        id = 1,
        merchantId = merchantId,
        provider = GiftCardProviderType.CTX.name,
        redeemType = "barcode",
        savingsPercentage = 500,
        active = true,
        denominationsType = "min-max",
        sourceId = "ctx-source"
    )
    private val otherMerchantId = "merchant-2"
    private val otherCtxRow = ctxRow.copy(id = 3, merchantId = otherMerchantId, sourceId = "ctx-source-2")
    private val piggyCardsRow = ctxRow.copy(
        id = 2,
        provider = GiftCardProviderType.PiggyCards.name,
        sourceId = "piggycards-source"
    )

    private val originalLocale = Locale.getDefault()
    private val originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
    private val originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
    private val rates = mutableMapOf<String, MutableStateFlow<ExchangeRate?>>()
    private val rateLookups = mutableMapOf<String, Flow<ExchangeRate?>>()
    private val rateLookupsStarted = mutableListOf<String>()
    private val api = mock<CTXSpendApi>()
    private val piggyCards = mock<PiggyCardsRepository>()
    private val giftCardDao = mock<GiftCardDao>()
    private val payments = mock<SendPaymentService>()
    private var signedInToCtx = false
    private val sentPayments = mutableListOf<Dash?>()
    private val zeroTxId = "00".repeat(32)
    private val freshRates = mutableMapOf<String, ExchangeRate>()
    private val currenciesWithoutAFreshRate = mutableSetOf<String>()
    private var elapsedMillis = 0L

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale)
        Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale)
    }

    private fun merchant() = Merchant(paymentMethod = "gift card").apply {
        id = 1
        this.merchantId = this@DashSpendCardCurrencyTest.merchantId
        name = "Acme"
        active = true
        giftCardProviders = listOf(ctxRow, piggyCardsRow)
    }

    private fun otherMerchant() = merchant().apply {
        id = 2
        merchantId = otherMerchantId
        name = "Other"
        giftCardProviders = listOf(otherCtxRow)
    }

    private fun merchantResponse(sourceId: String, currency: String?, discount: Int = 0) = GetMerchantResponse(
        id = sourceId,
        denominations = listOf("5", "500"),
        denominationsType = "min-max",
        userDiscount = discount,
        currency = currency
    )

    // like the Room query behind it: the current row at once, null while there is none, then changes
    private fun rateFlow(currency: String) = rates.getOrPut(currency) { MutableStateFlow(null) }

    /** One Dash is worth [dashPrice] of [currency]. */
    private fun setRate(currency: String, dashPrice: String) {
        rateFlow(currency).value = ExchangeRate(currency, dashPrice)
    }

    private fun viewModel(): DashSpendViewModel {
        val config = mock<CTXSpendConfig> {
            on { observeSecureData(any()) } doReturn emptyFlow()
            onBlocking { getSecuredData(eq(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN)) } doAnswer {
                "token".takeIf { signedInToCtx }
            }
        }
        val ctx = CTXSpendRepository(api, config, TokenAuthenticator(mock(), config))
        val providerDao = mock<GiftCardProviderDao> {
            onBlocking { getProvidersByMerchantId(any()) } doReturn listOf(ctxRow, piggyCardsRow)
            onBlocking { getProviderByMerchantId(eq(merchantId), eq(GiftCardProviderType.CTX.name)) } doReturn ctxRow
            onBlocking {
                getProviderByMerchantId(eq(otherMerchantId), eq(GiftCardProviderType.CTX.name))
            } doReturn otherCtxRow
            onBlocking {
                getProviderByMerchantId(eq(merchantId), eq(GiftCardProviderType.PiggyCards.name))
            } doReturn piggyCardsRow
        }
        val repositoryFactory = mock<DashSpendRepositoryFactory> {
            on { create(eq(GiftCardProviderType.CTX)) } doReturn ctx
            on { create(eq(GiftCardProviderType.PiggyCards)) } doReturn piggyCards
        }
        val exchangeRates = mock<ExchangeRatesProvider> {
            onBlocking { getFreshExchangeRate(any(), any()) } doAnswer {
                val currency = it.getArgument<String>(0)
                (freshRates[currency] ?: rateFlow(currency).value)
                    .takeUnless { currency in currenciesWithoutAFreshRate }
            }
            on { observeExchangeRate(any()) } doAnswer {
                val currency = it.getArgument<String>(0)
                rateLookupsStarted += currency
                rateLookups[currency] ?: rateFlow(currency)
            }
        }

        return DashSpendViewModel(
            mock<WalletDataProvider> {
                on { observeTotalBalance() } doReturn flowOf(Dash.COIN)
            },
            exchangeRates,
            mock<Configuration>(),
            payments,
            repositoryFactory,
            mock<TransactionMetadataProvider>(),
            giftCardDao,
            providerDao,
            mock<NetworkStateInt> { on { isConnected } doReturn MutableStateFlow(true) },
            mock<AnalyticsService>(),
            SavedStateHandle(),
            mock<MerchantDao>(),
            mock<CTXSpendConfig>(),
            mock<BlockchainStateProvider> { on { observeState() } doReturn emptyFlow() },
            mock<UnresolvedPaymentsProvider> {
                on { observeUnresolvedGiftCardPurchase() } doReturn flowOf(false)
                onBlocking { hasUnresolvedGiftCardPurchase() } doReturn false
            }
        )
    }

    private fun ctxSells(
        currency: String?,
        denominations: List<String>,
        denominationsType: String = "min-max",
        discount: Int = 500
    ) {
        api.stub {
            onBlocking { getMerchant(eq("ctx-source")) } doReturn GetMerchantResponse(
                id = "ctx-source",
                denominations = denominations,
                denominationsType = denominationsType,
                userDiscount = discount,
                currency = currency
            )
            onBlocking { purchaseGiftCard(any()) } doAnswer {
                val request = it.getArgument<PurchaseGiftCardRequest>(0)
                GiftCardResponse(
                    id = "order-1",
                    status = "unpaid",
                    fiatAmount = request.fiatAmount,
                    fiatCurrency = request.fiatCurrency
                )
            }
        }
    }

    /** What the purchase screen does when it opens on a CTX merchant. */
    private suspend fun DashSpendViewModel.openCtxMerchant(merchant: Merchant = merchant()): Merchant {
        selectedProvider = GiftCardProviderType.CTX
        setGiftCardMerchant(merchant)
        val updated = updateMerchantDetails(merchant)
        setGiftCardMerchant(updated)
        refreshMinMaxCardPurchaseValues()
        return updated
    }

    /** Confirm on a sheet that shows the currency the card is in right now. */
    private suspend fun DashSpendViewModel.buy() =
        purchaseGiftCard(currentQuote() ?: GiftCardQuote("USD", giftCardOrderInfo.value, BigDecimal.ZERO))

    private fun DashSpendViewModel.enter(amount: String) {
        setGiftCardOrderInfo(FiatValue.parseFiat(cardCurrency!!, amount), 1)
    }

    private fun orderSentToCtx(): PurchaseGiftCardRequest {
        val request = argumentCaptor<PurchaseGiftCardRequest>()
        verifyBlocking(api) { purchaseGiftCard(request.capture()) }
        return request.firstValue
    }

    private fun assertPurchaseRefused(viewModel: DashSpendViewModel) {
        assertThrows(CTXSpendException::class.java) { runBlocking { viewModel.buy() } }
        verifyBlocking(api, never()) { purchaseGiftCard(any()) }
    }

    private fun digits(text: String) = text.filter(Char::isDigit).map { Character.digit(it, 10) }.joinToString("")

    @Test
    fun gbpMerchant_limitsAndOrderAreInPounds() = runBlocking {
        ctxSells("GBP", listOf("5", "500.50"))
        setRate("GBP", "20")
        setRate("USD", "40")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals("GBP", viewModel.cardCurrency)
        assertEquals(FiatValue.parseFiat("GBP", "5"), viewModel.minCardPurchaseFiat)
        assertEquals(FiatValue.parseFiat("GBP", "500.50"), viewModel.maxCardPurchaseFiat)
        // limits in Dash come from the GBP rate, not the USD one
        assertEquals(Dash.parse("0.25"), viewModel.minCardPurchaseDash)
        assertEquals(Dash.parse("25.025"), viewModel.maxCardPurchaseDash)
        assertEquals("50050", digits(viewModel.maxCardPurchaseFiat.toCardFormattedString()))
        assertTrue(viewModel.maxCardPurchaseFiat.toCardFormattedString().contains("£"))

        viewModel.enter("19.99")
        assertEquals(FiatValue.parseFiat("GBP", "19.99"), viewModel.getFirstCardValueAsFiat())
        assertNull(viewModel.purchaseBlock)
        assertFalse(viewModel.exceedsBalance())

        viewModel.buy()

        val order = orderSentToCtx()
        assertEquals("GBP", order.fiatCurrency)
        assertEquals("19.99", order.fiatAmount)
        assertEquals("ctx-source", order.merchantId)
    }

    @Test
    fun jpyMerchant_hasNoDecimals() = runBlocking {
        ctxSells("JPY", listOf("1000", "5000", "10000"), denominationsType = "fixed")
        setRate("JPY", "5000")
        val viewModel = viewModel()

        val merchant = viewModel.openCtxMerchant()

        assertEquals(listOf(1000.0, 5000.0, 10000.0), merchant.denominations)
        assertEquals("¥1,000", GiftCardCurrency.displayFormat("JPY", trimZeroDecimals = true).format(1000.0))
        assertEquals("¥1,000", GiftCardCurrency.displayFormat("JPY").format(1000.0))
        assertEquals(FiatValue.parseFiat("JPY", "10000"), viewModel.maxCardPurchaseFiat)
        assertEquals("10000", digits(viewModel.maxCardPurchaseFiat.toCardFormattedString()))

        viewModel.setGiftCardOrderInfo(GiftCardCurrency.toFiat(merchant.denominations[1], "JPY").toFiatValue(), 1)
        assertNull(viewModel.purchaseBlock)
        viewModel.buy()

        val order = orderSentToCtx()
        assertEquals("JPY", order.fiatCurrency)
        assertEquals("5000", order.fiatAmount)
    }

    @Test
    fun jpyMerchant_fractionalAmountIsRefusedNotRounded() = runBlocking {
        ctxSells("JPY", listOf("500", "50000"))
        setRate("JPY", "5000")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()

        viewModel.enter("1000.5")

        assertEquals(GiftCardPurchaseBlock.AMOUNT_NOT_REPRESENTABLE, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)
    }

    @Test
    fun kwdMerchant_keepsThreeDecimals() = runBlocking {
        ctxSells("KWD", listOf("1.500", "150.125"))
        setRate("KWD", "8")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals(FiatValue.parseFiat("KWD", "1.5"), viewModel.minCardPurchaseFiat)
        assertEquals(FiatValue.parseFiat("KWD", "150.125"), viewModel.maxCardPurchaseFiat)
        assertEquals("150125", digits(viewModel.maxCardPurchaseFiat.toCardFormattedString()))
        assertEquals("1500", digits(GiftCardCurrency.displayFormat("KWD").format(1.5)))

        viewModel.enter("12.345")
        assertEquals(FiatValue.parseFiat("KWD", "12.345"), viewModel.getFirstCardValueAsFiat())
        assertNull(viewModel.purchaseBlock)
        viewModel.buy()

        val order = orderSentToCtx()
        assertEquals("KWD", order.fiatCurrency)
        assertEquals("12.345", order.fiatAmount)
    }

    @Test
    fun orderAmount_isPlainAsciiUnderGermanAndArabicLocales() {
        val locales = listOf(Locale.GERMANY, Locale.forLanguageTag("ar-EG-u-nu-arab"))
        val cases = listOf(
            Triple("USD", "25", "25.00"),
            Triple("USD", "1250.5", "1250.50"),
            Triple("GBP", "2500", "2500.00"),
            Triple("JPY", "125000", "125000"),
            Triple("KWD", "1000.125", "1000.125")
        )

        locales.forEach { locale ->
            cases.forEach { (currency, entered, expected) ->
                Locale.setDefault(locale)
                reset(api)
                ctxSells(currency, listOf("1", "500000"))
                setRate(currency, "1000000")
                val viewModel = viewModel()

                runBlocking {
                    viewModel.openCtxMerchant()
                    viewModel.enter(entered)
                    viewModel.buy()
                }

                val order = orderSentToCtx()
                assertEquals("$entered $currency in $locale", expected, order.fiatAmount)
                assertEquals(currency, order.fiatCurrency)
            }
        }
    }

    @Test
    fun missingRateForTheCardCurrency_blocksThePurchase() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        // a USD rate is no use for a GBP card
        setRate("USD", "25")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("25")

        assertEquals(GiftCardPurchaseBlock.RATE_MISSING, viewModel.purchaseBlock)
        assertNull(viewModel.cardExchangeRate)
        assertTrue("an unknown Dash cost must not pass the balance check", viewModel.exceedsBalance())
        assertEquals(Dash.ZERO, viewModel.maxCardPurchaseDash)
        assertPurchaseRefused(viewModel)

        setRate("GBP", "50")
        assertNull(viewModel.purchaseBlock)
        assertFalse(viewModel.exceedsBalance())
    }

    @Test
    fun merchantWithoutACurrency_blocksThePurchase() {
        listOf(null, "", "   ", "POUNDS").forEach { reported ->
            reset(api)
            ctxSells(reported, listOf("5", "500"))
            setRate("USD", "25")
            val viewModel = viewModel()

            runBlocking { viewModel.openCtxMerchant() }
            viewModel.setGiftCardOrderQuantities(mapOf(25.0 to 1))

            assertNull("'$reported'", viewModel.cardCurrency)
            assertEquals(GiftCardPurchaseBlock.CURRENCY_UNKNOWN, viewModel.purchaseBlock)
            assertNull(viewModel.getFirstCardValueAsFiat())
            assertTrue(viewModel.exceedsBalance())
            assertPurchaseRefused(viewModel)
        }
    }

    @Test
    fun beforeTheMerchantAnswers_thePurchaseIsBlocked() {
        ctxSells("USD", listOf("5", "500"))
        setRate("USD", "25")
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.CTX
        viewModel.setGiftCardMerchant(merchant())
        viewModel.setGiftCardOrderQuantities(mapOf(25.0 to 1))

        assertNull(viewModel.cardCurrency)
        assertEquals(GiftCardPurchaseBlock.CURRENCY_LOADING, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)
    }

    @Test
    fun failedRefreshOfTheSameMerchant_keepsTheConfirmedCurrencyAndTheOrder() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        setRate("USD", "25")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        viewModel.enter("25")

        api.stub { onBlocking { getMerchant(any()) } doAnswer { throw IOException("offline") } }
        viewModel.updateMerchantDetails(merchant)

        assertEquals(CardCurrencyState.Known("GBP"), viewModel.cardCurrencyState.value)
        assertEquals(mapOf(25.0 to 1), viewModel.giftCardOrderInfo.value)
        assertNull(viewModel.purchaseBlock)
    }

    @Test
    fun refreshAnsweredWithoutACurrency_blocksAMerchantThatHadOne() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        viewModel.enter("25")

        reset(api)
        ctxSells("  ", listOf("5", "500"))
        viewModel.updateMerchantDetails(merchant)

        assertEquals(CardCurrencyState.Unknown, viewModel.cardCurrencyState.value)
        assertTrue(viewModel.giftCardOrderInfo.value.isEmpty())
        assertPurchaseRefused(viewModel)
    }

    @Test
    fun refreshAnsweredWithLimitsThatCannotBeMapped_blocksAMerchantThatHadACurrency() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()

        reset(api)
        ctxSells("GBP", listOf("5", "50", "500"))
        viewModel.updateMerchantDetails(merchant)

        assertEquals(CardCurrencyState.Unknown, viewModel.cardCurrencyState.value)
    }

    @Test
    fun firstLoadThatFails_leavesTheCurrencyUnknown() = runBlocking {
        api.stub { onBlocking { getMerchant(any()) } doAnswer { throw IOException("offline") } }
        setRate("USD", "25")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()
        viewModel.setGiftCardOrderQuantities(mapOf(25.0 to 1))

        assertNull(viewModel.cardCurrency)
        assertEquals(GiftCardPurchaseBlock.CURRENCY_UNKNOWN, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)
    }

    @Test
    fun usdMerchant_behavesAsBefore() = runBlocking {
        ctxSells("USD", listOf("5", "500"))
        setRate("USD", "25")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals("USD", viewModel.cardCurrency)
        assertEquals(FiatValue.parseFiat("USD", "5"), viewModel.minCardPurchaseFiat)
        assertEquals(FiatValue.parseFiat("USD", "500"), viewModel.maxCardPurchaseFiat)
        assertEquals(Dash.parse("0.2"), viewModel.minCardPurchaseDash)
        assertEquals(Dash.parse("20"), viewModel.maxCardPurchaseDash)
        assertEquals("500", digits(viewModel.minCardPurchaseFiat.toCardFormattedString()))
        assertEquals("$25.00", GiftCardCurrency.displayFormat("USD").format(25.0))
        assertEquals("$25", GiftCardCurrency.displayFormat("USD", trimZeroDecimals = true).format(25.0))

        viewModel.enter("25")
        assertEquals(FiatValue.parseFiat("USD", "25"), viewModel.getFirstCardValueAsFiat())
        assertTrue(viewModel.withinLimits(FiatValue.parseFiat("USD", "25")))
        assertFalse(viewModel.withinLimits(FiatValue.parseFiat("USD", "501")))
        assertNull(viewModel.purchaseBlock)
        // 25 USD is 1 DASH, which the 1 DASH balance covers once the 5% discount is applied
        assertFalse(viewModel.exceedsBalance())

        viewModel.buy()

        val order = orderSentToCtx()
        assertEquals("USD", order.fiatCurrency)
        assertEquals("25.00", order.fiatAmount)
        assertEquals("DASH", order.cryptoCurrency)

        viewModel.enter("400")
        assertTrue(viewModel.exceedsBalance())
    }

    @Test
    fun piggyCards_staysUsdAndIsNeverBlockedByTheCardCurrencyRules() = runBlocking {
        piggyCards.stub { onBlocking { orderGiftcard(any(), any(), any(), any()) } doReturn emptyList() }
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.PiggyCards
        viewModel.setGiftCardMerchant(merchant())
        viewModel.setGiftCardOrderQuantities(mapOf(25.0 to 1))

        assertEquals("USD", viewModel.cardCurrency)
        assertNull(viewModel.purchaseBlock)

        viewModel.buy()

        verifyBlocking(piggyCards) { orderGiftcard(eq("DASH"), eq("USD"), any(), eq("piggycards-source")) }
    }

    @Test
    fun savedCard_keepsTheCurrencyOfTheOrderNotOfTheScreen() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        // CTX names the card's currency but not the payment's, so the one ordered is what gets recorded
        api.stub {
            onBlocking { purchaseGiftCard(any()) } doReturn GiftCardResponse(
                id = "order-1",
                status = "unpaid",
                fiatAmount = "23.75",
                fiatCurrency = "",
                cardFiatCurrency = "GBP"
            )
        }
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("25")

        val ordered = viewModel.buy()
        assertEquals("GBP", ordered.single().fiatCurrency)

        // the user moves on while the payment is still being recorded
        viewModel.setGiftCardMerchant(otherMerchant())
        viewModel.saveGiftCardDummy(zeroTxId, ordered)

        val saved = argumentCaptor<List<GiftCard>>()
        verifyBlocking(giftCardDao) { insertGiftCards(saved.capture()) }
        assertEquals("GBP", saved.firstValue.single().currency)
        assertEquals(23.75, saved.firstValue.single().price, 0.0)
    }

    @Test
    fun orderAnsweredInAnotherCurrency_isNotPaid() {
        val answers = listOf(
            GiftCardResponse(id = "order-1", status = "unpaid", fiatCurrency = "USD"),
            GiftCardResponse(id = "order-1", status = "unpaid", fiatCurrency = "GBP", cardFiatCurrency = "USD")
        )

        answers.forEach { answer ->
            reset(api)
            ctxSells("GBP", listOf("5", "500"))
            api.stub { onBlocking { purchaseGiftCard(any()) } doReturn answer }
            setRate("GBP", "20")
            val viewModel = viewModel()
            runBlocking { viewModel.openCtxMerchant() }
            viewModel.enter("25")

            // nothing comes back, so the dialog never gets a payment URI to pay
            val refusal = assertThrows(CTXSpendException::class.java) {
                runBlocking { viewModel.buy() }
            }
            assertEquals(R.string.gift_card_error, refusal.resourceString?.resourceId)
        }
    }

    @Test
    fun currencyState_goesFromLoadingToKnown() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.CTX
        viewModel.setGiftCardMerchant(merchant())
        assertEquals(CardCurrencyState.Loading, viewModel.cardCurrencyState.value)

        viewModel.updateMerchantDetails(merchant())

        assertEquals(CardCurrencyState.Known("GBP"), viewModel.cardCurrencyState.value)
    }

    @Test
    fun currencyState_recoversFromUnknownOnRetry() = runBlocking {
        api.stub { onBlocking { getMerchant(any()) } doAnswer { throw IOException("offline") } }
        setRate("GBP", "20")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()
        assertEquals(CardCurrencyState.Unknown, viewModel.cardCurrencyState.value)

        val duringRetry = mutableListOf<CardCurrencyState>()
        api.stub {
            onBlocking { getMerchant(any()) } doAnswer {
                duringRetry += viewModel.cardCurrencyState.value
                merchantResponse("ctx-source", "GBP")
            }
        }
        viewModel.updateMerchantDetails(merchant())

        assertEquals(listOf<CardCurrencyState>(CardCurrencyState.Loading), duringRetry)
        assertEquals(CardCurrencyState.Known("GBP"), viewModel.cardCurrencyState.value)
        viewModel.enter("25")
        assertNull(viewModel.purchaseBlock)
    }

    @Test
    fun currencyState_staysKnownWhileTheSameMerchantIsRefreshed() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        val viewModel = viewModel()
        viewModel.openCtxMerchant()

        val duringRefresh = mutableListOf<CardCurrencyState>()
        api.stub {
            onBlocking { getMerchant(any()) } doAnswer {
                duringRefresh += viewModel.cardCurrencyState.value
                merchantResponse("ctx-source", "GBP")
            }
        }
        // what a rotation does: the screen sets the same merchant up again
        viewModel.openCtxMerchant()

        assertEquals(listOf<CardCurrencyState>(CardCurrencyState.Known("GBP")), duringRefresh)
    }

    @Test
    fun switchingMerchant_resetsEverythingThatBelongedToTheLastOne() = runBlocking {
        ctxSells("GBP", listOf("10", "20"), denominationsType = "fixed")
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.setIsFixedDenomination(true)
        viewModel.setGiftCardOrderQuantities(mapOf(20.0 to 1))

        viewModel.setGiftCardMerchant(otherMerchant())

        assertEquals(CardCurrencyState.Loading, viewModel.cardCurrencyState.value)
        assertNull(viewModel.cardCurrency)
        assertNull(viewModel.isFixedDenomination.value)
        assertTrue(viewModel.giftCardOrderInfo.value.isEmpty())
        assertNull(viewModel.cardExchangeRate)
        assertEquals(GiftCardPurchaseBlock.CURRENCY_LOADING, viewModel.purchaseBlock)
    }

    @Test
    fun supersededMerchantLoad_cannotOverwriteANewerCurrency() = runBlocking {
        val firstLoadStarted = CountDownLatch(1)
        val releaseFirstLoad = CountDownLatch(1)
        api.stub {
            onBlocking { getMerchant(eq("ctx-source")) } doAnswer {
                firstLoadStarted.countDown()
                check(releaseFirstLoad.await(5, TimeUnit.SECONDS))
                merchantResponse("ctx-source", "GBP")
            }
            onBlocking { getMerchant(eq("ctx-source-2")) } doReturn merchantResponse("ctx-source-2", "EUR")
        }
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.CTX
        viewModel.setGiftCardMerchant(merchant())
        val firstLoad = async(Dispatchers.Default) { viewModel.updateMerchantDetails(merchant()) }
        try {
            assertTrue(firstLoadStarted.await(5, TimeUnit.SECONDS))

            viewModel.openCtxMerchant(otherMerchant())
            assertEquals(CardCurrencyState.Known("EUR"), viewModel.cardCurrencyState.value)
        } finally {
            releaseFirstLoad.countDown()
        }
        firstLoad.await()

        assertEquals(CardCurrencyState.Known("EUR"), viewModel.cardCurrencyState.value)
    }

    @Test
    fun merchantAnswerThatCannotBeMapped_leavesTheCurrencyUnknown() = runBlocking {
        // a range needs exactly two values, so the limits of this answer cannot be trusted
        ctxSells("GBP", listOf("5", "50", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals(CardCurrencyState.Unknown, viewModel.cardCurrencyState.value)
        assertEquals(GiftCardPurchaseBlock.CURRENCY_UNKNOWN, viewModel.purchaseBlock)
    }

    @Test
    fun amountTypedInAnotherCurrency_isDroppedNotRelabelled() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()

        assertTrue(viewModel.setEnteredAmount(FiatValue.parseFiat("GBP", "25")))
        assertEquals(FiatValue.parseFiat("GBP", "25"), viewModel.getFirstCardValueAsFiat())

        assertFalse(viewModel.setEnteredAmount(FiatValue.parseFiat("USD", "40")))

        assertTrue(viewModel.giftCardOrderInfo.value.isEmpty())
        assertEquals(FiatValue.parseFiat("GBP", "0"), viewModel.getFirstCardValueAsFiat())
    }

    @Test
    fun rateStillBeingLookedUp_isNotReportedAsMissing() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        rateLookups["GBP"] = flow { awaitCancellation() }
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals(CardRateState.Loading, viewModel.cardRateState.value)
        assertEquals(GiftCardPurchaseBlock.RATE_LOADING, viewModel.purchaseBlock)
        viewModel.enter("25")
        assertPurchaseRefused(viewModel)
    }

    @Test
    fun rateLookedUpAndAbsent_isReportedBeforeAnyAmountIsEntered() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("USD", "25")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals(CardRateState.Missing, viewModel.cardRateState.value)
        assertTrue(viewModel.giftCardOrderInfo.value.isEmpty())
        assertEquals(GiftCardPurchaseBlock.RATE_MISSING, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)
    }

    @Test
    fun failedRateLookup_blocksAndTheNextMerchantOrCurrencyRecovers() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        api.stub {
            onBlocking { getMerchant(eq("ctx-source-2")) } doReturn merchantResponse("ctx-source-2", "GBP")
        }
        rateLookups["GBP"] = flow { throw IOException("database closed") }
        setRate("EUR", "30")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        assertEquals(GiftCardPurchaseBlock.RATE_MISSING, viewModel.purchaseBlock)

        rateLookups.remove("GBP")
        setRate("GBP", "20")
        viewModel.openCtxMerchant(otherMerchant())
        viewModel.enter("25")
        assertNull("the same currency at the next merchant", viewModel.purchaseBlock)

        reset(api)
        ctxSells("EUR", listOf("5", "500"))
        viewModel.openCtxMerchant()
        viewModel.enter("25")
        assertNull("another currency", viewModel.purchaseBlock)
        assertEquals("EUR", viewModel.cardExchangeRate?.fiat?.currencyCode)
    }

    @Test
    fun emptyZeroOrMultiCardOrder_isRefused() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()

        assertEquals(GiftCardPurchaseBlock.NO_AMOUNT, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)

        viewModel.enter("0")
        assertEquals(GiftCardPurchaseBlock.NO_AMOUNT, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)

        // CTX is sent one card, so a total over several must not be confirmed
        viewModel.setGiftCardOrderInfo(FiatValue.parseFiat("GBP", "25"), 2)
        assertEquals(GiftCardPurchaseBlock.NO_AMOUNT, viewModel.purchaseBlock)
        viewModel.setGiftCardOrderQuantities(mapOf(25.0 to 1, 50.0 to 1))
        assertEquals(GiftCardPurchaseBlock.NO_AMOUNT, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)

        viewModel.enter("25")
        assertNull(viewModel.purchaseBlock)
    }

    @Test
    fun switchingProvider_clearsWhatWasChosenAtTheOtherOne() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.PiggyCards
        viewModel.setGiftCardMerchant(merchant())
        viewModel.setIsFixedDenominationMultiple(true)
        viewModel.setGiftCardOrderQuantities(mapOf(25.0 to 2, 50.0 to 1))

        viewModel.openCtxMerchant()

        assertTrue(viewModel.giftCardOrderInfo.value.isEmpty())
        assertNull(viewModel.isFixedDenominationMultiple.value)
        assertPurchaseRefused(viewModel)
    }

    @Test
    fun currencyChangingUnderTheConfirmation_clearsTheOrderAndRefusesIt() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        setRate("EUR", "30")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        viewModel.enter("25")

        reset(api)
        ctxSells("EUR", listOf("5", "500"))
        viewModel.updateMerchantDetails(merchant)

        assertEquals("EUR", viewModel.cardCurrency)
        assertTrue(viewModel.giftCardOrderInfo.value.isEmpty())

        // even with an amount, a sheet that showed pounds cannot buy in euros
        viewModel.enter("25")
        assertNull(viewModel.purchaseBlock)
        val inPounds = viewModel.currentQuote()!!.copy(currency = "GBP")
        assertThrows(CTXSpendException::class.java) { runBlocking { viewModel.purchaseGiftCard(inPounds) } }
        verifyBlocking(api, never()) { purchaseGiftCard(any()) }
    }

    @Test
    fun balanceEstimate_staysInUsdUntilTheCardIsKnownToBeInAnotherCurrency() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("USD", "25")
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.CTX
        viewModel.setGiftCardMerchant(merchant())
        assertEquals("USD", viewModel.balanceExchangeRate?.fiat?.currencyCode)

        viewModel.openCtxMerchant()
        assertNull("no pound rate: a dollar estimate would mislead", viewModel.balanceExchangeRate)

        setRate("GBP", "20")
        assertEquals("GBP", viewModel.balanceExchangeRate?.fiat?.currencyCode)
    }

    @Test
    fun merchantTurningFromRangeToFixed_clearsTheTypedAmount() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.setIsFixedDenomination(false)
        viewModel.enter("25")

        viewModel.setIsFixedDenomination(false)
        assertEquals("a refresh that says the same keeps it", mapOf(25.0 to 1), viewModel.giftCardOrderInfo.value)

        viewModel.setIsFixedDenomination(true)
        assertTrue(viewModel.giftCardOrderInfo.value.isEmpty())
    }

    @Test
    fun absentRate_isNotLookedUpAgainByARefreshOfTheSameMerchant() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        assertEquals(CardRateState.Missing, viewModel.cardRateState.value)

        viewModel.updateMerchantDetails(merchant)

        // the first lookup is still watching for the rate, so nothing may flip back to Loading
        assertEquals(listOf("GBP"), rateLookupsStarted.filter { it == "GBP" })
        assertEquals(CardRateState.Missing, viewModel.cardRateState.value)
        setRate("GBP", "20")
        assertTrue(viewModel.cardRateState.value is CardRateState.Available)
    }

    @Test
    fun failedRateLookup_isStartedAgainByARefreshOfTheSameMerchant() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        rateLookups["GBP"] = flow { throw IOException("database closed") }
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        assertEquals(GiftCardPurchaseBlock.RATE_MISSING, viewModel.purchaseBlock)

        rateLookups.remove("GBP")
        setRate("GBP", "20")
        assertEquals("the failed lookup is over", GiftCardPurchaseBlock.RATE_MISSING, viewModel.purchaseBlock)
        viewModel.updateMerchantDetails(merchant)
        viewModel.enter("25")

        assertEquals(CardCurrencyState.Known("GBP"), viewModel.cardCurrencyState.value)
        assertEquals(listOf("GBP", "GBP"), rateLookupsStarted.filter { it == "GBP" })
        assertNull(viewModel.purchaseBlock)
    }

    @Test
    fun refreshAnsweredWithDenominationsThatAreNotNumbers_blocksAMerchantThatHadACurrency() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        viewModel.enter("25")

        reset(api)
        ctxSells("GBP", listOf("5", "five hundred"))
        viewModel.updateMerchantDetails(merchant)

        assertEquals(CardCurrencyState.Unknown, viewModel.cardCurrencyState.value)
        assertTrue(viewModel.giftCardOrderInfo.value.isEmpty())
        assertPurchaseRefused(viewModel)
    }

    @Test
    fun olderLoadOfTheSameMerchantFinishingLast_appliesNothing() = runBlocking {
        val firstLoadStarted = CountDownLatch(1)
        val releaseFirstLoad = CountDownLatch(1)
        val answers = java.util.concurrent.atomic.AtomicInteger()
        api.stub {
            onBlocking { getMerchant(eq("ctx-source")) } doAnswer {
                if (answers.getAndIncrement() == 0) {
                    firstLoadStarted.countDown()
                    check(releaseFirstLoad.await(5, TimeUnit.SECONDS))
                    merchantResponse("ctx-source", "GBP", discount = 900)
                } else {
                    merchantResponse("ctx-source", "EUR", discount = 300)
                }
            }
        }
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.CTX
        viewModel.setGiftCardMerchant(merchant())
        val firstLoad = async(Dispatchers.Default) { viewModel.refreshMerchantDetails(merchant()) }
        val newer = try {
            assertTrue(firstLoadStarted.await(5, TimeUnit.SECONDS))
            viewModel.refreshMerchantDetails(merchant())
        } finally {
            releaseFirstLoad.countDown()
        }

        assertEquals(500.0, newer?.merchant?.maxCardPurchase)
        assertNull("the older answer must not be applied", firstLoad.await())
        assertEquals(CardCurrencyState.Known("EUR"), viewModel.cardCurrencyState.value)
        // the older answer reached the repository last, and must not price the confirmation either
        assertEquals(0.03, viewModel.getGiftCardDiscount(100.0), 0.0)
        viewModel.enter("100")
        assertEquals("97.00", viewModel.currentQuote()?.total?.toPlainString())
    }

    @Test
    fun piggyCards_ordersAWholeCartOfSeveralCardsInUsd() = runBlocking {
        piggyCards.stub { onBlocking { orderGiftcard(any(), any(), any(), any()) } doReturn emptyList() }
        // rules for CTX merchants: a GBP rate that is absent and a USD one that is there
        setRate("USD", "25")
        val cart = mapOf(25.0 to 2, 50.0 to 1)
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.PiggyCards
        viewModel.setGiftCardMerchant(merchant())
        viewModel.setIsFixedDenomination(false)
        viewModel.setIsFixedDenominationMultiple(true)
        viewModel.setGiftCardOrderQuantities(cart)

        // what only a CTX merchant reacts to
        viewModel.setIsFixedDenomination(true)
        viewModel.setGiftCardMerchant(merchant())
        viewModel.updateMerchantDetails(merchant())
        viewModel.selectedProvider = GiftCardProviderType.PiggyCards

        assertEquals("USD", viewModel.cardCurrency)
        assertNull(viewModel.purchaseBlock)
        assertEquals(cart, viewModel.giftCardOrderInfo.value)
        assertEquals(true, viewModel.isFixedDenominationMultiple.value)

        viewModel.buy()

        val ordered = argumentCaptor<GiftCardShoppingCart>()
        verifyBlocking(piggyCards) {
            orderGiftcard(eq("DASH"), eq("USD"), ordered.capture(), eq("piggycards-source"))
        }
        assertEquals(listOf(25.0 to 2, 50.0 to 1), ordered.firstValue.map { it.value to it.quantity })
        assertEquals(3, ordered.firstValue.cardCount())
        verifyBlocking(api, never()) { purchaseGiftCard(any()) }
    }

    private fun ctxAnswersTheOrderWith(paymentCurrency: String?, cardCurrency: String?) {
        api.stub {
            onBlocking { purchaseGiftCard(any()) } doAnswer {
                GiftCardResponse(
                    id = "order-1",
                    status = "unpaid",
                    fiatAmount = it.getArgument<PurchaseGiftCardRequest>(0).fiatAmount,
                    fiatCurrency = paymentCurrency,
                    cardFiatCurrency = cardCurrency
                )
            }
        }
    }

    @Test
    fun nonUsdOrderAnsweredWithoutAnyCurrency_isNotPaid() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("25")

        listOf<Pair<String?, String?>>(null to null, "" to "  ").forEach { (payment, card) ->
            ctxAnswersTheOrderWith(payment, card)
            assertThrows("$payment/$card", CTXSpendException::class.java) { runBlocking { viewModel.buy() } }
        }

        ctxAnswersTheOrderWith(paymentCurrency = null, cardCurrency = "GBP")
        assertEquals("GBP", viewModel.buy().single().fiatCurrency)
        ctxAnswersTheOrderWith(paymentCurrency = "gbp", cardCurrency = null)
        assertEquals("gbp", viewModel.buy().single().fiatCurrency)
    }

    @Test
    fun usdOrderAnsweredWithoutACurrency_isStillAccepted() = runBlocking {
        ctxSells("USD", listOf("5", "500"))
        setRate("USD", "25")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("25")
        ctxAnswersTheOrderWith(paymentCurrency = null, cardCurrency = "")

        assertEquals("USD", viewModel.buy().single().fiatCurrency)
    }

    @Test
    fun denominationsThatAreNotAmounts_leaveTheCurrencyUnknown() {
        listOf(
            listOf("5", "NaN"),
            listOf("5", "Infinity"),
            listOf("-5", "500"),
            listOf("0", "0")
        ).forEach { denominations ->
            reset(api)
            ctxSells("GBP", denominations)
            setRate("GBP", "20")
            val viewModel = viewModel()

            runBlocking { viewModel.openCtxMerchant() }

            assertEquals("$denominations", CardCurrencyState.Unknown, viewModel.cardCurrencyState.value)
            assertEquals(GiftCardPurchaseBlock.CURRENCY_UNKNOWN, viewModel.purchaseBlock)
        }
    }

    @Test
    fun screenRebuiltWithTheCatalogueRow_keepsTheConfirmedDetailsWhenTheRefreshFails() = runBlocking {
        ctxSells("GBP", listOf("10", "20", "50"), denominationsType = "fixed")
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.applyMerchantRefresh(
            viewModel.run {
                selectedProvider = GiftCardProviderType.CTX
                setGiftCardMerchant(merchant())
                refreshMerchantDetails(merchant())!!
            }
        )
        assertEquals(true, viewModel.isFixedDenomination.value)

        // what a rebuilt screen does: the row from the catalogue again, then a refresh that gets no answer
        api.stub { onBlocking { getMerchant(any()) } doAnswer { throw IOException("offline") } }
        viewModel.setGiftCardMerchant(merchant())
        assertEquals("before the refresh", listOf(10.0, 20.0, 50.0), viewModel.giftCardMerchant.value?.denominations)
        val refresh = viewModel.refreshMerchantDetails(merchant())!!
        assertTrue(viewModel.applyMerchantRefresh(refresh))

        assertEquals(CardCurrencyState.Known("GBP"), viewModel.cardCurrencyState.value)
        assertEquals(listOf(10.0, 20.0, 50.0), viewModel.giftCardMerchant.value?.denominations)
        assertEquals(50.0, viewModel.giftCardMerchant.value?.maxCardPurchase)
        assertEquals(true, viewModel.giftCardMerchant.value?.fixedDenomination)
        assertEquals(true, viewModel.isFixedDenomination.value)
    }

    @Test
    fun refreshResultOfAnOlderLoad_isNotApplied() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.CTX
        viewModel.setGiftCardMerchant(merchant())
        val older = viewModel.refreshMerchantDetails(merchant())!!

        reset(api)
        ctxSells("GBP", listOf("10", "20"), denominationsType = "fixed")
        val newer = viewModel.refreshMerchantDetails(merchant())!!

        assertFalse(viewModel.applyMerchantRefresh(older))
        assertNull(viewModel.isFixedDenomination.value)
        assertTrue(viewModel.applyMerchantRefresh(newer))
        assertEquals(true, viewModel.isFixedDenomination.value)
        assertEquals(20.0, viewModel.giftCardMerchant.value?.maxCardPurchase)

        viewModel.selectedProvider = GiftCardProviderType.PiggyCards
        assertFalse("another provider", viewModel.applyMerchantRefresh(newer))
    }

    @Test
    fun orderChangedAfterTheConfirmationOpened_isRefused() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("25")
        val shown = viewModel.currentQuote()!!

        viewModel.enter("40")

        assertNull(viewModel.purchaseBlock)
        assertThrows(CTXSpendException::class.java) { runBlocking { viewModel.purchaseGiftCard(shown) } }
        verifyBlocking(api, never()) { purchaseGiftCard(any()) }

        viewModel.enter("25")
        viewModel.purchaseGiftCard(shown)
        assertEquals("25.00", orderSentToCtx().fiatAmount)
    }

    @Test
    fun discountLoweredAfterTheConfirmationOpened_isRefused() = runBlocking {
        ctxSells("GBP", listOf("5", "500"), discount = 1000)
        setRate("GBP", "20")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        viewModel.enter("100")
        val shown = viewModel.currentQuote()!!
        assertEquals("90.00", shown.total.toPlainString())

        // same currency, card and quantity; only what it costs is new
        reset(api)
        ctxSells("GBP", listOf("5", "500"), discount = 200)
        viewModel.updateMerchantDetails(merchant)

        assertEquals(mapOf(100.0 to 1), viewModel.giftCardOrderInfo.value)
        assertNull(viewModel.purchaseBlock)
        assertEquals("98.00", viewModel.currentQuote()?.total?.toPlainString())
        assertThrows(CTXSpendException::class.java) { runBlocking { viewModel.purchaseGiftCard(shown) } }
        verifyBlocking(api, never()) { purchaseGiftCard(any()) }
    }

    @Test
    fun fixedCardNoLongerSold_isUnselected() = runBlocking {
        ctxSells("GBP", listOf("10", "20", "50"), denominationsType = "fixed")
        setRate("GBP", "20")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        viewModel.setIsFixedDenomination(true)
        viewModel.enter("20")
        assertNull(viewModel.purchaseBlock)

        viewModel.setGiftCardOrderQuantities(mapOf(30.0 to 1))
        assertEquals("never on the list", GiftCardPurchaseBlock.AMOUNT_NOT_OFFERED, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)

        viewModel.enter("20")
        reset(api)
        ctxSells("GBP", listOf("10", "50"), denominationsType = "fixed")
        viewModel.updateMerchantDetails(merchant)

        assertEquals(CardCurrencyState.Known("GBP"), viewModel.cardCurrencyState.value)
        assertTrue(viewModel.giftCardOrderInfo.value.isEmpty())
        assertPurchaseRefused(viewModel)
    }

    @Test
    fun amountOutsideTheAcceptedRange_isBlocked() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        viewModel.enter("300")
        assertNull(viewModel.purchaseBlock)

        reset(api)
        ctxSells("GBP", listOf("5", "200"))
        viewModel.updateMerchantDetails(merchant)

        // the entry still shows 300, so the order keeps it and the block says why it cannot be bought
        assertEquals(mapOf(300.0 to 1), viewModel.giftCardOrderInfo.value)
        assertEquals(GiftCardPurchaseBlock.AMOUNT_NOT_OFFERED, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)
        viewModel.enter("4.99")
        assertEquals(GiftCardPurchaseBlock.AMOUNT_NOT_OFFERED, viewModel.purchaseBlock)
        viewModel.enter("200")
        assertNull(viewModel.purchaseBlock)
    }

    @Test
    fun answerThatCannotBeDecoded_blocksAMerchantThatHadACurrency() {
        listOf<Exception>(
            com.google.gson.JsonSyntaxException("Expected BEGIN_OBJECT but was STRING"),
            com.google.gson.stream.MalformedJsonException("Unterminated object")
        ).forEach { failure ->
            reset(api)
            ctxSells("GBP", listOf("5", "500"))
            setRate("GBP", "20")
            val viewModel = viewModel()
            val merchant = runBlocking { viewModel.openCtxMerchant() }
            viewModel.enter("25")

            api.stub { onBlocking { getMerchant(any()) } doAnswer { throw failure } }
            runBlocking { viewModel.updateMerchantDetails(merchant) }

            assertEquals("$failure", CardCurrencyState.Unknown, viewModel.cardCurrencyState.value)
            assertTrue(viewModel.giftCardOrderInfo.value.isEmpty())
        }
    }

    @Test
    fun denominationsFinerThanTheCurrency_leaveTheCurrencyUnknown() {
        listOf(
            "USD" to listOf("5", "10.00001"),
            "USD" to listOf("5.001", "500"),
            "JPY" to listOf("500", "1000.5"),
            "KWD" to listOf("1", "1.2345"),
            "USD" to listOf("5", "92233720369")
        ).forEach { (currency, denominations) ->
            reset(api)
            ctxSells(currency, denominations)
            val viewModel = viewModel()

            runBlocking { viewModel.openCtxMerchant() }

            assertEquals("$currency $denominations", CardCurrencyState.Unknown, viewModel.cardCurrencyState.value)
        }
        reset(api)
        ctxSells("KWD", listOf("1.000", "1.234"))
        val viewModel = viewModel()
        runBlocking { viewModel.openCtxMerchant() }
        assertEquals(CardCurrencyState.Known("KWD"), viewModel.cardCurrencyState.value)
    }

    @Test
    fun ctxLoadStillRunningWhenTheProviderChanges_cannotTouchThePiggyCardsCart() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        api.stub {
            onBlocking { getMerchant(eq("ctx-source")) } doAnswer {
                refreshStarted.countDown()
                check(releaseRefresh.await(5, TimeUnit.SECONDS))
                merchantResponse("ctx-source", "EUR")
            }
        }
        val refresh = async(Dispatchers.Default) { viewModel.refreshMerchantDetails(merchant()) }
        val cart = mapOf(25.0 to 2, 50.0 to 1)
        try {
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
            viewModel.selectedProvider = GiftCardProviderType.PiggyCards
            viewModel.setGiftCardOrderQuantities(cart)
        } finally {
            releaseRefresh.countDown()
        }

        assertNull(refresh.await())
        assertEquals(cart, viewModel.giftCardOrderInfo.value)

        // back at CTX nothing of the abandoned load is left
        viewModel.selectedProvider = GiftCardProviderType.CTX
        assertEquals(CardCurrencyState.Loading, viewModel.cardCurrencyState.value)
        assertNull(viewModel.cardCurrency)
    }

    @Test
    fun wholeAmountRangeMerchant_isSoldWithItsTwoValuesAndWholeAmountsOnly() = runBlocking {
        // what CTX sends for "min-max-major": the same (min, max) pair, in whole units
        ctxSells("USD", listOf("1", "500"), denominationsType = "min-max-major")
        setRate("USD", "25")
        val viewModel = viewModel()

        val merchant = viewModel.openCtxMerchant()

        assertEquals(CardCurrencyState.Known("USD"), viewModel.cardCurrencyState.value)
        assertFalse(merchant.fixedDenomination)
        assertEquals(FiatValue.parseFiat("USD", "1"), viewModel.minCardPurchaseFiat)
        assertEquals(FiatValue.parseFiat("USD", "500"), viewModel.maxCardPurchaseFiat)
        assertTrue(viewModel.isWholeAmountOnly)

        viewModel.enter("25.50")
        assertEquals(GiftCardPurchaseBlock.AMOUNT_NOT_REPRESENTABLE, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)

        viewModel.enter("25")
        assertNull(viewModel.purchaseBlock)
        viewModel.buy()
        assertEquals("25.00", orderSentToCtx().fiatAmount)
    }

    @Test
    fun ordinaryRangeMerchant_stillTakesFractions() = runBlocking {
        ctxSells("USD", listOf("1", "500"))
        setRate("USD", "25")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()

        viewModel.enter("25.50")

        assertFalse(viewModel.isWholeAmountOnly)
        assertNull(viewModel.purchaseBlock)
    }

    @Test
    fun acceptedDetailsVersion_changesWithEveryAcceptedAnswer() = runBlocking {
        ctxSells("GBP", listOf("5", "500"), discount = 1000)
        setRate("GBP", "20")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        val shown = viewModel.acceptedDetailsVersion.value

        // not applied to the screen, as when the merchant turns out to be disabled
        reset(api)
        ctxSells("GBP", listOf("5", "500"), discount = 200)
        viewModel.refreshMerchantDetails(merchant)

        assertTrue(viewModel.acceptedDetailsVersion.value != shown)
        assertEquals(0.02, viewModel.getGiftCardDiscount(100.0), 0.0)
    }

    /** A GBP 100 card at 5% off, confirmed at GBP 95.00 = 3.16666666 Dash, that CTX charges [dash] for. */
    private suspend fun payOrderCharged(
        dash: String?,
        cardAmount: String? = "100.00",
        acceptsHigherPrice: Boolean = false,
        invoiced: Dash? = null
    ): Pair<String?, List<GiftCardPriceChange>> {
        ctxSells("GBP", listOf("5", "500"), discount = 500)
        api.stub {
            onBlocking { purchaseGiftCard(any()) } doAnswer {
                GiftCardResponse(
                    id = "order-1",
                    status = "unpaid",
                    cardFiatAmount = cardAmount,
                    cardFiatCurrency = "GBP",
                    cryptoAmount = dash,
                    fiatAmount = "95.00",
                    fiatCurrency = "GBP"
                )
            }
        }
        paymentRequestAsksFor(invoiced ?: dash?.let(GiftCardPaymentGuard::dashAmount)?.toDash())
        setRate("GBP", "30")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("100")
        val shown = checkNotNull(viewModel.currentQuote())
        assertEquals(BigDecimal("95.00"), shown.total)
        val asked = mutableListOf<GiftCardPriceChange>()

        var txId = viewModel.payCtxOrder(shown, "dash:?r=https://ctx.example/pay", viewModel.purchaseGiftCard(shown))
        while (txId == null) {
            // what the sheet does with the question the view model holds
            asked += viewModel.priceChange.value ?: break
            assertTrue("asked about the same order over and over", asked.size < 4)
            assertEquals(GiftCardSubmissionState.IDLE, viewModel.submissionState.value)
            if (!acceptsHigherPrice) {
                viewModel.declinePriceChange()
                break
            }
            txId = viewModel.payAcceptedPrice()
        }
        assertNull(viewModel.priceChange.value)
        return txId to asked
    }

    /** The wallet's side of a payment: the fetched request asks for [amount], and is paid unless refused. */
    private fun paymentRequestAsksFor(amount: Dash?) {
        val transaction = mock<TxInfo> { on { txId } doReturn zeroTxId }
        sentPayments.clear()
        payments.stub {
            onBlocking {
                payWithDashUrl(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
            } doSuspendableAnswer {
                it.getArgument<(suspend (Dash?) -> Unit)?>(4)?.invoke(amount)
                it.getArgument<(suspend (String) -> Unit)?>(5)?.invoke(zeroTxId)
                sentPayments += amount
                transaction
            }
        }
    }

    private fun assertPaid(times: Int) = verifyBlocking(payments, times(times)) {
        payWithDashUrl(
            eq("dash:?r=https://ctx.example/pay"),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull()
        )
    }

    @Test
    fun ctxChargesWhatWasShownOrLess_isPaidWithoutAsking() = runBlocking {
        listOf("3.16666666", "3.00000000", "3.18249999").forEach { dash ->
            reset(payments)

            val (txId, asked) = payOrderCharged(dash)

            assertEquals(dash, zeroTxId, txId)
            assertEquals(dash, emptyList<GiftCardPriceChange>(), asked)
            assertPaid(1)
        }
    }

    @Test
    fun ctxChargesMoreThanShown_isPaidOnlyWhenAccepted() = runBlocking {
        // GBP 98.00 at the same rate
        val (declinedTxId, asked) = payOrderCharged("3.26666667")

        assertNull(declinedTxId)
        assertEquals(1, asked.size)
        assertEquals(Dash.valueOf(316_666_666), asked.single().shown)
        assertEquals(Dash.valueOf(326_666_667), asked.single().charged)
        assertEquals(BigDecimal("95.00"), asked.single().shownTotal)
        assertEquals(0, BigDecimal("98.0000001").compareTo(asked.single().chargedTotal))
        assertPaid(0)

        val (acceptedTxId, askedAgain) = payOrderCharged("3.26666667", acceptsHigherPrice = true)

        assertEquals(zeroTxId, acceptedTxId)
        assertEquals(1, askedAgain.size)
        assertPaid(1)
    }

    @Test
    fun declinedHigherPrice_leavesTheOrderUnpaidAndThePurchaseOpen() = runBlocking {
        ctxSells("GBP", listOf("5", "500"), discount = 500)
        setRate("GBP", "30")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("100")
        val shown = checkNotNull(viewModel.currentQuote())
        val order = listOf(GiftCardInfo("order-1", cryptoAmount = "3.5"))
        assertTrue(viewModel.tryStartSubmission())

        assertNull(viewModel.payCtxOrder(shown, "dash:?r=https://ctx.example/pay", order))
        viewModel.declinePriceChange()

        assertEquals(GiftCardSubmissionState.IDLE, viewModel.submissionState.value)
        verifyBlocking(giftCardDao, never()) { insertGiftCard(any()) }
        assertPaid(0)
    }

    @Test
    fun chargeThatCannotBeChecked_isNotPaid() = runBlocking {
        listOf(null, "", "0", "abc").forEach { dash ->
            val refusal = assertThrows(CTXSpendException::class.java) { runBlocking { payOrderCharged(dash) } }

            assertEquals("$dash", R.string.gift_card_price_unchecked, refusal.resourceString?.resourceId)
        }
        assertPaid(0)
    }

    @Test
    fun chargeWithoutARate_isNotPaid() = runBlocking {
        ctxSells("GBP", listOf("5", "500"), discount = 500)
        setRate("GBP", "30")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("100")
        val shown = checkNotNull(viewModel.currentQuote())
        rateFlow("GBP").value = null

        val refusal = assertThrows(CTXSpendException::class.java) {
            runBlocking {
                val order = listOf(GiftCardInfo("order-1", cryptoAmount = "3.1"))
                viewModel.payCtxOrder(shown, "dash:?r=x", order)
            }
        }

        assertEquals(R.string.exchange_rate_not_found, refusal.resourceString?.resourceId)
        verifyBlocking(payments, never()) {
            payWithDashUrl(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        }
    }

    @Test
    fun orderForAnotherCardAmount_isRefused() = runBlocking {
        listOf("110.00", "abc").forEach { answered ->
            assertThrows(CTXSpendException::class.java) {
                runBlocking { payOrderCharged("3.16666666", cardAmount = answered) }
            }
        }
        assertPaid(0)

        // the same amount written another way, or left out as before CTX reported it
        reset(payments)
        assertEquals(zeroTxId, payOrderCharged("3.16666666", cardAmount = "100").first)
        assertEquals(zeroTxId, payOrderCharged("3.16666666", cardAmount = null).first)
    }

    @Test
    fun usdOrderWithOnlyTheDashAmount_isCheckedTheSameWay() = runBlocking {
        ctxSells("USD", listOf("5", "500"), discount = 0)
        setRate("USD", "40")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("100")
        val shown = checkNotNull(viewModel.currentQuote())

        paymentRequestAsksFor(Dash.valueOf(250_000_000))

        val paid = listOf(GiftCardInfo("order-1", cryptoAmount = "2.5", fiatAmount = "100.00"))
        assertEquals(zeroTxId, viewModel.payCtxOrder(shown, "dash:?r=x", paid))

        val higher = listOf(GiftCardInfo("order-2", cryptoAmount = "2.6", fiatAmount = "100.00"))
        assertNull(viewModel.payCtxOrder(shown, "dash:?r=x", higher))
        assertEquals(Dash.valueOf(260_000_000), viewModel.priceChange.value?.charged)
    }

    @Test
    fun refusalsAtConfirm_carryAMessageForTheUser() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("100")
        val shown = checkNotNull(viewModel.currentQuote())
        viewModel.enter("50")

        val refusal = assertThrows(CTXSpendException::class.java) { runBlocking { viewModel.purchaseGiftCard(shown) } }

        assertEquals(R.string.gift_card_order_changed, refusal.resourceString?.resourceId)
    }

    @Test
    fun changedRangeType_reachesTheKeypadWithoutTheMerchantChanging() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()
        val shownMerchant = viewModel.giftCardMerchant.value
        val version = viewModel.acceptedDetailsVersion.value
        assertEquals(2, viewModel.entryDecimals)

        reset(api)
        ctxSells("GBP", listOf("5", "500"), denominationsType = "min-max-major")
        viewModel.refreshMerchantDetails(merchant)?.let(viewModel::applyMerchantRefresh)

        // nothing the merchant flow would announce
        assertEquals(shownMerchant, viewModel.giftCardMerchant.value)
        assertTrue(viewModel.acceptedDetailsVersion.value != version)
        assertEquals(0, viewModel.entryDecimals)
    }

    @Test
    fun cutShortAnswer_countsAsNoAnswerAndKeepsTheCurrency() = runBlocking {
        ctxSells("GBP", listOf("5", "500"))
        setRate("GBP", "20")
        val viewModel = viewModel()
        val merchant = viewModel.openCtxMerchant()

        reset(api)
        api.stub { onBlocking { getMerchant(any()) } doAnswer { throw java.io.EOFException("End of input") } }
        viewModel.refreshMerchantDetails(merchant)

        assertEquals(CardCurrencyState.Known("GBP"), viewModel.cardCurrencyState.value)
    }

    @Test
    fun merchantThatOffersNoCard_isNotSoldRatherThanUnreachable() = runBlocking {
        // what CTX answers while no discount is on offer
        ctxSells("USD", emptyList(), denominationsType = "")
        setRate("USD", "25")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals(CardCurrencyState.Unknown, viewModel.cardCurrencyState.value)
        assertEquals(GiftCardPurchaseBlock.NOT_SOLD, viewModel.purchaseBlock)
        viewModel.setGiftCardOrderQuantities(mapOf(25.0 to 1))
        assertPurchaseRefused(viewModel)

        // sold again
        reset(api)
        ctxSells("USD", listOf("5", "500"))
        viewModel.openCtxMerchant()
        assertEquals(CardCurrencyState.Known("USD"), viewModel.cardCurrencyState.value)
        assertNull(viewModel.purchaseBlock)
    }

    @Test
    fun unreadableAnswer_isStillACurrencyThatCouldNotBeConfirmed() = runBlocking {
        ctxSells("USD", listOf("abc"))
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals(GiftCardPurchaseBlock.CURRENCY_UNKNOWN, viewModel.purchaseBlock)
    }

    @Test
    fun ctxTrouble_doesNotKeepThePiggyCardsRowFromBeingRefreshed() = runBlocking {
        ctxSells("USD", emptyList(), denominationsType = "")
        signedInToCtx = true
        val piggyCardsAnswer = UpdatedMerchantDetails(
            id = "piggycards-source",
            denominations = listOf(10.0, 200.0),
            denominationsType = "min-max",
            savingsPercentage = 700,
            redeemType = "barcode",
            enabled = false
        )
        piggyCards.stub {
            onBlocking { isUserSignedIn() } doReturn true
            onBlocking { getMerchant(eq("piggycards-source")) } doReturn piggyCardsAnswer
            // debug builds only ask PiggyCards about its test merchants, and this way
            onBlocking { getMerchant(eq("piggycards-source"), any()) } doReturn piggyCardsAnswer
        }
        val viewModel = viewModel()
        val testMerchant = merchant().apply { merchantId = PiggyCardsTestMerchants.ALL.first().merchantId }

        val refreshed = viewModel.updateMerchantDetailsForAllProviders(testMerchant)

        val row = refreshed.giftCardProviders.single { it.provider == GiftCardProviderType.PiggyCards.name }
        assertEquals(700, row.savingsPercentage)
        assertFalse(row.active)
        assertEquals(700, refreshed.savingsPercentage)
    }

    @Test
    fun piggyCardsPriceChange_isAnnouncedSoAnOpenSheetCanClose() = runBlocking {
        var discount = 0.10
        piggyCards.stub {
            on { getGiftCardDiscount(any(), any()) } doAnswer { discount }
            onBlocking { getMerchant(any()) } doAnswer {
                discount = 0.05
                null
            }
        }
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.PiggyCards
        viewModel.setGiftCardMerchant(merchant())
        viewModel.setGiftCardOrderQuantities(mapOf(25.0 to 2))
        val shown = checkNotNull(viewModel.currentQuote())
        val version = viewModel.acceptedDetailsVersion.value

        viewModel.refreshMerchantDetails(merchant())

        assertTrue(viewModel.acceptedDetailsVersion.value != version)
        assertEquals(shown.order, viewModel.currentQuote()?.order)
        assertEquals(BigDecimal("47.50"), viewModel.currentQuote()?.total)
        assertEquals(BigDecimal("45.00"), shown.total)
    }

    @Test
    fun entryDecimals_followTheCardCurrencyAndTheRangeType() = runBlocking {
        ctxSells("KWD", listOf("1", "50"))
        setRate("KWD", "9")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()
        assertEquals(3, viewModel.entryDecimals)
        val keypadDecimals = AmountInputRules.maxDecimals(false, viewModel.entryDecimals)
        assertTrue(AmountInputRules.acceptsDigit("1.23", '.', keypadDecimals))

        reset(api)
        ctxSells("JPY", listOf("500", "50000"))
        viewModel.openCtxMerchant()
        assertEquals(0, viewModel.entryDecimals)

        reset(api)
        ctxSells("USD", listOf("1", "500"), denominationsType = "min-max-major")
        viewModel.openCtxMerchant()
        assertEquals(0, viewModel.entryDecimals)
    }

    @Test
    fun noProviderReached_leavesTheMerchantAsItIs() = runBlocking {
        api.stub { onBlocking { getMerchant(any()) } doAnswer { throw java.io.IOException("offline") } }
        signedInToCtx = true
        val viewModel = viewModel()
        val merchant = merchant().apply { savingsPercentage = 500 }

        val refreshed = viewModel.updateMerchantDetailsForAllProviders(merchant)

        assertEquals(500, refreshed.savingsPercentage)
    }

    @Test
    fun paymentRequestForWhatWasShownOrLess_isPaidWithoutAsking() = runBlocking {
        listOf(316_666_666L, 300_000_000L, 318_249_999L).forEach { duffs ->
            reset(payments)

            val (txId, asked) = payOrderCharged("3.16666666", invoiced = Dash.valueOf(duffs))

            assertEquals("$duffs", zeroTxId, txId)
            assertEquals("$duffs", emptyList<GiftCardPriceChange>(), asked)
            assertEquals(listOf<Dash?>(Dash.valueOf(duffs)), sentPayments)
        }
    }

    @Test
    fun paymentRequestForMoreThanShown_isPaidOnlyWhenAccepted() = runBlocking {
        val invoiced = Dash.valueOf(326_666_667)

        val (declinedTxId, asked) = payOrderCharged("3.16666666", invoiced = invoiced)

        assertNull(declinedTxId)
        assertEquals(listOf(invoiced), asked.map { it.charged })
        assertEquals(listOf(Dash.valueOf(316_666_666)), asked.map { it.shown })
        assertEquals(emptyList<Dash?>(), sentPayments)

        val (acceptedTxId, askedAgain) = payOrderCharged("3.16666666", acceptsHigherPrice = true, invoiced = invoiced)

        assertEquals(zeroTxId, acceptedTxId)
        assertEquals(1, askedAgain.size)
        assertEquals(listOf<Dash?>(invoiced), sentPayments)
    }

    @Test
    fun higherPriceAcceptedOnce_coversThePaymentRequestForTheSameAmount() = runBlocking {
        val (txId, asked) = payOrderCharged("3.26666667", acceptsHigherPrice = true)

        assertEquals(zeroTxId, txId)
        assertEquals(1, asked.size)
        assertEquals(listOf<Dash?>(Dash.valueOf(326_666_667)), sentPayments)
    }

    @Test
    fun paymentRequestAboveTheAcceptedPrice_isAskedAboutAgain() = runBlocking {
        // 3.5 is more than 0.5% above the 3.26666667 accepted a moment earlier
        val invoiced = Dash.valueOf(350_000_000)
        val (txId, asked) = payOrderCharged("3.26666667", acceptsHigherPrice = true, invoiced = invoiced)

        assertEquals(zeroTxId, txId)
        assertEquals(listOf(Dash.valueOf(326_666_667), Dash.valueOf(350_000_000)), asked.map { it.charged })
        assertEquals(listOf(Dash.valueOf(316_666_666), Dash.valueOf(326_666_667)), asked.map { it.shown })
    }

    @Test
    fun paymentRequestWithoutAnAmount_isNotPaid() = runBlocking {
        ctxSells("GBP", listOf("5", "500"), discount = 500)
        setRate("GBP", "30")
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("100")
        val shown = checkNotNull(viewModel.currentQuote())
        val order = listOf(GiftCardInfo("order-1", cryptoAmount = "3.16666666"))

        listOf(null, Dash.ZERO).forEach { invoiced ->
            paymentRequestAsksFor(invoiced)

            val refusal = assertThrows(CTXSpendException::class.java) {
                runBlocking { viewModel.payCtxOrder(shown, "dash:?r=x", order) }
            }

            assertEquals(R.string.gift_card_price_unchecked, refusal.resourceString?.resourceId)
            assertEquals(emptyList<Dash?>(), sentPayments)
            assertEquals(GiftCardSubmissionState.IDLE, viewModel.submissionState.value)
        }
    }

    @Test
    fun piggyCardsPayment_isNotChecked() = runBlocking {
        paymentRequestAsksFor(Dash.valueOf(999_000_000))
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.PiggyCards

        viewModel.payAndRecordOrder("dash:?r=x", emptyList())

        verifyBlocking(payments) {
            payWithDashUrl(any(), anyOrNull(), anyOrNull(), anyOrNull(), eq(null), anyOrNull())
        }
    }

    @Test
    fun paidOrder_isRecordedAndHandedToOneSheetWhicheverStartedIt() = runBlocking {
        paymentRequestAsksFor(Dash.COIN)
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.CTX
        assertNull(viewModel.paidOrder.value)

        val txId = viewModel.payAndRecordOrder("dash:?r=x", listOf(GiftCardInfo("order-1", fiatAmount = "10")))

        val recorded = argumentCaptor<List<GiftCard>>()
        verifyBlocking(giftCardDao) { insertGiftCards(recorded.capture()) }
        assertEquals(listOf("order-1"), recorded.firstValue.map { it.note })
        val paid = PaidOrder(txId, isMultipleCards = false)
        assertEquals(paid, viewModel.paidOrder.value)
        // a sheet opened for the next purchase must not make this one vanish
        viewModel.beginNewPurchase()
        assertEquals(paid, viewModel.paidOrder.value)
        assertTrue(viewModel.isPurchaseOutstanding)
        assertTrue(viewModel.takePaidOrder(paid))
        assertFalse(viewModel.takePaidOrder(paid))
        assertNull(viewModel.paidOrder.value)
    }

    @Test
    fun paidCartOfSeveralCards_getsTheOrderDetailsEvenAfterTheCartWasReset() = runBlocking {
        paymentRequestAsksFor(Dash.COIN)
        val viewModel = viewModel()
        viewModel.selectedProvider = GiftCardProviderType.PiggyCards
        viewModel.setGiftCardOrderQuantities(mapOf(25.0 to 2, 50.0 to 1))
        val cards = listOf(GiftCardInfo("order-1", fiatAmount = "25"), GiftCardInfo("order-2", fiatAmount = "50"))

        val txId = viewModel.payAndRecordOrder("dash:?r=x", cards)
        viewModel.resetSelectedDenomination()

        assertEquals(PaidOrder(txId, isMultipleCards = true), viewModel.paidOrder.value)
    }

    @Test
    fun acceptedHigherPrice_isPaidFromTheWalletTheOrderWasConfirmedOn() = runBlocking {
        ctxSells("GBP", listOf("5", "500"), discount = 500)
        setRate("GBP", "30")
        paymentRequestAsksFor(Dash.valueOf(326_666_667))
        val confirmedOn = WalletSession("confirmed on")
        payments.stub { on { currentWalletSession() } doReturn confirmedOn }
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("100")
        val shown = checkNotNull(viewModel.currentQuote())
        val order = listOf(GiftCardInfo("order-1", cryptoAmount = "3.16666666", fiatAmount = "95.00"))
        assertTrue(viewModel.tryStartSubmission())
        assertNull(viewModel.payCtxOrder(shown, "dash:?r=x", order))

        // a wipe installs another wallet while the question is on screen
        payments.stub { on { currentWalletSession() } doReturn WalletSession("replacement") }
        assertEquals(zeroTxId, viewModel.payAcceptedPrice())

        val sessions = argumentCaptor<WalletSession>()
        verifyBlocking(payments, times(2)) {
            payWithDashUrl(any(), anyOrNull(), anyOrNull(), sessions.capture(), anyOrNull(), anyOrNull())
        }
        assertEquals(listOf(confirmedOn, confirmedOn), sessions.allValues)
    }

    @Test
    fun higherPrice_isHeldAsAQuestionWithNothingPaidAndNothingWaiting() = runBlocking {
        ctxSells("GBP", listOf("5", "500"), discount = 500)
        setRate("GBP", "30")
        paymentRequestAsksFor(Dash.valueOf(326_666_667))
        val viewModel = viewModel()
        viewModel.openCtxMerchant()
        viewModel.enter("100")
        val shown = checkNotNull(viewModel.currentQuote())
        val order = listOf(GiftCardInfo("order-1", cryptoAmount = "3.16666666", fiatAmount = "95.00"))
        assertTrue(viewModel.tryStartSubmission())

        // returns by itself: no answer is awaited while the payment is under way
        assertNull(viewModel.payCtxOrder(shown, "dash:?r=x", order))

        val change = checkNotNull(viewModel.priceChange.value)
        assertEquals("order-1", change.orderId)
        assertEquals(Dash.valueOf(326_666_667), change.charged)
        assertEquals(GiftCardSubmissionState.IDLE, viewModel.submissionState.value)
        assertEquals(emptyList<Dash?>(), sentPayments)
        verifyBlocking(giftCardDao, never()) { insertGiftCards(any()) }
        // what a sheet rebuilt after recreation finds
        assertTrue(viewModel.isPurchaseOutstanding)
        assertEquals(change, viewModel.priceChange.value)

        assertEquals(zeroTxId, viewModel.payAcceptedPrice())

        assertEquals(listOf<Dash?>(Dash.valueOf(326_666_667)), sentPayments)
        val recorded = argumentCaptor<List<GiftCard>>()
        verifyBlocking(giftCardDao) { insertGiftCards(recorded.capture()) }
        assertEquals(listOf("order-1"), recorded.firstValue.map { it.note })
        assertNull(viewModel.priceChange.value)
        assertNull(viewModel.payAcceptedPrice())
    }

    // 15 minutes to pay by CTX's own clock, and by any wall clock both moments are years ago
    private fun orderOpenFor15Minutes(created: String? = "2020-01-01T10:00:00Z") = listOf(
        GiftCardInfo(
            "order-1",
            cryptoAmount = "3.16666666",
            fiatAmount = "95.00",
            paymentUrlExpiration = "2020-01-01T10:15:00+00:00",
            created = created
        )
    )

    private suspend fun DashSpendViewModel.shownFor100Pounds(): GiftCardQuote {
        ctxSells("GBP", listOf("5", "500"), discount = 500)
        setRate("GBP", "30")
        elapsedRealtime = { elapsedMillis }
        openCtxMerchant()
        enter("100")
        return checkNotNull(currentQuote())
    }

    private fun assertRefusedAsExpired(pay: suspend () -> String?) {
        val refusal = assertThrows(CTXSpendException::class.java) { runBlocking { pay() } }
        assertEquals(R.string.gift_card_order_expired, refusal.resourceString?.resourceId)
    }

    @Test
    fun orderLifetime_isMeasuredOnThisDeviceNotReadOffItsWallClock() = runBlocking {
        val viewModel = viewModel()
        val shown = viewModel.shownFor100Pounds()

        // it can run out while the payment request is being fetched
        payments.stub {
            onBlocking {
                payWithDashUrl(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
            } doSuspendableAnswer {
                elapsedMillis += 15.minutes.inWholeMilliseconds + 1
                it.getArgument<(suspend (Dash?) -> Unit)?>(4)?.invoke(Dash.valueOf(316_666_666))
                sentPayments += Dash.valueOf(316_666_666)
                mock<TxInfo>()
            }
        }
        assertRefusedAsExpired { viewModel.payCtxOrder(shown, "dash:?r=x", orderOpenFor15Minutes()) }
        assertEquals(emptyList<Dash?>(), sentPayments)
        assertEquals(GiftCardSubmissionState.IDLE, viewModel.submissionState.value)

        // the wall clock is years past the expiry time, yet hardly any time passes after the answer
        paymentRequestAsksFor(Dash.valueOf(316_666_666))
        assertEquals(zeroTxId, viewModel.payCtxOrder(shown, "dash:?r=x", orderOpenFor15Minutes()))
    }

    @Test
    fun orderLifetime_runsFromWhenCtxAnsweredTheOrder() = runBlocking {
        paymentRequestAsksFor(Dash.valueOf(316_666_666))
        val viewModel = viewModel()
        val shown = viewModel.shownFor100Pounds()
        api.stub {
            onBlocking { purchaseGiftCard(any()) } doReturn GiftCardResponse(
                id = "order-1",
                status = "unpaid",
                cryptoAmount = "3.16666666",
                fiatAmount = "95.00",
                fiatCurrency = "GBP",
                paymentUrlExpiration = "2020-01-01T10:15:00Z",
                created = "2020-01-01T10:00:00Z"
            )
        }

        val order = viewModel.purchaseGiftCard(shown)
        elapsedMillis += 15.minutes.inWholeMilliseconds + 1

        assertRefusedAsExpired { viewModel.payCtxOrder(shown, "dash:?r=x", order) }
        assertEquals(emptyList<Dash?>(), sentPayments)
    }

    @Test
    fun priceQuestionAnsweredAfterTheOrderRanOut_isNotPaid() = runBlocking {
        paymentRequestAsksFor(Dash.valueOf(326_666_667))
        val viewModel = viewModel()
        val shown = viewModel.shownFor100Pounds()
        assertNull(viewModel.payCtxOrder(shown, "dash:?r=x", orderOpenFor15Minutes()))

        elapsedMillis += 15.minutes.inWholeMilliseconds + 1

        assertRefusedAsExpired { viewModel.payAcceptedPrice() }
        assertEquals(emptyList<Dash?>(), sentPayments)
    }

    @Test
    fun orderWithoutCtxOwnStartTime_isLeftForCtxToTurnDown() = runBlocking {
        paymentRequestAsksFor(Dash.valueOf(316_666_666))
        val viewModel = viewModel()
        val shown = viewModel.shownFor100Pounds()
        val order = orderOpenFor15Minutes(created = null)
        elapsedMillis += 60.minutes.inWholeMilliseconds

        assertEquals(zeroTxId, viewModel.payCtxOrder(shown, "dash:?r=x", order))
    }

    @Test
    fun rateFromWhenTheScreenOpened_isNotWhatAMarketMoveIsJudgedBy() = runBlocking {
        // the screen still holds 30; the market, and with it CTX, has moved to 29: 3.4% more Dash
        freshRates["GBP"] = ExchangeRate("GBP", "29")

        val (txId, asked) = payOrderCharged("3.27586206")

        assertEquals(zeroTxId, txId)
        assertEquals(emptyList<GiftCardPriceChange>(), asked)
    }

    private suspend fun DashSpendViewModel.heldPriceQuestion(): GiftCardPriceChange {
        paymentRequestAsksFor(Dash.valueOf(326_666_667))
        val shown = shownFor100Pounds()
        val order = listOf(GiftCardInfo("order-1", cryptoAmount = "3.16666666", fiatAmount = "95.00"))
        assertTrue(tryStartSubmission())
        assertNull(payOrder(shown, "dash:?r=https://ctx.example/pay", order))
        return checkNotNull(priceChange.value)
    }

    @Test
    fun acceptedPrice_isPaidByTheViewModelWithNoScreenLeftToWaitForIt() = runBlocking {
        val viewModel = viewModel()
        val change = viewModel.heldPriceQuestion()

        // nothing collects or awaits this but the view model's own scope
        viewModel.acceptPriceChange(change).join()

        assertEquals(listOf<Dash?>(Dash.valueOf(326_666_667)), sentPayments)
        assertEquals(PaidOrder(zeroTxId, false), viewModel.paidOrder.value)
        assertNull(viewModel.priceChange.value)
        assertNull(viewModel.paymentFailure.value)
    }

    @Test
    fun acceptedPriceThatCannotBePaid_isReportedOnceToWhicheverScreenIsLeft() = runBlocking {
        val viewModel = viewModel()
        val change = viewModel.heldPriceQuestion()
        payments.stub {
            onBlocking {
                payWithDashUrl(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
            } doSuspendableAnswer { throw IOException("offline") }
        }

        viewModel.acceptPriceChange(change).join()

        val failure = checkNotNull(viewModel.paymentFailure.value)
        assertTrue(viewModel.takePaymentFailure(failure))
        assertFalse(viewModel.takePaymentFailure(failure))
        assertNull(viewModel.paidOrder.value)
    }

    @Test
    fun newPurchaseWhileAPriceQuestionIsOpen_endsItUnpaidAndSaysSo() = runBlocking {
        val viewModel = viewModel()
        val change = viewModel.heldPriceQuestion()

        // the sheet of another merchant opens
        viewModel.beginNewPurchase()

        assertNull(viewModel.priceChange.value)
        assertEquals(change, viewModel.droppedPriceChange.value)
        assertTrue(viewModel.takeDroppedPriceChange(change))
        assertFalse(viewModel.takeDroppedPriceChange(change))
        // a Pay still on screen for the old question pays nothing
        viewModel.acceptPriceChange(change).join()
        assertEquals(emptyList<Dash?>(), sentPayments)
        assertNull(viewModel.paidOrder.value)
        assertNull(viewModel.paymentFailure.value)
    }

    @Test
    fun priceQuestion_isOnlyAnsweredForTheOrderItWasAskedAbout() = runBlocking {
        val viewModel = viewModel()
        val change = viewModel.heldPriceQuestion()

        assertNull(viewModel.payAcceptedPrice(change.copy(orderId = "order-2")))

        assertEquals(emptyList<Dash?>(), sentPayments)
        assertEquals(change, viewModel.priceChange.value)
    }

    @Test
    fun leavingThePurchaseWithAPriceQuestionOpen_endsItUnpaidAndSaysSo() = runBlocking {
        val viewModel = viewModel()
        val change = viewModel.heldPriceQuestion()

        viewModel.dropPriceChange()

        assertNull(viewModel.priceChange.value)
        assertFalse(viewModel.isPurchaseOutstanding)
        assertEquals(change, viewModel.droppedPriceChange.value)
        assertEquals(emptyList<Dash?>(), sentPayments)
    }

    @Test
    fun priceQuestionTheBuyerDeclined_needsNoFurtherNotice() = runBlocking {
        val viewModel = viewModel()
        viewModel.heldPriceQuestion()

        viewModel.declinePriceChange()
        viewModel.dropPriceChange()

        assertNull(viewModel.droppedPriceChange.value)
    }

    @Test
    fun providerSwitchedAfterTheTap_doesNotTakeTheCtxOrderPastThePriceCheck() = runBlocking {
        paymentRequestAsksFor(Dash.valueOf(326_666_667))
        val viewModel = viewModel()
        val shown = viewModel.shownFor100Pounds()
        val order = listOf(GiftCardInfo("order-1", cryptoAmount = "3.26666667", fiatAmount = "95.00"))
        assertTrue(viewModel.tryStartSubmission())

        viewModel.selectedProvider = GiftCardProviderType.PiggyCards

        assertNull(viewModel.payOrder(shown, "dash:?r=x", order))
        assertEquals(emptyList<Dash?>(), sentPayments)
        assertEquals("order-1", viewModel.priceChange.value?.orderId)
    }

    @Test
    fun acceptedPrice_isACeilingWithNoAllowanceOnTop() = runBlocking {
        // one duff more than the buyer just said yes to, far inside the 0.5% a shown price allows
        val invoiced = Dash.valueOf(326_666_668)
        val (txId, asked) = payOrderCharged("3.26666667", acceptsHigherPrice = true, invoiced = invoiced)

        assertEquals(zeroTxId, txId)
        assertEquals(listOf(Dash.valueOf(326_666_667), Dash.valueOf(326_666_668)), asked.map { it.charged })
        assertEquals(listOf<Dash?>(Dash.valueOf(326_666_668)), sentPayments)
    }

    @Test
    fun confirmWithoutAFreshRate_createsNoOrder() = runBlocking {
        val viewModel = viewModel()
        val shown = viewModel.shownFor100Pounds()
        currenciesWithoutAFreshRate += "GBP"

        val refusal = assertThrows(CTXSpendException::class.java) {
            runBlocking { viewModel.purchaseGiftCard(shown) }
        }

        assertEquals(R.string.exchange_rate_not_found, refusal.resourceString?.resourceId)
        verifyBlocking(api, never()) { purchaseGiftCard(any()) }
    }

    @Test
    fun rateTakenAtConfirm_isTheOneTheOrderIsCheckedWith() = runBlocking {
        paymentRequestAsksFor(Dash.valueOf(350_000_000))
        val viewModel = viewModel()
        val shown = viewModel.shownFor100Pounds()
        api.stub {
            onBlocking { purchaseGiftCard(any()) } doReturn GiftCardResponse(
                id = "order-1",
                status = "unpaid",
                cryptoAmount = "3.5",
                fiatAmount = "95.00",
                fiatCurrency = "GBP"
            )
        }
        val order = viewModel.purchaseGiftCard(shown)

        // at 20 the 95 pounds would come to 4.75 Dash, and 3.5 would pass without a question
        freshRates["GBP"] = ExchangeRate("GBP", "20")

        assertNull(viewModel.payCtxOrder(shown, "dash:?r=x", order))
        assertEquals(Dash.valueOf(316_666_666), viewModel.priceChange.value?.shown)
        assertEquals(emptyList<Dash?>(), sentPayments)
    }

    private fun ctxRefusesTheMerchantWith(code: Int) = api.stub {
        onBlocking { getMerchant(any()) } doAnswer {
            throw CTXSpendException("CTXSpend error $code", "CTXSpend", code, "")
        }
    }

    @Test
    fun firstLoadRefusedForAnExpiredSession_asksToSignInAgain() = runBlocking {
        ctxRefusesTheMerchantWith(401)
        setRate("USD", "25")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals(GiftCardPurchaseBlock.SIGNED_OUT, viewModel.purchaseBlock)
        assertPurchaseRefused(viewModel)
    }

    @Test
    fun firstLoadOfAMerchantCtxNoLongerHas_isNotSoldRatherThanUnreachable() = runBlocking {
        ctxRefusesTheMerchantWith(404)
        setRate("USD", "25")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals(GiftCardPurchaseBlock.NOT_SOLD, viewModel.purchaseBlock)
    }

    @Test
    fun firstLoadMetWithAServerError_canBeTriedAgain() = runBlocking {
        ctxRefusesTheMerchantWith(503)
        setRate("USD", "25")
        val viewModel = viewModel()

        viewModel.openCtxMerchant()

        assertEquals(GiftCardPurchaseBlock.CURRENCY_UNKNOWN, viewModel.purchaseBlock)
    }
}
