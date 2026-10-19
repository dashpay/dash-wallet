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

package de.schildbach.wallet.service.platform.sdk

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.dashfoundation.dashsdk.keywallet.DecodedTransaction
import org.dashfoundation.dashsdk.keywallet.TransactionDecoder
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.persistence.dao.TransactionDao
import org.dashfoundation.dashsdk.persistence.entities.TransactionEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.money.Coin as NeutralCoin
import org.dash.wallet.common.transactions.TransactionCategory

/**
 * Host-JVM tests for the Step B1 decode → detail-model mapping
 * ([buildSdkTxDetail] + helpers) — the pure core behind
 * [SdkTxDetailProvider].
 *
 * The fixture is a REAL raw transaction: built and consensus-serialized
 * with dashj (the app's reference implementation), then re-parsed from its
 * raw bytes, and the [DecodedTransaction] input is constructed to mirror
 * exactly what the SDK's `transaction_decode` binding returns for those
 * bytes (txids in consensus/wire order, per-output address/value/script —
 * the byte-level binding contract is pinned cross-language by the SDK's
 * own `TransactionDecoderTest` / Rust `fixture_blob_hex_is_pinned_for_kotlin`).
 */
class SdkTxDetailTest {

    private val params = TestNet3Params.get()

    private lateinit var rawTxBytes: ByteArray
    private lateinit var decoded: DecodedTransaction
    private lateinit var recipientAddress: String
    private lateinit var changeAddress: String

    private val prevTxId: Sha256Hash =
        Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111")

    @Before
    fun setUp() {
        Context.propagate(Context(params))

        recipientAddress = Address.fromKey(params, ECKey()).toBase58()
        changeAddress = Address.fromKey(params, ECKey()).toBase58()

        // Fixture raw tx: 1 input spending 11…11:3, outputs =
        // 70_000 duffs to the recipient, 25_000 duffs change, one OP_RETURN.
        val tx = Transaction(params)
        tx.addInput(
            TransactionInput(params, null, ByteArray(0), TransactionOutPoint(params, 3L, prevTxId))
        )
        tx.addOutput(
            TransactionOutput(params, tx, Coin.valueOf(70_000), Address.fromBase58(params, recipientAddress))
        )
        tx.addOutput(
            TransactionOutput(params, tx, Coin.valueOf(25_000), Address.fromBase58(params, changeAddress))
        )
        tx.addOutput(
            TransactionOutput(
                params, tx, Coin.ZERO,
                ScriptBuilder.createOpReturnScript(byteArrayOf(1, 2, 3)).program
            )
        )
        rawTxBytes = tx.unsafeBitcoinSerialize()

        // Re-parse from the raw bytes (proving the fixture is a valid
        // consensus-serialized transaction) and mirror the decode result.
        val parsed = Transaction(params, rawTxBytes)
        decoded = DecodedTransaction(
            txid = parsed.txId.reversedBytes, // wire order, as transaction_decode returns
            inputs = parsed.inputs.map {
                DecodedTransaction.Input(
                    prevTxid = it.outpoint.hash.reversedBytes,
                    prevVout = it.outpoint.index.toInt(),
                    address = null // empty scriptSig — no P2PKH sender hint
                )
            },
            outputs = parsed.outputs.map { out ->
                val script = out.scriptBytes
                val address = if (script.isNotEmpty() && script[0] == 0x6a.toByte()) {
                    null // OP_RETURN — transaction_decode returns no address
                } else {
                    runCatching { out.scriptPubKey.getToAddress(params, true).toBase58() }.getOrNull()
                }
                DecodedTransaction.Output(address, out.value.value, script)
            }
        )
    }

    private fun record(
        netAmountDuffs: Long,
        feeDuffs: Long? = null,
        contextCode: Int = 1, // instantSend
        directionCode: Int,
        firstSeenSec: Long = 1_770_000_000L
    ) = l1TxUiRecord(
        txidWireBytes = decoded.txid,
        netAmountDuffs = netAmountDuffs,
        feeDuffs = feeDuffs,
        contextCode = contextCode,
        directionCode = directionCode,
        firstSeenSec = firstSeenSec,
        blockTimestampSec = 0
    )

    // ── Outgoing send ─────────────────────────────────────────────────

    @Test
    fun `outgoing send derives fee from inputs minus outputs when all inputs known`() {
        val myInputAddress = Address.fromKey(params, ECKey()).toBase58()
        val detail = buildSdkTxDetail(
            record = record(netAmountDuffs = -70_247, directionCode = 1),
            decoded = decoded,
            myOutputAddresses = setOf(changeAddress),
            inputTxoAddresses = listOf(myInputAddress),
            inputTxoValues = listOf(95_247L) // Σin 95_247 − Σout 95_000 = 247
        )

        assertEquals(decoded.txidDisplayHex, detail.txIdDisplayHex)
        assertTrue(detail.isSent)
        assertFalse(detail.isInternal)
        assertEquals(247L, detail.feeDuffs)
        assertEquals(listOf(recipientAddress), detail.outputAddresses) // change filtered out
        assertEquals(listOf(myInputAddress), detail.inputAddresses)
        assertTrue(detail.hasOpReturn)
        assertEquals(L1TxUiStatus.INSTANT_LOCKED, detail.status)
        assertEquals(1_770_000_000_000L, detail.timestampMs)
        assertFalse(detail.decodeFailed)
    }

    @Test
    fun `recorded SDK fee wins over the derived fee`() {
        val detail = buildSdkTxDetail(
            record = record(netAmountDuffs = -70_247, feeDuffs = 300L, directionCode = 1),
            decoded = decoded,
            myOutputAddresses = setOf(changeAddress),
            inputTxoAddresses = listOf(null),
            inputTxoValues = listOf(95_247L)
        )
        assertEquals(300L, detail.feeDuffs)
    }

    @Test
    fun `fee is absent when any input value is unknown`() {
        val detail = buildSdkTxDetail(
            record = record(netAmountDuffs = -70_247, directionCode = 1),
            decoded = decoded,
            myOutputAddresses = setOf(changeAddress),
            inputTxoAddresses = listOf(null),
            inputTxoValues = listOf(null) // wallet never tracked the spent output
        )
        assertNull("no fabricated fee", detail.feeDuffs)
    }

    @Test
    fun `negative derived fee is rejected as inconsistent`() {
        val detail = buildSdkTxDetail(
            record = record(netAmountDuffs = -70_000, directionCode = 1),
            decoded = decoded,
            myOutputAddresses = setOf(changeAddress),
            inputTxoAddresses = listOf(null),
            inputTxoValues = listOf(10_000L) // Σin < Σout — impossible data
        )
        assertNull(detail.feeDuffs)
    }

    @Test
    fun `entirely-self send falls back to showing all output addresses`() {
        val detail = buildSdkTxDetail(
            record = record(netAmountDuffs = -247, directionCode = 1),
            decoded = decoded,
            myOutputAddresses = setOf(recipientAddress, changeAddress), // every output is ours
            inputTxoAddresses = listOf(null),
            inputTxoValues = listOf(null)
        )
        assertEquals(listOf(recipientAddress, changeAddress), detail.outputAddresses)
    }

    // ── Incoming receive ──────────────────────────────────────────────

    @Test
    fun `incoming receive shows only provably-ours outputs and no senders`() {
        val detail = buildSdkTxDetail(
            record = record(netAmountDuffs = 70_000, directionCode = 0),
            decoded = decoded,
            myOutputAddresses = setOf(recipientAddress), // ours; change is the sender's
            inputTxoAddresses = listOf(null),
            inputTxoValues = listOf(null)
        )

        assertFalse(detail.isSent)
        assertEquals(listOf(recipientAddress), detail.outputAddresses)
        assertTrue("received txs show no sender addresses", detail.inputAddresses.isEmpty())
        assertNull("input values unknown for receives — no fee", detail.feeDuffs)
    }

    @Test
    fun `incoming receive never displays foreign change as received-at`() {
        val detail = buildSdkTxDetail(
            record = record(netAmountDuffs = 70_000, directionCode = 0),
            decoded = decoded,
            myOutputAddresses = emptySet(), // TXO rows missing (unexpected)
            inputTxoAddresses = listOf(null),
            inputTxoValues = listOf(null)
        )
        assertTrue("no fallback to third-party addresses", detail.outputAddresses.isEmpty())
    }

    // ── Internal / degraded ───────────────────────────────────────────

    @Test
    fun `internal direction lists all output addresses`() {
        val detail = buildSdkTxDetail(
            record = record(netAmountDuffs = -247, directionCode = 2),
            decoded = decoded,
            myOutputAddresses = setOf(recipientAddress, changeAddress),
            inputTxoAddresses = listOf(changeAddress),
            inputTxoValues = listOf(95_247L)
        )
        assertTrue(detail.isInternal)
        assertTrue(detail.isSent)
        assertEquals(listOf(recipientAddress, changeAddress), detail.outputAddresses)
        assertEquals(listOf(changeAddress), detail.inputAddresses)
    }

    @Test
    fun `decode failure degrades to row-only fields without fabricating addresses`() {
        val detail = buildSdkTxDetail(
            record = record(netAmountDuffs = -70_247, feeDuffs = 247L, directionCode = 1),
            decoded = null,
            myOutputAddresses = emptySet(),
            inputTxoAddresses = emptyList(),
            inputTxoValues = emptyList()
        )
        assertTrue(detail.decodeFailed)
        assertEquals(247L, detail.feeDuffs) // row-recorded fee still shown
        assertTrue(detail.outputAddresses.isEmpty())
        assertTrue(detail.inputAddresses.isEmpty())
        assertFalse(detail.hasOpReturn)
    }

    @Test
    fun `p2pkh scriptSig hint fills sender gaps for outgoing but never for incoming`() {
        val hint = Address.fromKey(params, ECKey()).toBase58()
        val withHint = DecodedTransaction(
            txid = decoded.txid,
            inputs = listOf(DecodedTransaction.Input(decoded.inputs[0].prevTxid, 3, hint)),
            outputs = decoded.outputs
        )

        val outgoing = buildSdkTxDetail(
            record = record(netAmountDuffs = -70_247, directionCode = 1),
            decoded = withHint,
            myOutputAddresses = setOf(changeAddress),
            inputTxoAddresses = listOf(null), // no TXO row — hint fills in
            inputTxoValues = listOf(null)
        )
        assertEquals(listOf(hint), outgoing.inputAddresses)

        val incoming = buildSdkTxDetail(
            record = record(netAmountDuffs = 70_000, directionCode = 0),
            decoded = withHint,
            myOutputAddresses = setOf(recipientAddress),
            inputTxoAddresses = listOf(null),
            inputTxoValues = listOf(null)
        )
        assertTrue("unauthenticated hint never shown on receives", incoming.inputAddresses.isEmpty())
    }

    // ── Wire-format helpers ───────────────────────────────────────────

    @Test
    fun `display txid converts to wire bytes and back`() {
        val displayHex = decoded.txidDisplayHex
        val wire = displayTxIdToWireBytes(displayHex)!!
        assertArrayEquals(decoded.txid, wire)
        assertEquals(
            displayHex,
            wire.reversedArray().joinToString("") { "%02x".format(it) }
        )
        assertNull(displayTxIdToWireBytes("nonsense"))
        assertNull(displayTxIdToWireBytes("ab".repeat(31)))
    }

    @Test
    fun `txo outpoint is wire txid plus little-endian vout`() {
        val wire = ByteArray(32) { it.toByte() }
        val outpoint = txoOutpoint(wire, 3)
        assertEquals(36, outpoint.size)
        assertArrayEquals(wire, outpoint.copyOfRange(0, 32))
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), outpoint.copyOfRange(32, 36))

        val bigVout = txoOutpoint(wire, 0x0102_0304)
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), bigVout.copyOfRange(32, 36))
    }

    // ── Native-lib failure hardening ──────────────────────────────────

    @Test
    fun `native decode UnsatisfiedLinkError degrades to row-only detail without crashing`() {
        // The AAR ships only arm64-v8a + x86_64; on any other ABI the JNI
        // load throws UnsatisfiedLinkError (an Error, not an Exception).
        // The provider must catch it and serve the same row-only detail
        // as a decode failure — never let it escape and crash the sheet.
        val entity = mockk<TransactionEntity> {
            every { txid } returns decoded.txid
            every { transactionData } returns rawTxBytes
            every { netAmount } returns -70_247L
            every { fee } returns 247L
            every { context } returns 1 // instantSend
            every { direction } returns 1 // outgoing
            every { firstSeen } returns 1_770_000_000L
            every { blockTimestamp } returns 0
        }
        val txDao = mockk<TransactionDao> {
            coEvery { getByTxid(any()) } returns entity
        }
        val db = mockk<DashDatabase> {
            every { transactionDao() } returns txDao
            every { txoDao() } returns mockk()
        }
        val sdkService = mockk<DashSdkService> {
            coEvery { ensureStarted() } returns Unit
            every { databaseOrNull() } returns db
        }

        mockkObject(TransactionDecoder)
        try {
            every {
                TransactionDecoder.decode(any(), any())
            } throws UnsatisfiedLinkError("dlopen failed: library not found for this ABI")

            // No tx_display_cache row for this txid (pre-cutover / non-contact
            // tx): the provider consults the dao before decoding, and an empty
            // result must leave the SDK row's direction/amount unchanged.
            val displayCacheDao = mockk<de.schildbach.wallet.database.dao.TxDisplayCacheDao> {
                coEvery { getEntriesByIds(any()) } returns emptyList()
            }
            val detail = runBlocking {
                SdkTxDetailProvider(sdkService, displayCacheDao, mockk(relaxed = true))
                    .load(decoded.txidDisplayHex)
            }

            assertNotNull("row-only detail, not a crash or null", detail)
            assertTrue(detail!!.decodeFailed)
            assertEquals(decoded.txidDisplayHex, detail.txIdDisplayHex)
            assertEquals(-70_247L, detail.netAmountDuffs)
            assertEquals(247L, detail.feeDuffs) // row-recorded fee still shown
            assertEquals(L1TxUiStatus.INSTANT_LOCKED, detail.status)
            assertTrue(detail.outputAddresses.isEmpty())
            assertTrue(detail.inputAddresses.isEmpty())
            assertFalse(detail.hasOpReturn)
        } finally {
            unmockkObject(TransactionDecoder)
        }
    }

    // ── Metadata source (SDK-only txs) ────────────────────────────────

    @Test
    fun `defaultMetadataFor builds a received row from the SDK store without starting the SDK`() {
        // The 2026-10-02 field case: a DashPay one-way-contact receive the SDK's
        // contact backfill found, held only by the SDK store.
        val txIdHex = "e5169bfc4989585abd4b0476188611b981e3c750539da5b8a39fe135e3bbb957"
        val entity = mockk<TransactionEntity> {
            every { txid } returns displayTxIdToWireBytes(txIdHex)!!
            every { netAmount } returns 100_000L
            every { fee } returns null
            every { context } returns 2 // in block
            every { direction } returns 0 // incoming
            every { firstSeen } returns 1_770_000_000L
            every { blockTimestamp } returns 0
        }
        val txDao = mockk<TransactionDao> {
            coEvery { getByTxid(any()) } returns entity
        }
        val db = mockk<DashDatabase> { every { transactionDao() } returns txDao }
        val sdkService = mockk<DashSdkService> { every { databaseOrNull() } returns db }
        val displayCacheDao = mockk<de.schildbach.wallet.database.dao.TxDisplayCacheDao> {
            coEvery { getEntriesByIds(any()) } returns emptyList()
        }

        val row = runBlocking {
            SdkTxDetailProvider(sdkService, displayCacheDao, mockk(relaxed = true)) { _, _ -> null }
                .defaultMetadataFor(TxId.wrap(txIdHex))
        }

        assertNotNull(row)
        assertEquals(TxId.wrap(txIdHex), row!!.txId)
        assertEquals(NeutralCoin.valueOf(100_000L), row.value)
        assertEquals(TransactionCategory.Received, row.type)
        assertEquals(1_770_000_000_000L, row.timestamp)
        assertEquals("", row.memo)
        assertNull(row.taxCategory)
        coVerify(exactly = 0) { sdkService.ensureStarted() }
    }

    @Test
    fun `defaultMetadataFor is null when the SDK store is not open`() {
        val sdkService = mockk<DashSdkService> { every { databaseOrNull() } returns null }

        val row = runBlocking {
            SdkTxDetailProvider(sdkService, mockk(), mockk(relaxed = true)) { _, _ -> null }
                .defaultMetadataFor(TxId.wrap("e5169bfc4989585abd4b0476188611b981e3c750539da5b8a39fe135e3bbb957"))
        }

        assertNull(row)
        coVerify(exactly = 0) { sdkService.ensureStarted() }
    }

    @Test
    fun `defaultMetadataFor follows the walker record over a misattributed store row`() {
        // A contact send the store row records as INCOMING +change; the walker
        // reattributes it to OUTGOING with the whole-wallet net. The metadata row
        // must match what the history list shows, not the raw store row.
        val txIdHex = decoded.txidDisplayHex
        val entity = mockk<TransactionEntity> {
            every { txid } returns decoded.txid
            every { netAmount } returns 4_000L
            every { fee } returns null
            every { context } returns 3
            every { direction } returns 0 // incoming (misattributed)
            every { firstSeen } returns 1_770_000_000L
            every { blockTimestamp } returns 0
        }
        val db = mockk<DashDatabase> {
            every { transactionDao() } returns mockk<TransactionDao> { coEvery { getByTxid(any()) } returns entity }
        }
        val sdkService = mockk<DashSdkService> { every { databaseOrNull() } returns db }
        val displayCacheDao = mockk<de.schildbach.wallet.database.dao.TxDisplayCacheDao> {
            coEvery { getEntriesByIds(any()) } returns emptyList()
        }
        val walkerRecord = L1TxUiRecord(
            txidHex = txIdHex,
            netAmountDuffs = -10_000_227L,
            feeDuffs = 227L,
            timestampMs = 1_770_000_000_000L,
            status = L1TxUiStatus.CHAINLOCKED,
            direction = L1TxUiDirection.OUTGOING
        )

        val row = runBlocking {
            SdkTxDetailProvider(sdkService, displayCacheDao, mockk(relaxed = true)) { _, _ -> walkerRecord }
                .defaultMetadataFor(TxId.wrap(txIdHex))
        }

        assertNotNull(row)
        assertEquals(TransactionCategory.Sent, row!!.type)
        assertEquals(NeutralCoin.valueOf(-10_000_227L), row.value)
        coVerify(exactly = 0) { sdkService.ensureStarted() }
    }

    @Test
    fun `batch defaultMetadataFor reads wallet records and cache rows once per batch`() {
        val received = "e5169bfc4989585abd4b0476188611b981e3c750539da5b8a39fe135e3bbb957"
        val contactSend = decoded.txidDisplayHex
        val notInStore = "00000000000000000000000000000000000000000000000000000000000000aa"
        fun entityFor(hex: String, net: Long) = mockk<TransactionEntity> {
            every { txid } returns displayTxIdToWireBytes(hex)!!
            every { netAmount } returns net
            every { fee } returns null
            every { context } returns 3
            every { direction } returns 0 // incoming, as stored
            every { firstSeen } returns 1_770_000_000L
            every { blockTimestamp } returns 0
        }
        val entities = mapOf(received to entityFor(received, 100_000L), contactSend to entityFor(contactSend, 4_000L))
        val txDao = mockk<TransactionDao> {
            coEvery { getByTxid(any()) } answers {
                val hex = firstArg<ByteArray>().reversedArray().joinToString("") { "%02x".format(it) }
                entities[hex]
            }
        }
        val db = mockk<DashDatabase> { every { transactionDao() } returns txDao }
        val sdkService = mockk<DashSdkService> { every { databaseOrNull() } returns db }
        val displayCacheDao = mockk<de.schildbach.wallet.database.dao.TxDisplayCacheDao> {
            coEvery { getEntriesByIds(any()) } returns emptyList()
        }
        val batchCalls = mutableListOf<Collection<String>>()
        var singleCalls = 0
        val provider = SdkTxDetailProvider(
            sdkService,
            displayCacheDao,
            mockk(relaxed = true),
            walletRecordsFor = { _, hexes ->
                batchCalls.add(hexes.toList())
                // The walker reattributes the contact send; the receive is as stored.
                mapOf(
                    contactSend to L1TxUiRecord(
                        txidHex = contactSend,
                        netAmountDuffs = -10_000_227L,
                        feeDuffs = 227L,
                        timestampMs = 1_770_000_000_000L,
                        status = L1TxUiStatus.CHAINLOCKED,
                        direction = L1TxUiDirection.OUTGOING
                    )
                )
            },
            walletRecordFor = { _, _ -> singleCalls++; null }
        )

        val rows = runBlocking {
            provider.defaultMetadataFor(listOf(TxId.wrap(received), TxId.wrap(contactSend), TxId.wrap(notInStore)))
        }

        assertEquals(setOf(TxId.wrap(received), TxId.wrap(contactSend)), rows.keys)
        assertEquals(TransactionCategory.Received, rows.getValue(TxId.wrap(received)).type)
        assertEquals(NeutralCoin.valueOf(100_000L), rows.getValue(TxId.wrap(received)).value)
        assertEquals(TransactionCategory.Sent, rows.getValue(TxId.wrap(contactSend)).type)
        assertEquals(NeutralCoin.valueOf(-10_000_227L), rows.getValue(TxId.wrap(contactSend)).value)
        // One wallet pass for the txids the store holds; no per-txid walker lookups.
        assertEquals(listOf(listOf(received, contactSend)), batchCalls)
        assertEquals(0, singleCalls)
        coVerify(exactly = 1) { displayCacheDao.getEntriesByIds(any()) }
        coVerify(exactly = 0) { sdkService.ensureStarted() }
    }

    // ── D-M-01: the fee is never counted twice ────────────────────────

    /**
     * [SdkTxDetailProvider.load] for a send whose history row holds [cachedValue],
     * with the SDK row's [storedNet]/[storedFee]. With [inputTxoAmount] null the decode
     * is forced to fail, so only the row-to-net reconstruction is under test; with it
     * set, the fixture decodes and its one input is a wallet TXO of that amount.
     */
    private fun detailFor(
        cachedValue: Long,
        storedNet: Long,
        storedFee: Long?,
        storedDirection: Int,
        contactUserId: String? = null,
        storedContext: Int = 3,
        inputTxoAmount: Long? = null
    ): SdkTxDetail {
        val entity = mockk<TransactionEntity> {
            every { txid } returns decoded.txid
            every { transactionData } returns rawTxBytes
            every { netAmount } returns storedNet
            every { fee } returns storedFee
            every { context } returns storedContext
            every { direction } returns storedDirection
            every { firstSeen } returns 1_770_000_000L
            every { blockTimestamp } returns 0
        }
        val db = mockk<DashDatabase> {
            every { transactionDao() } returns mockk<TransactionDao> { coEvery { getByTxid(any()) } returns entity }
            every { txoDao() } returns mockk<org.dashfoundation.dashsdk.persistence.dao.TxoDao> {
                val inputOutpoint = txoOutpoint(decoded.inputs.single().prevTxid, decoded.inputs.single().prevVout)
                coEvery { getByOutpoint(any()) } answers {
                    if (inputTxoAmount != null && firstArg<ByteArray>().contentEquals(inputOutpoint)) {
                        mockk<org.dashfoundation.dashsdk.persistence.entities.TxoEntity> {
                            every { amount } returns inputTxoAmount
                            every { address } returns "input-address"
                        }
                    } else {
                        null
                    }
                }
            }
        }
        val sdkService = mockk<DashSdkService> {
            coEvery { ensureStarted() } returns Unit
            every { databaseOrNull() } returns db
        }
        val row = de.schildbach.wallet.database.entity.TxDisplayCacheEntry(
            rowId = decoded.txidDisplayHex,
            title = "Sent",
            valueSatoshis = cachedValue,
            iconType = de.schildbach.wallet.database.entity.TxDisplayCacheEntry.ICON_SENT,
            iconBgType = de.schildbach.wallet.database.entity.TxDisplayCacheEntry.BG_SENT,
            statusText = "",
            comment = "",
            transactionAmount = 1,
            time = 1_770_000_000_000L,
            hasErrors = false,
            service = null,
            exchangeRateFiatCode = null,
            exchangeRateFiatValue = null,
            contactUsername = contactUserId?.let { "friend" },
            contactDisplayName = null,
            contactAvatarUrl = null,
            contactUserId = contactUserId,
            filterFlags = de.schildbach.wallet.database.entity.TxDisplayCacheEntry.FLAG_SENT
        )
        val displayCacheDao = mockk<de.schildbach.wallet.database.dao.TxDisplayCacheDao> {
            coEvery { getEntriesByIds(any()) } returns listOf(row)
        }
        mockkObject(TransactionDecoder)
        try {
            if (inputTxoAmount == null) {
                every { TransactionDecoder.decode(any(), any()) } throws UnsatisfiedLinkError("no decode in this test")
            } else {
                every { TransactionDecoder.decode(any(), any()) } returns decoded
            }
            return requireNotNull(
                runBlocking {
                    SdkTxDetailProvider(sdkService, displayCacheDao, mockk(relaxed = true))
                        .load(decoded.txidDisplayHex)
                }
            )
        } finally {
            unmockkObject(TransactionDecoder)
        }
    }

    private fun detailNetFor(
        cachedValue: Long,
        storedNet: Long,
        storedFee: Long?,
        storedDirection: Int,
        contactUserId: String? = null
    ): Long = detailFor(cachedValue, storedNet, storedFee, storedDirection, contactUserId).netAmountDuffs

    @Test
    fun `pending send whose fee is only known in memory shows the amount with its fee`() {
        // Not yet confirmed: the walker recovers the 247-duff fee from the input
        // reservation and serves the fee-free row (−70 000), but the store's fee stays
        // NULL. The sheet derives the same fee from the input TXO instead of reading 0.
        val detail = detailFor(
            cachedValue = -70_000L, storedNet = -70_247L, storedFee = null, storedDirection = 1,
            storedContext = 0, inputTxoAmount = 95_247L
        )
        assertEquals(-70_247L, detail.netAmountDuffs) // not −70 000
        assertEquals(247L, detail.feeDuffs)
    }

    @Test
    fun `contact send keeps its fee-inclusive net once the walker recovers the fee`() {
        // The SDK row shows only the +change; the history row holds the engine's
        // signed net, fee included. Before and after the fee is persisted.
        val before = detailNetFor(-10_000_227L, storedNet = 499_773L, storedFee = null, storedDirection = 0, contactUserId = "id")
        val after = detailNetFor(-10_000_227L, storedNet = 499_773L, storedFee = 227L, storedDirection = 0, contactUserId = "id")
        assertEquals(-10_000_227L, before)
        assertEquals(-10_000_227L, after) // not −10 000 454
    }

    @Test
    fun `send still cached with its fee is not counted twice`() {
        // Fee persisted, history row not yet corrected by the next planner pass.
        assertEquals(-10_000_227L, detailNetFor(-10_000_227L, storedNet = -10_000_227L, storedFee = 227L, storedDirection = 1))
    }

    @Test
    fun `fee-free send gets its fee back for the detail sheet`() {
        assertEquals(-10_000_227L, detailNetFor(-10_000_000L, storedNet = -10_000_227L, storedFee = 227L, storedDirection = 1))
    }

    // ── D-M-01: a contact row's amount is read from the wallet record ─

    /**
     * [SdkTxDetailProvider.load] for the history [row] of a send whose stored SDK row is
     * the misattributed INCOMING +change (what a contact send persists), with the
     * walker's corrected [walletRecord] supplied through the provider's seam.
     */
    private fun loadContactSend(row: de.schildbach.wallet.database.entity.TxDisplayCacheEntry, walletRecord: L1TxUiRecord): SdkTxDetail {
        val entity = mockk<TransactionEntity> {
            every { txid } returns decoded.txid
            every { transactionData } returns rawTxBytes
            every { netAmount } returns 499_773L
            every { fee } returns null
            every { context } returns 0
            every { direction } returns 0
            every { firstSeen } returns 1_770_000_000L
            every { blockTimestamp } returns 0
        }
        val db = mockk<DashDatabase> {
            every { transactionDao() } returns mockk<TransactionDao> { coEvery { getByTxid(any()) } returns entity }
            every { txoDao() } returns mockk()
        }
        val sdkService = mockk<DashSdkService> {
            coEvery { ensureStarted() } returns Unit
            every { databaseOrNull() } returns db
        }
        val displayCacheDao = mockk<de.schildbach.wallet.database.dao.TxDisplayCacheDao> {
            coEvery { getEntriesByIds(any()) } returns listOf(row)
        }
        mockkObject(TransactionDecoder)
        try {
            every { TransactionDecoder.decode(any(), any()) } throws UnsatisfiedLinkError("no decode in this test")
            return requireNotNull(
                runBlocking {
                    SdkTxDetailProvider(sdkService, displayCacheDao, mockk(relaxed = true)) { _, hex ->
                        walletRecord.takeIf { it.txidHex == hex }
                    }.load(decoded.txidDisplayHex)
                }
            )
        } finally {
            unmockkObject(TransactionDecoder)
        }
    }

    /** The walker's record: the pending contact send reattributed OUTGOING, fee recovered in memory. */
    private fun reattributedContactSend() = l1TxUiRecord(
        txidWireBytes = decoded.txid,
        netAmountDuffs = -10_000_227L,
        feeDuffs = 227L,
        contextCode = 0,
        directionCode = 1,
        firstSeenSec = 1_770_000_000L,
        blockTimestampSec = 0
    )

    private val friend = ResolvedTxContact(username = "friend", displayName = null, avatarUrl = null, userId = "friend-id")

    @Test
    fun `contact send planned without the engine net shows its fee in details`() {
        // After a restart with the send still pending, no engine net is known, so the
        // planner writes the fee-free principal — and still attaches the contact.
        val record = reattributedContactSend()
        val row = planL1DisplaySync(
            records = listOf(record), existingByRowId = emptyMap(), groupedTxIds = emptySet(),
            resolve = { "str:$it" }, nowMs = 1_770_000_000_000L,
            contactByTxid = mapOf(record.txidHex to friend)
        ).inserts.single()
        assertEquals(-10_000_000L, row.valueSatoshis)
        assertEquals("friend-id", row.contactUserId)

        val detail = loadContactSend(row, record)
        assertEquals(-10_000_227L, detail.netAmountDuffs) // not −10 000 000
        assertEquals(227L, detail.feeDuffs)
    }

    @Test
    fun `contact send planned from the engine net is not counted twice`() {
        val record = reattributedContactSend()
        val row = planL1DisplaySync(
            records = listOf(record), existingByRowId = emptyMap(), groupedTxIds = emptySet(),
            resolve = { "str:$it" }, nowMs = 1_770_000_000_000L,
            contactByTxid = mapOf(record.txidHex to friend),
            signedNetByTxid = mapOf(record.txidHex to -10_000_227L)
        ).inserts.single()
        assertEquals(-10_000_227L, row.valueSatoshis)

        val detail = loadContactSend(row, record)
        assertEquals(-10_000_227L, detail.netAmountDuffs) // not −10 000 454
        assertEquals(227L, detail.feeDuffs)
    }
}
