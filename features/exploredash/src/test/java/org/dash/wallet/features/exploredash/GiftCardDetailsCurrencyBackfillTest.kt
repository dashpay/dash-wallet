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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionCategory
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.network.authenticator.TokenAuthenticator
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.GiftCardDetailsViewModel
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A card saved before currencies were recorded, or restored on another device, has none. Opening
 * its details asks CTX once, whether or not the card still needs polling for its number.
 */
@ExperimentalCoroutinesApi
class GiftCardDetailsCurrencyBackfillTest {
    @get:Rule var rule: TestRule = InstantTaskExecutorRule()

    // one scheduler for the view model's scope and its lookups: nothing runs on another thread or in real time
    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule var mainCoroutineRule = MainCoroutineRule(dispatcher)

    private val txId: Sha256Hash =
        Sha256Hash.wrap("00000000000000000000000000000000000000000000000000000000000000a1")

    // already delivered, so nothing polls for it
    private val legacyCard =
        GiftCard(txId = txId, merchantName = "Acme", price = 23.75, number = "1234", note = "order-1")

    private val ctxMetadata = TransactionMetadata(
        txId = txId,
        timestamp = 1_000L,
        value = Coin.COIN,
        type = TransactionCategory.Sent,
        taxCategory = TaxCategory.Expense,
        service = ServiceName.CTXSpend
    )
    private val metadata = MutableStateFlow<TransactionMetadata?>(ctxMetadata)
    private val blankReplies = AtomicInteger()
    private val orderLookups = AtomicInteger()
    private val accessToken = AtomicReference<String?>()
    private lateinit var api: CTXSpendApi

    private fun open(card: GiftCard, accessToken: String?, failingLookups: Int = 0): GiftCardDao =
        open(flowOf(listOf(card)), card, accessToken, failingLookups)

    private fun open(
        emissions: Flow<List<GiftCard>>,
        card: GiftCard,
        accessToken: String?,
        failingLookups: Int = 0
    ): GiftCardDao {
        val dao = mock<GiftCardDao> {
            on { observeCardForTransaction(eq(txId)) } doReturn emissions
            onBlocking { getCardForTransaction(eq(txId)) } doReturn listOf(card)
            onBlocking { backfillCurrency(any(), any()) } doReturn 1
        }
        val config = mock<CTXSpendConfig> {
            on { observeSecureData(any()) } doReturn emptyFlow()
            onBlocking { getSecuredData(eq(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN)) } doAnswer {
                this@GiftCardDetailsCurrencyBackfillTest.accessToken.get()
            }
        }
        this.accessToken.set(accessToken)
        api = mock<CTXSpendApi> {
            onBlocking { getGiftCardByOrderId(eq("order-1")) } doAnswer {
                if (orderLookups.incrementAndGet() <= failingLookups) {
                    throw IOException("offline")
                }
                GiftCardResponse(
                    id = "order-1",
                    status = "fulfilled",
                    fiatAmount = "23.75",
                    fiatCurrency = if (blankReplies.getAndDecrement() > 0) " " else "GBP"
                )
            }
        }
        val metadataProvider = mock<TransactionMetadataProvider> {
            on { observeTransactionMetadata(eq(txId)) } doReturn metadata
            onBlocking { getTransactionMetadata(eq(txId)) } doAnswer { metadata.value }
        }

        GiftCardDetailsViewModel(
            mock(),
            CoroutineScope(Dispatchers.Unconfined),
            dao,
            metadataProvider,
            mock(),
            CTXSpendRepository(api, config, TokenAuthenticator(mock(), config)),
            mock(),
            mock(),
            config
        ).apply { currencyLookupDispatcher = dispatcher }.init(txId)
        return dao
    }

    private fun allAttemptsMade() = dispatcher.scheduler.advanceUntilIdle()

    @Test
    fun cardWithoutACurrency_isBackfilledWhenItsDetailsOpen() {
        val dao = open(legacyCard, accessToken = "token")

        verifyBlocking(dao) { backfillCurrency(eq(txId), eq("GBP")) }
    }

    @Test
    fun cardWithACurrency_isLeftAlone() {
        val dao = open(legacyCard.copy(currency = "EUR"), accessToken = "token")
        allAttemptsMade()

        verifyBlocking(dao, never()) { backfillCurrency(any(), any()) }
        assertEquals(0, orderLookups.get())
    }

    @Test
    fun signedOut_keepsTheCardWithoutACurrency() {
        val dao = open(legacyCard, accessToken = null)
        allAttemptsMade()

        verifyBlocking(dao, never()) { backfillCurrency(any(), any()) }
        assertEquals(0, orderLookups.get())
    }

    @Test
    fun signedOut_looksTheCurrencyUpOnceSignedIn() {
        val emissions = MutableStateFlow(listOf(legacyCard))
        val dao = open(emissions, legacyCard, accessToken = null)
        allAttemptsMade()

        accessToken.set("token")
        emissions.value = listOf(legacyCard.copy(pin = "1"))

        verifyBlocking(dao) { backfillCurrency(eq(txId), eq("GBP")) }
    }

    @Test
    fun failedLookup_isRepeatedWhileTheDetailsStayOpen() {
        val dao = open(legacyCard, accessToken = "token", failingLookups = 1)
        assertEquals(1, orderLookups.get())
        verifyBlocking(dao, never()) { backfillCurrency(any(), any()) }

        allAttemptsMade()

        verifyBlocking(dao) { backfillCurrency(eq(txId), eq("GBP")) }
        assertEquals(2, orderLookups.get())
    }

    @Test
    fun lookupThatKeepsFailing_stopsAfterThreeAttempts() {
        val dao = open(legacyCard, accessToken = "token", failingLookups = 10)

        allAttemptsMade()

        assertEquals(3, orderLookups.get())
        verifyBlocking(dao, never()) { backfillCurrency(any(), any()) }
    }

    @Test
    fun replyWithoutACurrency_isAskedAgain() {
        blankReplies.set(1)
        val dao = open(legacyCard, accessToken = "token")

        allAttemptsMade()

        verifyBlocking(dao) { backfillCurrency(eq(txId), eq("GBP")) }
        assertEquals(2, orderLookups.get())
    }

    @Test
    fun cardWhoseTransactionIsNotKnownYet_isLookedUpWhenItArrives() {
        metadata.value = null
        val dao = open(legacyCard, accessToken = "token")
        // every attempt is over, so only the arrival itself can start another lookup
        allAttemptsMade()
        assertEquals(0, orderLookups.get())

        metadata.value = ctxMetadata

        verifyBlocking(dao) { backfillCurrency(eq(txId), eq("GBP")) }
    }

    @Test
    fun cardOfAnotherProvider_isNeverLookedUpAtCtx() {
        metadata.value = ctxMetadata.copy(service = ServiceName.PiggyCards)
        val emissions = MutableStateFlow(listOf(legacyCard))
        open(emissions, legacyCard, accessToken = "token")

        emissions.value = listOf(legacyCard.copy(pin = "1"))
        allAttemptsMade()

        assertEquals(0, orderLookups.get())
    }

    @Test
    fun successfulLookup_isNotRepeated() {
        val emissions = MutableStateFlow(listOf(legacyCard))
        val dao = open(emissions, legacyCard, accessToken = "token")
        verifyBlocking(dao) { backfillCurrency(eq(txId), eq("GBP")) }

        emissions.value = listOf(legacyCard.copy(pin = "1"))
        emissions.value = listOf(legacyCard.copy(pin = "2"))
        allAttemptsMade()

        assertEquals(1, orderLookups.get())
    }
}
