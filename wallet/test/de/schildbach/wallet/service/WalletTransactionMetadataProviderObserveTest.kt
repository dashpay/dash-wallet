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
package de.schildbach.wallet.service

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.AddressMetadataDao
import de.schildbach.wallet.database.dao.IconBitmapDao
import de.schildbach.wallet.database.dao.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.database.dao.TransactionMetadataDao
import de.schildbach.wallet.database.dao.TransactionMetadataDocumentDao
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.util.toTxId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.transactions.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-trip coverage for the transaction-metadata store → observe pipeline that
 * drives the tx-detail sheet's "Tax Category" field.
 *
 * Regression context: the detail sheet was stuck on "Loading" because the field
 * is only set once [WalletTransactionMetadataProvider.observeTransactionMetadata]
 * emits a row for the tx id. These tests pin that a row stored under a tx id is
 * observed back under the SAME id (no wire-order vs display-order drift), and that
 * a dashj `Sha256Hash` must be reconciled with the neutral [TxId] via `toTxId()`
 * rather than a cross-type `==` (the exact trap that broke the sheet after the
 * Step A `Sha256Hash` → `TxId` migration).
 */
class WalletTransactionMetadataProviderObserveTest {

    private lateinit var metadataDao: TransactionMetadataDao
    private lateinit var provider: WalletTransactionMetadataProvider

    // In-memory backing store so insert/load/observe behave like a real DAO.
    private val store = mutableMapOf<TxId, TransactionMetadata>()
    private val flows = mutableMapOf<TxId, MutableStateFlow<TransactionMetadata?>>()

    private fun flowFor(txId: TxId) = flows.getOrPut(txId) { MutableStateFlow(store[txId]) }

    @Before
    fun setUp() {
        metadataDao = mockk(relaxed = true)
        coEvery { metadataDao.insert(any()) } answers {
            val m = firstArg<TransactionMetadata>()
            store[m.txId] = m
            flowFor(m.txId).value = m
        }
        coEvery { metadataDao.load(any<TxId>()) } answers { store[firstArg()] }
        every { metadataDao.observe(any()) } answers { flowFor(firstArg()) }
        // Mutate the backing store and re-emit a fresh instance so observe (which
        // is distinctUntilChanged) sees the change — like a real DAO UPDATE.
        coEvery { metadataDao.updateTaxCategory(any(), any()) } answers {
            val id = firstArg<TxId>()
            store[id]?.let {
                val updated = it.copy(taxCategory = secondArg<TaxCategory>())
                store[id] = updated
                flowFor(id).value = updated
            }
        }
        coEvery { metadataDao.updateMemo(any(), any()) } answers {
            val id = firstArg<TxId>()
            store[id]?.let {
                val updated = it.copy(memo = secondArg<String>())
                store[id] = updated
                flowFor(id).value = updated
            }
        }

        // Simulate a wallet that holds NONE of these transactions (the SDK-only
        // case): getTransaction returns null, so insertTransactionMetadata cannot
        // derive a row and the provider must use the caller-supplied fallback.
        // Give it a REAL testnet Context: insertTransactionMetadata calls
        // Context.propagate(wallet.context), and a relaxed mock context carries
        // mock NetworkParameters that would pollute bitcoinj's thread-local
        // context and break unrelated tests sharing the JVM.
        val wallet = mockk<org.bitcoinj.wallet.Wallet>(relaxed = true)
        every { wallet.getTransaction(any()) } returns null
        every { wallet.context } returns org.bitcoinj.core.Context(org.bitcoinj.params.TestNet3Params.get())
        val walletData = mockk<WalletData>(relaxed = true)
        every { walletData.wallet } returns wallet

        provider = WalletTransactionMetadataProvider(
            transactionMetadataDao = metadataDao,
            addressMetadataDao = mockk<AddressMetadataDao>(relaxed = true),
            iconBitmapDao = mockk<IconBitmapDao>(relaxed = true),
            walletData = walletData,
            giftCardDao = mockk(relaxed = true),
            swapOrderDao = mockk(relaxed = true),
            transactionMetadataChangeCacheDao = mockk<TransactionMetadataChangeCacheDao>(relaxed = true),
            transactionMetadataDocumentDao = mockk<TransactionMetadataDocumentDao>(relaxed = true),
            swapOrderDao = mockk(relaxed = true),
            dashPayConfig = mockk<DashPayConfig>(relaxed = true)
        )
    }

    private fun metadataFor(txId: TxId, received: Boolean) = TransactionMetadata(
        txId,
        timestamp = 1_700_000_000_000L,
        value = if (received) Coin.valueOf(100_000) else Coin.valueOf(-100_000),
        type = if (received) TransactionCategory.Received else TransactionCategory.Sent
    )

    @Test
    fun `stored metadata is observed back under the same txid`() = runTest {
        val txId = Sha256Hash.of("round-trip-tx".toByteArray()).toTxId()

        provider.setTransactionMetadata(metadataFor(txId, received = true))

        val observed = provider.observeTransactionMetadata(txId).first()

        assertNotNull("observe must emit the stored row, not null (the 'Loading' bug)", observed)
        assertEquals("observed row must carry the same txid it was stored under", txId, observed!!.txId)
        assertTrue("the round-tripped metadata must be non-empty", observed.isNotEmpty())
    }

    @Test
    fun `dashj Sha256Hash reconciles with the neutral metadata TxId only via toTxId`() = runTest {
        val hash = Sha256Hash.of("cross-type-tx".toByteArray())
        provider.setTransactionMetadata(metadataFor(hash.toTxId(), received = false))
        val observed = provider.observeTransactionMetadata(hash.toTxId()).first()!!

        // The trap that stuck the sheet on "Loading": a dashj Sha256Hash and a
        // neutral TxId are unrelated types, so `==` is always false even for the
        // very same transaction.
        @Suppress("EqualsBetweenInconvertibleTypes")
        val crossType: Boolean = (hash as Any) == (observed.txId as Any)
        assertFalse("cross-type Sha256Hash == TxId must be false (documents the regression)", crossType)

        // The fix: convert the dashj hash to a TxId before comparing.
        assertTrue("Sha256Hash.toTxId() must equal the metadata TxId", hash.toTxId() == observed.txId)
        assertEquals("display-order hex must match across the two types", hash.toString(), observed.txId.toString())
    }

    @Test
    fun `setTransactionTaxCategory on a tx with no dashj wallet tx persists via the fallback row`() = runTest {
        // walletData is a relaxed mock, so wallet.getTransaction(...) returns null:
        // insertTransactionMetadata cannot build a row (no dashj Transaction), the exact
        // SDK-only gap that dropped the toggle. The provider must fall back to the caller's
        // minimal row (txid + value/type from the SDK detail) instead.
        val txId = Sha256Hash.of("sdk-only-toggle-tx".toByteArray()).toTxId()
        val fallback = metadataFor(txId, received = true) // default would be Income

        assertNull("precondition: no row for this SDK-only tx yet", store[txId])

        provider.setTransactionTaxCategory(txId, TaxCategory.Expense, fallbackMetadata = fallback)

        val observed = provider.observeTransactionMetadata(txId).first()
        assertNotNull("a fallback row must be created so the stream emits", observed)
        assertEquals("the user-selected category must persist", TaxCategory.Expense, observed!!.taxCategory)
        assertEquals("the persisted row must keep the SDK-only txid", txId, observed.txId)
    }

    @Test
    fun `setTransactionTaxCategory with no wallet tx and no fallback is a no-op`() = runTest {
        // Guard: without a fallback the provider still cannot invent a row, so nothing
        // is persisted (dashj behavior is unchanged — those callers pass no fallback).
        val txId = Sha256Hash.of("no-fallback-tx".toByteArray()).toTxId()

        provider.setTransactionTaxCategory(txId, TaxCategory.Expense)

        assertNull("no row may be created without a wallet tx or a fallback", store[txId])
    }

    @Test
    fun `setTransactionMemo on a tx with no dashj wallet tx persists via the fallback row`() = runTest {
        val txId = Sha256Hash.of("sdk-only-memo-tx".toByteArray()).toTxId()
        val fallback = metadataFor(txId, received = false)

        provider.setTransactionMemo(txId, "coffee", fallbackMetadata = fallback)

        val observed = provider.observeTransactionMetadata(txId).first()
        assertNotNull(observed)
        assertEquals("the memo must persist for the SDK-only tx", "coffee", observed!!.memo)
    }
}
