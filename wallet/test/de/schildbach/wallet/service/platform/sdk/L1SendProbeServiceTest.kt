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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.params.TestNet3Params
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Host-JVM tests for the Phase 5c.0/5c.1 send probes: the change-address
 * extraction from fixture tx bytes, the exact `L1FeeParity` /
 * `L1BridgeProbe` line formats, and — the load-bearing invariant — that a
 * probe pass NEVER throws into the send path, whatever its read surfaces
 * do. No native calls; the SDK Room DB and dashj wallet are faked via
 * [L1SendProbeSource].
 */
class L1SendProbeServiceTest {

    private val params: NetworkParameters = TestNet3Params.get()
    private val recipientAddress: String by lazy { Address.fromKey(params, ECKey()).toBase58() }
    private val changeAddress: String by lazy { Address.fromKey(params, ECKey()).toBase58() }
    private val amountDuffs = 1_000_000L

    @Before
    fun setUp() {
        Context.propagate(Context.getOrCreate(params))
    }

    /**
     * A minimal single-input send tx: one recipient output, an optional
     * change output — round-tripped through consensus bytes exactly like
     * the SDK Room row's `transactionData`.
     */
    private fun buildSendTxBytes(
        recipientDuffs: Long = amountDuffs,
        changeDuffs: Long? = 500_000L,
        changeAddr: String = changeAddress
    ): ByteArray {
        val tx = Transaction(params)
        tx.addInput(
            TransactionInput(
                params, tx, ByteArray(0),
                TransactionOutPoint(params, 0, Sha256Hash.wrap("11".repeat(32)))
            )
        )
        tx.addOutput(Coin.valueOf(recipientDuffs), Address.fromString(params, recipientAddress))
        if (changeDuffs != null) {
            tx.addOutput(Coin.valueOf(changeDuffs), Address.fromString(params, changeAddr))
        }
        return tx.bitcoinSerialize()
    }

    private fun parse(bytes: ByteArray): Transaction = Transaction(params, bytes)

    // ── Change-address extraction (from fixture bytes) ────────────────

    @Test
    fun summarizeSendOutputs_extractsChangeAddress_fromFixtureBytes() {
        val tx = parse(buildSendTxBytes())
        val summary = summarizeSendOutputs(tx, params, recipientAddress, amountDuffs)

        assertEquals(changeAddress, summary.changeAddress)
        assertTrue(summary.recipientFound)
        assertEquals(
            listOf(
                "0:$recipientAddress=$amountDuffs (recipient)",
                "1:$changeAddress=500000 (change)"
            ),
            summary.outputLabels
        )
    }

    @Test
    fun summarizeSendOutputs_noChangeOutput_reportsNull() {
        val tx = parse(buildSendTxBytes(changeDuffs = null))
        val summary = summarizeSendOutputs(tx, params, recipientAddress, amountDuffs)

        assertNull(summary.changeAddress)
        assertTrue(summary.recipientFound)
        assertEquals(listOf("0:$recipientAddress=$amountDuffs (recipient)"), summary.outputLabels)
    }

    /**
     * SELF-SEND: both outputs pay the SAME address; only the send amount
     * distinguishes the payment from the change — the exact-{address,
     * amount} preference must label the amount-matching output recipient.
     */
    @Test
    fun summarizeSendOutputs_selfSend_labelsByTargetAmount() {
        val tx = parse(buildSendTxBytes(changeDuffs = 123_456L, changeAddr = recipientAddress))
        val summary = summarizeSendOutputs(tx, params, recipientAddress, amountDuffs)

        assertTrue(summary.recipientFound)
        assertEquals(recipientAddress, summary.changeAddress)
        assertEquals(
            listOf(
                "0:$recipientAddress=$amountDuffs (recipient)",
                "1:$recipientAddress=123456 (change)"
            ),
            summary.outputLabels
        )
    }

    /** Fee-subtracted sends (dashj emptyWallet): address-only fallback match. */
    @Test
    fun summarizeSendOutputs_amountMismatch_fallsBackToAddressMatch() {
        val tx = parse(buildSendTxBytes(recipientDuffs = amountDuffs - 225, changeDuffs = null))
        val summary = summarizeSendOutputs(tx, params, recipientAddress, amountDuffs)

        assertTrue(summary.recipientFound)
        assertNull(summary.changeAddress)
    }

    @Test
    fun summarizeSendOutputs_recipientAbsent_everyOutputIsChange() {
        val tx = parse(buildSendTxBytes())
        val other = Address.fromKey(params, ECKey()).toBase58()
        val summary = summarizeSendOutputs(tx, params, other, amountDuffs)

        assertFalse(summary.recipientFound)
        assertEquals("$recipientAddress,$changeAddress", summary.changeAddress)
    }

    // ── Wire txid bytes ────────────────────────────────────────────────

    @Test
    fun wireTxidBytes_reversesDisplayHex() {
        val display = "01" + "00".repeat(30) + "ff"
        val wire = wireTxidBytesOrNull(display)!!

        assertEquals(32, wire.size)
        assertEquals(0xff.toByte(), wire[0])
        assertEquals(0x01.toByte(), wire[31])
    }

    @Test
    fun wireTxidBytes_malformed_returnsNull() {
        assertNull(wireTxidBytesOrNull("not-a-txid"))
        assertNull(wireTxidBytesOrNull("ab".repeat(31)))
        assertNull(wireTxidBytesOrNull(""))
    }

    // ── SDK outpoint encoding + the computed-fee TXO join ─────────────

    @Test
    fun sdkOutpointBytes_wireTxidPlusLittleEndianVout() {
        val txidWire = ByteArray(32) { it.toByte() }
        val outpoint = sdkOutpointBytes(txidWire, 0x01020304L)

        assertEquals(36, outpoint.size)
        assertTrue(outpoint.copyOfRange(0, 32).contentEquals(txidWire))
        assertEquals(0x04.toByte(), outpoint[32])
        assertEquals(0x03.toByte(), outpoint[33])
        assertEquals(0x02.toByte(), outpoint[34])
        assertEquals(0x01.toByte(), outpoint[35])
    }

    /** A two-input send tx, prevouts at ("11"*32, 0) and ("22"*32, 1). */
    private fun buildTwoInputTx(): Transaction {
        val tx = Transaction(params)
        tx.addInput(
            TransactionInput(
                params, tx, ByteArray(0),
                TransactionOutPoint(params, 0, Sha256Hash.wrap("11".repeat(32)))
            )
        )
        tx.addInput(
            TransactionInput(
                params, tx, ByteArray(0),
                TransactionOutPoint(params, 1, Sha256Hash.wrap("22".repeat(32)))
            )
        )
        tx.addOutput(Coin.valueOf(amountDuffs), Address.fromString(params, recipientAddress))
        tx.addOutput(Coin.valueOf(500_000L), Address.fromString(params, changeAddress))
        return parse(tx.bitcoinSerialize())
    }

    private val prevout0 = sdkOutpointBytes(Sha256Hash.wrap("11".repeat(32)).reversedBytes, 0)
    private val prevout1 = sdkOutpointBytes(Sha256Hash.wrap("22".repeat(32)).reversedBytes, 1)

    /** Σ(prevout values) − Σ(outputs): (900_000+600_408) − (1_000_000+500_000) = 408. */
    @Test
    fun computeFeeFromTxoJoin_allInputsResolve_isInputSumMinusOutputSum() = runBlocking {
        val amounts = mapOf(prevout0.toList() to 900_000L, prevout1.toList() to 600_408L)
        val fee = computeFeeFromTxoJoin(buildTwoInputTx()) { amounts[it.toList()] }

        assertEquals(408L, fee)
    }

    /** A partial input sum is never reported as a fee. */
    @Test
    fun computeFeeFromTxoJoin_anyInputUnresolvable_returnsNull() = runBlocking {
        val amounts = mapOf(prevout0.toList() to 900_000L)
        assertNull(computeFeeFromTxoJoin(buildTwoInputTx()) { amounts[it.toList()] })
    }

    /** Inputs < outputs means the store is inconsistent — no negative "fee". */
    @Test
    fun computeFeeFromTxoJoin_negativeArithmetic_returnsNull() = runBlocking {
        val amounts = mapOf(prevout0.toList() to 700_000L, prevout1.toList() to 700_000L)
        assertNull(computeFeeFromTxoJoin(buildTwoInputTx()) { amounts[it.toList()] })
    }

    // ── Layered fee-source selection ───────────────────────────────────

    @Test
    fun resolveSdkFee_rowFeePresent_winsWithoutTouchingLaterLayers() = runBlocking {
        var repolled = false
        var computed = false
        val resolved = resolveSdkFee(
            rowFeeDuffs = 408L,
            repollRowFee = { repolled = true; null },
            computeFee = { computed = true; null }
        )

        assertEquals(ResolvedSdkFee(408L, "row"), resolved)
        assertFalse(repolled)
        assertFalse(computed)
    }

    @Test
    fun resolveSdkFee_rowFeeAppearsLate_tagsTheMeasuredLatency() = runBlocking {
        var computed = false
        val resolved = resolveSdkFee(
            rowFeeDuffs = null,
            repollRowFee = { 408L to 2350L },
            computeFee = { computed = true; null }
        )

        assertEquals(ResolvedSdkFee(408L, "row-late:2350"), resolved)
        assertFalse(computed)
    }

    @Test
    fun resolveSdkFee_rowFeeNeverAppears_fallsBackToComputed() = runBlocking {
        assertEquals(
            ResolvedSdkFee(408L, "computed"),
            resolveSdkFee(rowFeeDuffs = null, repollRowFee = { null }, computeFee = { 408L })
        )
    }

    @Test
    fun resolveSdkFee_noLayerResolves_isNa() = runBlocking {
        assertEquals(
            ResolvedSdkFee(null, "n/a"),
            resolveSdkFee(rowFeeDuffs = null, repollRowFee = { null }, computeFee = { null })
        )
    }

    /** A throwing layer is contained and counts as "didn't resolve". */
    @Test
    fun resolveSdkFee_throwingLayers_fallThroughContained() = runBlocking {
        assertEquals(
            ResolvedSdkFee(408L, "computed"),
            resolveSdkFee(
                rowFeeDuffs = null,
                repollRowFee = { throw IllegalStateException("repoll boom") },
                computeFee = { 408L }
            )
        )
        assertEquals(
            ResolvedSdkFee(null, "n/a"),
            resolveSdkFee(
                rowFeeDuffs = null,
                repollRowFee = { throw IllegalStateException("repoll boom") },
                computeFee = { throw IllegalStateException("compute boom") }
            )
        )
    }

    // ── Summarizing-line formats (the grep contract) ──────────────────

    private val txid = "ab".repeat(32)

    @Test
    fun feeParityLine_sdkRoute_allFieldsKnown() {
        assertEquals(
            "L1FeeParity route=sdk txid=$txid sdkFee=300 feeSource=row dashjEstimatedFee=225" +
                " delta=75 rowLatencyMs=1234 changeAddress=$changeAddress",
            feeParityLine("sdk", txid, 300, "row", 225, 1234, changeAddress)
        )
    }

    @Test
    fun feeParityLine_sdkRoute_nothingKnown() {
        assertEquals(
            "L1FeeParity route=sdk txid=$txid sdkFee=n/a feeSource=n/a dashjEstimatedFee=n/a" +
                " delta=n/a rowLatencyMs=timeout changeAddress=n/a",
            feeParityLine("sdk", txid, null, "n/a", null, null, "n/a")
        )
    }

    /** The `row-late:<ms>` and `computed` tags land verbatim in the line. */
    @Test
    fun feeParityLine_sdkRoute_lateAndComputedFeeSources() {
        assertEquals(
            "L1FeeParity route=sdk txid=$txid sdkFee=408 feeSource=row-late:2350" +
                " dashjEstimatedFee=376 delta=32 rowLatencyMs=250 changeAddress=$changeAddress",
            feeParityLine("sdk", txid, 408, "row-late:2350", 376, 250, changeAddress)
        )
        assertEquals(
            "L1FeeParity route=sdk txid=$txid sdkFee=408 feeSource=computed" +
                " dashjEstimatedFee=376 delta=32 rowLatencyMs=250 changeAddress=$changeAddress",
            feeParityLine("sdk", txid, 408, "computed", 376, 250, changeAddress)
        )
    }

    /** The dashj route has no SDK fee source — the field is forced to n/a. */
    @Test
    fun feeParityLine_dashjRoute_baseline() {
        assertEquals(
            "L1FeeParity route=dashj txid=$txid dashjFee=225 feeSource=n/a dashjEstimatedFee=225" +
                " delta=0 rowLatencyMs=n/a changeAddress=none",
            feeParityLine("dashj", txid, 225, "row", 225, null, "none")
        )
    }

    @Test
    fun feeParityDetailLog_format() {
        val detail = feeParityDetailLog(
            route = "sdk",
            txidHex = txid,
            addressBase58 = recipientAddress,
            amountDuffs = amountDuffs,
            emptyWallet = false,
            actualFeeDuffs = 300,
            dashjEstimatedFeeDuffs = 225,
            txSizeBytes = 300,
            economicFeePerKb = 1000,
            outputLabels = listOf(
                "0:$recipientAddress=$amountDuffs (recipient)",
                "1:$changeAddress=500000 (change)"
            )
        )
        assertEquals(
            "L1FeeParity DETAIL route=sdk txid=$txid" +
                "\n  send: address=$recipientAddress amount=$amountDuffs duffs emptyWallet=false" +
                "\n  fees: actual=300 dashjEstimated=225 delta=75 duffs" +
                "\n  tx: size=300 bytes impliedFeePerKb=1000 (dashj ECONOMIC_FEE=1000/kB)" +
                "\n  outputs: 0:$recipientAddress=$amountDuffs (recipient), " +
                "1:$changeAddress=500000 (change)",
            detail
        )
    }

    @Test
    fun bridgeProbeLine_allFieldsKnown() {
        assertEquals(
            "L1BridgeProbe txid=$txid bytesOk=true txidMatch=true roomLatencyMs=500" +
                " dashjNetworkLatencyMs=1500 inputsAllOurs=true preexistedInDashj=false",
            bridgeProbeLine(txid, true, true, 500, 1500, true, false)
        )
    }

    @Test
    fun bridgeProbeLine_unknownsAndTimeouts() {
        assertEquals(
            "L1BridgeProbe txid=$txid bytesOk=false txidMatch=n/a roomLatencyMs=timeout" +
                " dashjNetworkLatencyMs=timeout inputsAllOurs=n/a preexistedInDashj=true",
            bridgeProbeLine(txid, false, null, null, null, null, true)
        )
    }

    // ── Probe orchestration ────────────────────────────────────────────

    private class FakeSource(
        var row: (ByteArray) -> SdkTxRow? = { null },
        var hasTx: () -> Boolean = { false },
        var allOurs: () -> Boolean? = { null },
        var txoAmount: (ByteArray) -> Long? = { null }
    ) : L1SendProbeSource {
        var lastWireTxid: ByteArray? = null
        var rowReads = 0
        val txoLookups = mutableListOf<List<Byte>>()

        override suspend fun sdkTxRow(txidWireBytes: ByteArray): SdkTxRow? {
            lastWireTxid = txidWireBytes
            rowReads++
            return row(txidWireBytes)
        }

        override suspend fun sdkTxoAmount(outpoint: ByteArray): Long? {
            txoLookups.add(outpoint.toList())
            return txoAmount(outpoint)
        }

        override suspend fun dashjHasTx(txidHex: String): Boolean = hasTx()

        override suspend fun inputsAllOurs(tx: Transaction): Boolean? = allOurs()
    }

    private fun service(source: L1SendProbeSource, scope: CoroutineScope) = L1SendProbeService(
        source = source,
        scope = scope,
        networkParameters = { params },
        economicFeePerKb = { 1000L },
        pollIntervalMs = 1,
        roomTimeoutMs = 25,
        dashjNetworkTimeoutMs = 25,
        feeRepollTimeoutMs = 25
    )

    /** The full pass on a healthy fixture: Room row lands, bytes decode, dashj sees the tx. */
    @Test
    fun probeSdkSend_happyPath_queriesRoomWithWireOrderTxid() = runBlocking {
        val bytes = buildSendTxBytes()
        val fixtureTxid = parse(bytes).txId.toString()
        val source = FakeSource(
            row = { SdkTxRow(feeDuffs = 225, rawTxBytes = bytes) },
            hasTx = { true },
            allOurs = { true }
        )
        val probe = service(source, this)

        probe.probeSdkSend(fixtureTxid, recipientAddress, amountDuffs, emptyWallet = false) { 225L }

        assertTrue(wireTxidBytesOrNull(fixtureTxid)!!.contentEquals(source.lastWireTxid!!))
        // The row fee answered on the first read: no re-poll, no TXO join.
        assertEquals(1, source.rowReads)
        assertTrue(source.txoLookups.isEmpty())
    }

    /**
     * The sdkFee=n/a gap fix, layer (b): the row lands with a null fee and
     * a later "accounting pass" populates it — the probe re-polls the row
     * instead of computing, so the TXO join is never touched.
     */
    @Test
    fun probeSdkSend_rowFeePopulatedLate_repollsInsteadOfComputing() = runBlocking {
        val bytes = buildSendTxBytes()
        val fixtureTxid = parse(bytes).txId.toString()
        var reads = 0
        val source = FakeSource(
            row = {
                reads++
                SdkTxRow(feeDuffs = if (reads >= 3) 408L else null, rawTxBytes = bytes)
            },
            txoAmount = { 1_500_408L }
        )

        service(source, this).probeSdkSend(
            fixtureTxid, recipientAddress, amountDuffs, emptyWallet = false
        ) { 376L }

        assertTrue("expected the fee re-poll to re-read the row", source.rowReads >= 3)
        assertTrue(source.txoLookups.isEmpty())
    }

    /**
     * The sdkFee=n/a gap fix, layer (c): the fee column never populates —
     * the probe joins the decoded tx's inputs against the SDK's own TXO
     * table by the 36-byte outpoint key.
     */
    @Test
    fun probeSdkSend_rowFeeNeverPopulated_joinsInputsAgainstTxoTable() = runBlocking {
        val bytes = buildSendTxBytes() // one input, prevout ("11"*32, 0)
        val fixtureTxid = parse(bytes).txId.toString()
        val expectedOutpoint = sdkOutpointBytes(Sha256Hash.wrap("11".repeat(32)).reversedBytes, 0)
        val source = FakeSource(
            row = { SdkTxRow(feeDuffs = null, rawTxBytes = bytes) },
            txoAmount = { outpoint ->
                if (outpoint.contentEquals(expectedOutpoint)) 1_500_408L else null
            }
        )

        service(source, this).probeSdkSend(
            fixtureTxid, recipientAddress, amountDuffs, emptyWallet = false
        ) { 376L }

        assertEquals(listOf(expectedOutpoint.toList()), source.txoLookups)
    }

    /**
     * THE containment invariant: every read surface throwing (including
     * the caller's dry-run lambda) must never escape a probe pass — the
     * probe can only ever observe a send, not fail one.
     */
    @Test
    fun probeSdkSend_neverThrows_whenEverySurfaceFails() = runBlocking {
        val source = FakeSource(
            row = { throw IllegalStateException("room down") },
            hasTx = { throw IllegalStateException("wallet down") },
            allOurs = { throw IllegalStateException("wallet down") },
            txoAmount = { throw IllegalStateException("room down") }
        )
        val probe = service(source, this)

        // Completes normally despite every collaborator throwing.
        probe.probeSdkSend(txid, recipientAddress, amountDuffs, emptyWallet = false) {
            throw IllegalStateException("estimate boom")
        }
    }

    @Test
    fun probeSdkSend_undecodableRowBytes_stillCompletes() = runBlocking {
        val source = FakeSource(
            row = { SdkTxRow(feeDuffs = null, rawTxBytes = byteArrayOf(1, 2, 3)) }
        )
        service(source, this).probeSdkSend(
            txid, recipientAddress, amountDuffs, emptyWallet = false
        ) { null }
    }

    @Test
    fun probeSdkSendInBackground_containsEscapedFailures() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val source = FakeSource(row = { throw IllegalStateException("room down") })
        val job = service(source, scope).probeSdkSendInBackground(
            "malformed-txid", recipientAddress, amountDuffs, emptyWallet = false
        ) { throw IllegalStateException("estimate boom") }

        job.join()
        assertTrue(job.isCompleted)
        assertFalse("a probe failure escaped into the launched job", job.isCancelled)
    }

    @Test
    fun probeDashjSendInBackground_containsFailures() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val failedEstimate = CompletableDeferred<Long?>().apply {
            completeExceptionally(IllegalStateException("estimate boom"))
        }
        val job = service(FakeSource(), scope).probeDashjSendInBackground(
            tx = parse(buildSendTxBytes()),
            addressBase58 = recipientAddress,
            amountDuffs = amountDuffs,
            emptyWallet = false,
            estimatedFeeDuffs = failedEstimate
        )

        job.join()
        assertTrue(job.isCompleted)
        assertFalse("a baseline failure escaped into the launched job", job.isCancelled)
    }

    @Test
    fun dryRunEstimateAsync_neverCompletesExceptionally() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val deferred = service(FakeSource(), scope)
            .dryRunEstimateAsync { throw IllegalStateException("boom") }

        assertNull(deferred.await())
    }
}
