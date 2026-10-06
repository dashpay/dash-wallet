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
import de.schildbach.wallet.database.dao.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.database.dao.TransactionMetadataDao
import de.schildbach.wallet.database.dao.TransactionMetadataDocumentDao
import de.schildbach.wallet.service.platform.sdk.SdkTxMetadataSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.transactions.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Metadata for a transaction that only the Kotlin SDK's store holds.
 *
 * Field case (2026-10-02, topple testnet, int28): the DashPay one-way-contact
 * receive e5169bfc… was found by the SDK's contact backfill and stored in the SDK's
 * transactions/txos tables, but never reached the held dashj wallet. Opening its
 * details logged "txmetadata for e5169bfc… DROPPED — no wallet tx and no fallback
 * row", so its memo, exchange rate and other platform metadata were never shown.
 */
class WalletTransactionMetadataProviderSdkStoreTest {

    private val sdkOnlyTxId =
        TxId.wrap("e5169bfc4989585abd4b0476188611b981e3c750539da5b8a39fe135e3bbb957")
    private val unknownTxId =
        TxId.wrap("00000000000000000000000000000000000000000000000000000000000000aa")

    /** What the SDK store yields for the field tx: +100000 duffs received. */
    private val sdkRow = TransactionMetadata(
        sdkOnlyTxId,
        timestamp = 1_759_400_000_000L,
        value = Coin.valueOf(100_000),
        type = TransactionCategory.Received
    )

    private val store = mutableMapOf<TxId, TransactionMetadata>()
    private lateinit var metadataDao: TransactionMetadataDao
    private lateinit var documentDao: TransactionMetadataDocumentDao
    private lateinit var changeCacheDao: TransactionMetadataChangeCacheDao
    private lateinit var provider: WalletTransactionMetadataProvider

    @Before
    fun setUp() {
        metadataDao = mockk(relaxed = true)
        coEvery { metadataDao.insert(any()) } answers {
            val m = firstArg<TransactionMetadata>()
            store[m.txId] = m
        }
        coEvery { metadataDao.load(any<TxId>()) } answers { store[firstArg()] }
        coEvery { metadataDao.updateMemo(any(), any()) } answers {
            val id = firstArg<TxId>()
            store[id]?.let { store[id] = it.copy(memo = secondArg()) }
        }

        // Platform metadata documents fetched for the SDK-only tx. Nothing for
        // any other txid (relaxed mock returns null for nullable results).
        documentDao = mockk(relaxed = true)
        coEvery { documentDao.getTransactionMemo(sdkOnlyTxId) } returns "rent share"
        coEvery { documentDao.getTransactionExchangeRate(sdkOnlyTxId) } returns ExchangeRate("USD", "24.50")
        coEvery { documentDao.getTransactionService(sdkOnlyTxId) } returns null
        coEvery { documentDao.getTransactionTaxCategory(sdkOnlyTxId) } returns TaxCategory.Income.name
        coEvery { documentDao.getSentTimestamp(sdkOnlyTxId) } returns 1_759_399_000_000L
        coEvery { documentDao.getTransactionIconUrl(any()) } returns null
        coEvery { documentDao.getMerchantName(any()) } returns null
        coEvery { documentDao.getGiftCardNumber(any()) } returns null
        coEvery { documentDao.getGiftCardPin(any()) } returns null
        coEvery { documentDao.getBarcodeValue(any()) } returns null
        coEvery { documentDao.getBarcodeFormat(any()) } returns null

        changeCacheDao = mockk(relaxed = true)

        // The held dashj wallet holds neither transaction. Real testnet Context:
        // insertTransactionMetadata propagates it, and a mock one would pollute
        // bitcoinj's thread-local context for other tests in the JVM.
        val wallet = mockk<org.bitcoinj.wallet.Wallet>(relaxed = true)
        every { wallet.getTransaction(any()) } returns null
        every { wallet.context } returns org.bitcoinj.core.Context(org.bitcoinj.params.TestNet3Params.get())
        val walletData = mockk<WalletData>(relaxed = true)
        every { walletData.wallet } returns wallet

        val sdkStore = object : SdkTxMetadataSource {
            override suspend fun defaultMetadataFor(txId: TxId): TransactionMetadata? =
                sdkRow.takeIf { txId == sdkOnlyTxId }?.copy()
        }

        provider = WalletTransactionMetadataProvider(
            transactionMetadataDao = metadataDao,
            addressMetadataDao = mockk(relaxed = true),
            iconBitmapDao = mockk(relaxed = true),
            walletData = walletData,
            giftCardDao = mockk(relaxed = true),
            swapOrderDao = mockk(relaxed = true),
            transactionMetadataChangeCacheDao = changeCacheDao,
            transactionMetadataDocumentDao = documentDao,
            dashPayConfig = mockk(relaxed = true),
            sdkTxMetadataSource = sdkStore
        )
    }

    @Test
    fun `should keep platform metadata for a tx present only in the SDK store`() = runTest {
        // What the tx-detail sheet does on open.
        provider.importTransactionMetadata(sdkOnlyTxId)

        val row = store[sdkOnlyTxId]
        assertNotNull("SDK-only tx must get a metadata row, not be DROPPED", row)
        row!!
        // From the SDK store.
        assertEquals(Coin.valueOf(100_000), row.value)
        assertEquals(TransactionCategory.Received, row.type)
        // From the platform metadata documents.
        assertEquals("rent share", row.memo)
        assertEquals("USD", row.currencyCode)
        assertEquals("24.50", row.rate)
        assertEquals(TaxCategory.Income, row.taxCategory)
        assertEquals(1_759_399_000_000L, row.timestamp)
        // Fetched from platform, so nothing to publish back.
        coVerify(exactly = 0) { changeCacheDao.insert(any()) }
    }

    @Test
    fun `should return metadata for a tx present only in the SDK store`() = runTest {
        // The private-memo screen reads through getTransactionMetadata.
        val row = provider.getTransactionMetadata(sdkOnlyTxId)

        assertNotNull(row)
        assertEquals("rent share", row!!.memo)
        assertEquals(Coin.valueOf(100_000), row.value)
    }

    @Test
    fun `should persist a memo edit for an SDK-store tx without a caller fallback`() = runTest {
        provider.setTransactionMemo(sdkOnlyTxId, "edited")

        assertEquals("edited", store[sdkOnlyTxId]?.memo)
        assertEquals(Coin.valueOf(100_000), store[sdkOnlyTxId]?.value)
    }

    @Test
    fun `should prefer the SDK store row over the caller fallback`() = runTest {
        // A caller fallback built without the SDK detail (e.g. a platform row)
        // carries no real value; the SDK store knows the amount.
        val callerFallback = TransactionMetadata(
            sdkOnlyTxId,
            timestamp = 0L,
            value = Coin.ZERO,
            type = TransactionCategory.Received
        )

        provider.setTransactionMemo(sdkOnlyTxId, "edited", fallbackMetadata = callerFallback)

        assertEquals(Coin.valueOf(100_000), store[sdkOnlyTxId]?.value)
    }

    @Test
    fun `should still use the caller fallback when the SDK store lacks the tx`() = runTest {
        val callerFallback = TransactionMetadata(
            unknownTxId,
            timestamp = 1L,
            value = Coin.valueOf(-5_000),
            type = TransactionCategory.Sent
        )

        provider.setTransactionMemo(unknownTxId, "note", fallbackMetadata = callerFallback)

        assertEquals("note", store[unknownTxId]?.memo)
        assertEquals(Coin.valueOf(-5_000), store[unknownTxId]?.value)
    }

    @Test
    fun `should drop metadata only when neither store holds the tx and there is no fallback`() = runTest {
        provider.importTransactionMetadata(unknownTxId)

        assertNull(store[unknownTxId])
        coVerify(exactly = 0) { metadataDao.insert(match { it.txId == unknownTxId }) }
    }
}
