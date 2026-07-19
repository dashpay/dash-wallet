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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}
