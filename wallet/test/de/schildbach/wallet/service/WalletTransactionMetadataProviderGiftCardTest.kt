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

import de.schildbach.wallet.database.dao.AddressMetadataDao
import de.schildbach.wallet.database.dao.IconBitmapDao
import de.schildbach.wallet.database.dao.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.database.dao.TransactionMetadataDao
import de.schildbach.wallet.database.dao.TransactionMetadataDocumentDao
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [WalletTransactionMetadataProvider.updateGiftCardMetadata].
 *
 * The DAO's @Update rewrites every column, so this method has to merge against the
 * stored row — otherwise a caller passing a partial GiftCard (e.g. only number/pin
 * after fulfillment) would null out fields it didn't set (note, merchantUrl, ...).
 */
class WalletTransactionMetadataProviderGiftCardTest {

    private lateinit var giftCardDao: GiftCardDao
    private lateinit var cacheDao: TransactionMetadataChangeCacheDao
    private lateinit var provider: WalletTransactionMetadataProvider

    private val txId: Sha256Hash =
        Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001")

    @Before
    fun setUp() {
        giftCardDao = mockk(relaxed = true)
        cacheDao = mockk(relaxed = true)
        provider = WalletTransactionMetadataProvider(
            transactionMetadataDao = mockk(relaxed = true),
            addressMetadataDao = mockk<AddressMetadataDao>(relaxed = true),
            iconBitmapDao = mockk<IconBitmapDao>(relaxed = true),
            walletData = mockk<WalletDataProvider>(relaxed = true),
            giftCardDao = giftCardDao,
            transactionMetadataChangeCacheDao = cacheDao,
            transactionMetadataDocumentDao = mockk<TransactionMetadataDocumentDao>(relaxed = true),
            dashPayConfig = mockk<DashPayConfig>(relaxed = true)
        )
    }

    @Test
    fun `partial update preserves existing note, merchantUrl, barcode and merchantName`() = runTest {
        // Existing row carries the full set of fields written at purchase time
        // (note = order id, merchantUrl = redeem url) plus a previously stored barcode.
        val existing = GiftCard(
            txId = txId,
            merchantName = "Acme",
            price = 25.0,
            number = null,
            pin = null,
            barcodeValue = "stored-barcode",
            barcodeFormat = null,
            merchantUrl = "https://redeem.example/abc",
            note = "order-123",
            index = 0
        )
        coEvery { giftCardDao.getCardForTransaction(txId) } returns listOf(existing)

        // Fulfillment writes only the newly-arrived number/pin.
        val incoming = existing.copy(
            number = "1234-5678",
            pin = "9999",
            // simulate the unsafe-caller pattern: these are null on the incoming object
            barcodeValue = null,
            merchantUrl = null,
            note = null
        )

        val captured = slot<GiftCard>()
        coEvery { giftCardDao.updateGiftCard(capture(captured)) } returns 1

        provider.updateGiftCardMetadata(incoming)

        val merged = captured.captured
        assertEquals("number should be updated", "1234-5678", merged.number)
        assertEquals("pin should be updated", "9999", merged.pin)
        assertEquals("note must be preserved", "order-123", merged.note)
        assertEquals("merchantUrl must be preserved", "https://redeem.example/abc", merged.merchantUrl)
        assertEquals("barcodeValue must be preserved", "stored-barcode", merged.barcodeValue)
        assertEquals("merchantName must be preserved", "Acme", merged.merchantName)
        assertEquals("price must be preserved", 25.0, merged.price, 0.0)
        assertEquals("index must be preserved", 0, merged.index)

        // No insert path when the row already exists.
        coVerify(exactly = 0) { giftCardDao.insertGiftCard(any()) }
    }

    @Test
    fun `empty merchantName and zero price on incoming fall back to existing values`() = runTest {
        val existing = GiftCard(
            txId = txId,
            merchantName = "Acme",
            price = 25.0,
            number = "1111",
            pin = "2222",
            barcodeValue = null,
            barcodeFormat = null,
            merchantUrl = "https://x",
            note = "order-7",
            index = 0
        )
        coEvery { giftCardDao.getCardForTransaction(txId) } returns listOf(existing)

        // Caller hands us defaults (merchantName="", price=0.0) without realising they would
        // clobber the stored merchant info.
        val incoming = GiftCard(
            txId = txId,
            merchantName = "",
            price = 0.0,
            number = null,
            pin = null,
            barcodeValue = null,
            barcodeFormat = null,
            merchantUrl = null,
            note = null,
            index = 0
        )

        val captured = slot<GiftCard>()
        coEvery { giftCardDao.updateGiftCard(capture(captured)) } returns 1

        provider.updateGiftCardMetadata(incoming)

        val merged = captured.captured
        assertEquals("Acme", merged.merchantName)
        assertEquals(25.0, merged.price, 0.0)
        assertEquals("1111", merged.number)
        assertEquals("2222", merged.pin)
        assertEquals("https://x", merged.merchantUrl)
        assertEquals("order-7", merged.note)
    }

    @Test
    fun `insert path is used when no existing row matches the index`() = runTest {
        // No local row for this (txId, index) yet — e.g. the dummy row wasn't saved.
        coEvery { giftCardDao.getCardForTransaction(txId) } returns emptyList()

        val incoming = GiftCard(
            txId = txId,
            merchantName = "Acme",
            price = 25.0,
            number = "1234",
            pin = "5678",
            barcodeValue = null,
            barcodeFormat = null,
            merchantUrl = null,
            note = "order-fresh",
            index = 0
        )

        val captured = slot<GiftCard>()
        coEvery { giftCardDao.insertGiftCard(capture(captured)) } returns Unit

        provider.updateGiftCardMetadata(incoming)

        val inserted = captured.captured
        assertEquals("Acme", inserted.merchantName)
        assertEquals("1234", inserted.number)
        assertEquals("5678", inserted.pin)
        assertEquals("order-fresh", inserted.note)
        assertNull(inserted.merchantUrl)

        coVerify(exactly = 0) { giftCardDao.updateGiftCard(any()) }
    }

    @Test
    fun `merge respects the index when multiple cards exist for the same txId`() = runTest {
        // Multi-card PiggyCards order — two rows distinguished only by index.
        val card0 = GiftCard(
            txId = txId, merchantName = "Acme", price = 25.0,
            number = null, pin = null,
            barcodeValue = null, barcodeFormat = null,
            merchantUrl = "https://redeem/0", note = "order-multi", index = 0
        )
        val card1 = GiftCard(
            txId = txId, merchantName = "Acme", price = 25.0,
            number = null, pin = null,
            barcodeValue = null, barcodeFormat = null,
            merchantUrl = "https://redeem/1", note = "order-multi", index = 1
        )
        coEvery { giftCardDao.getCardForTransaction(txId) } returns listOf(card0, card1)

        // Only update card at index 1.
        val incoming = card1.copy(
            number = "card-one-number",
            pin = "card-one-pin",
            merchantUrl = null,
            note = null
        )

        val captured = slot<GiftCard>()
        coEvery { giftCardDao.updateGiftCard(capture(captured)) } returns 1

        provider.updateGiftCardMetadata(incoming)

        val merged = captured.captured
        assertEquals("merge must select the row at the requested index", 1, merged.index)
        assertEquals("card-one-number", merged.number)
        assertEquals("card-one-pin", merged.pin)
        assertEquals("must preserve that row's merchantUrl", "https://redeem/1", merged.merchantUrl)
        assertEquals("must preserve note", "order-multi", merged.note)
    }

    @Test
    fun `cache write is gated on index == 0 and uses merged values`() = runTest {
        val existing = GiftCard(
            txId = txId, merchantName = "Acme", price = 25.0,
            number = null, pin = null,
            barcodeValue = null, barcodeFormat = null,
            merchantUrl = "https://kept", note = "order-9", index = 0
        )
        coEvery { giftCardDao.getCardForTransaction(txId) } returns listOf(existing)
        coEvery { giftCardDao.updateGiftCard(any()) } returns 1

        val incoming = existing.copy(number = "N", pin = "P", merchantUrl = null, note = null)

        provider.updateGiftCardMetadata(incoming)

        // The cache write must use the post-merge merchantUrl, not the null incoming value.
        // cacheTimestamp has a System.currentTimeMillis() default; match it with any() so the
        // verification doesn't race the wall clock.
        coVerify {
            cacheDao.insertGiftCardData(
                txId = txId,
                giftCardNumber = "N",
                giftCardPin = "P",
                merchantName = "Acme",
                originalPrice = 25.0,
                merchantUrl = "https://kept",
                cacheTimestamp = any()
            )
        }
    }

    @Test
    fun `no cache write for non-zero index`() = runTest {
        val existing = GiftCard(
            txId = txId, merchantName = "Acme", price = 25.0,
            number = null, pin = null,
            barcodeValue = null, barcodeFormat = null,
            merchantUrl = "https://kept", note = "order-9", index = 2
        )
        coEvery { giftCardDao.getCardForTransaction(txId) } returns listOf(existing)
        coEvery { giftCardDao.updateGiftCard(any()) } returns 1

        provider.updateGiftCardMetadata(existing.copy(number = "N", pin = "P"))

        coVerify(exactly = 0) { cacheDao.insertGiftCardData(any(), any(), any(), any(), any(), any(), any()) }
    }
}