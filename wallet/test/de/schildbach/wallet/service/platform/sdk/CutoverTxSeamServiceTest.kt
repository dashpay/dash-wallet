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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.Utils
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.CoinsToAddressTxFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Step B7 post-cutover wallet-data seam service:
 * - pre-cutover: provably inert (no SDK subscription, null snapshot, false gate);
 * - post-cutover: snapshot primed WITHOUT history replay, then dashj-observer-style
 *   events (new tx = coins-received/sent-style; lock change = confidence-style,
 *   withConfidence collectors only), neutral filters applied over SDK-fed TxInfo;
 * - rollback: snapshot nulled and gate false again (seam reads return to dashj).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CutoverTxSeamServiceTest {
    private val params = TestNet3Params.get()

    // A real testnet transaction paying 0.023 DASH to yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi.
    private val txData = "01000000033f90cbc2d751c77358b3ff37efd72936b389a17b9ec72bdec4678394814cfe2d000000006a473044022050d2f3b6f097f1973b29bb5a0e98f307f6fc338bb8d29e4a7eb257eebd147ccd022055f88aa06cf90aec97991db9c351fd622fa60fe2cb6bbe6df2ecfef03ca047fa012102d336120a91d7d3497056715f6078e36c56e84c41038cf630260ef3245f6ba39effffffff94cae0fa480e004218a66ea7eae8c0a1a39dbd8ebba966004ddfdcac1e11f089000000006b483045022100ed1fbe54b90c8d69e616b79ba5e03e192bdee6b26f66d40d9da14ae7c7e64a9c022062c54fb1635937a38f3b43b504777c9faf357734cad6f53130870f7e980a3be60121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff3e2611f35c7a2fefadce6b115ce8e14b31b627667af9c04909c0ddcceb8294a3000000006a473044022036bed2e8600ed1a715618ca398553254c14fcea824b77ed784cee5f5b23b84df022041c4821e6e639169ddc891e4d6b4e146e5f4684e5687daf5fcce2fd1f73392230121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff0260182300000000001976a9140205411ec940f9139ea72e3a999d21fceff671e688ac4dc27200000000001976a91425b2b9126bf32e6115a813d019e72b7b9106211b88ac00000000" // ktlint-disable max-line-length

    private val transaction: Transaction = Transaction(params, Utils.HEX.decode(txData))
    private val txidHex: String = transaction.txId.toString()

    private fun record(context: Int = 0, net: Long = 2_300_000L) =
        l1TxUiRecord(transaction.txId.reversedBytes, net, null, context, 0, 1_753_000_000L, 0)

    private fun snapshot(vararg records: L1TxUiRecord) = SdkSeamTxSnapshot(
        walletRecords = records.toList(),
        payloadByTxid = mapOf(txidHex to transaction.bitcoinSerialize()),
        mineOutpoints = emptySet(),
        spenderByOutpoint = emptyMap()
    )

    private class FakeSource(
        var boundWalletId: String? = "cd".repeat(32),
        val snapshots: MutableStateFlow<SdkSeamTxSnapshot> = MutableStateFlow(
            SdkSeamTxSnapshot(emptyList(), emptyMap(), emptySet(), emptyMap())
        ),
        /** When set, served instead of [snapshots] — lets a test gate the priming emission. */
        val snapshotsOverride: Flow<SdkSeamTxSnapshot>? = null
    ) : CutoverUiSource {
        var boundCalls = 0
        var seamSubscriptions = 0

        override suspend fun boundWalletIdOrNull(): String? {
            boundCalls++
            return boundWalletId
        }

        override fun observeTotalDuffs(walletIdHex: String): Flow<Long> = emptyFlow()

        override suspend fun currentTotalDuffs(walletIdHex: String): Long = 0L

        override suspend fun currentBalanceSplitDuffs(walletIdHex: String): SdkBalanceSplitDuffs =
            SdkBalanceSplitDuffs(confirmed = 0L, unconfirmed = 0L)

        override fun observeWalletTxRecords(walletIdHex: String): Flow<List<L1TxUiRecord>> = emptyFlow()

        override fun observeSeamTxSnapshots(walletIdHex: String): Flow<SdkSeamTxSnapshot> {
            seamSubscriptions++
            return snapshotsOverride ?: snapshots.map { it }
        }
    }

    private fun configWithStateFlow(stateFlow: Flow<String?>): DashPayConfig = mockk {
        every { observe(DashPayConfig.CUTOVER_STATE) } returns stateFlow
    }

    private fun configWithState(state: String?): DashPayConfig =
        configWithStateFlow(MutableStateFlow(state))

    private fun buildService(
        source: FakeSource,
        dashPayConfig: DashPayConfig,
        scope: kotlinx.coroutines.CoroutineScope,
        dashjLookup: (org.bitcoinj.core.Sha256Hash) -> Transaction? = { null },
        clockMs: () -> Long = { System.currentTimeMillis() }
    ) = CutoverTxSeamService(
        source = source,
        dashPayConfig = dashPayConfig,
        scope = scope,
        networkParameters = params,
        dashjTxLookup = dashjLookup,
        clockMs = clockMs
    )

    @Test
    fun preCutover_inertAndGateFalse() = runTest {
        val source = FakeSource()
        val service = buildService(source, configWithState("DUAL_RUNNING"), backgroundScope)
        service.start()
        runCurrent()

        assertFalse(service.activeState.value)
        assertNull(service.sdkTxInfosOrNull())
        assertEquals(0, source.boundCalls)
        assertEquals(0, source.seamSubscriptions)
    }

    @Test
    fun postCutover_primesSnapshotWithoutHistoryReplay() = runTest {
        val source = FakeSource(snapshots = MutableStateFlow(snapshot(record(context = 1))))
        val service = buildService(source, configWithState("CUT_OVER"), backgroundScope)

        val observed = mutableListOf<TxInfo>()
        backgroundScope.launch {
            service.observeSdkTransactions(true, emptyArray()).collect { observed += it }
        }
        service.start()
        runCurrent()

        assertTrue(service.activeState.value)
        val infos = service.sdkTxInfosOrNull()
        assertEquals(setOf(txidHex), infos?.keys)
        assertTrue(infos!!.getValue(txidHex).isLocked)
        // The pre-existing (history) tx did NOT replay as an event — exactly
        // like attaching dashj wallet listeners.
        assertTrue(observed.isEmpty())
    }

    @Test
    fun postCutover_newTxEmitsToAllObserversAndFiltersApply() = runTest {
        val source = FakeSource()
        val service = buildService(source, configWithState("CUT_OVER"), backgroundScope)

        val matching = CoinsToAddressTxFilter("yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi", Dash(2_300_000L))
        val nonMatching = CoinsToAddressTxFilter("yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi", Dash.valueOf(1))

        val noConfidence = mutableListOf<TxInfo>()
        val withFilter = mutableListOf<TxInfo>()
        val filteredOut = mutableListOf<TxInfo>()
        backgroundScope.launch {
            service.observeSdkTransactions(false, emptyArray()).collect { noConfidence += it }
        }
        backgroundScope.launch {
            service.observeSdkTransactions(true, arrayOf(matching)).collect { withFilter += it }
        }
        backgroundScope.launch {
            service.observeSdkTransactions(true, arrayOf(nonMatching)).collect { filteredOut += it }
        }
        service.start()
        runCurrent()

        // A NEW wallet-relevant tx lands in the SDK store.
        source.snapshots.value = snapshot(record(context = 0))
        runCurrent()

        assertEquals(listOf(txidHex), noConfidence.map { it.txId })
        assertEquals(listOf(txidHex), withFilter.map { it.txId })
        assertTrue(filteredOut.isEmpty())
    }

    @Test
    fun postCutover_lockChangeIsConfidenceStyleEvent() = runTest {
        val source = FakeSource(snapshots = MutableStateFlow(snapshot(record(context = 0))))
        val service = buildService(source, configWithState("CUT_OVER"), backgroundScope)

        val withConfidence = mutableListOf<TxInfo>()
        val withoutConfidence = mutableListOf<TxInfo>()
        backgroundScope.launch {
            service.observeSdkTransactions(true, emptyArray()).collect { withConfidence += it }
        }
        backgroundScope.launch {
            service.observeSdkTransactions(false, emptyArray()).collect { withoutConfidence += it }
        }
        service.start()
        runCurrent()

        // The SDK records the islock for the already-known pending tx.
        source.snapshots.value = snapshot(record(context = 1))
        runCurrent()

        assertEquals(listOf(txidHex), withConfidence.map { it.txId })
        assertTrue(withConfidence.single().isLocked)
        // Non-confidence observers do not see lock updates (dashj parity).
        assertTrue(withoutConfidence.isEmpty())

        // The synchronous snapshot reflects the new lock state too.
        assertTrue(service.sdkTxInfosOrNull()!!.getValue(txidHex).isLocked)
    }

    @Test
    fun rollback_nullsSnapshotAndGate() = runTest {
        val state = MutableStateFlow<String?>("CUT_OVER")
        val source = FakeSource(snapshots = MutableStateFlow(snapshot(record(context = 1))))
        val service = buildService(source, configWithStateFlow(state), backgroundScope)
        service.start()
        runCurrent()

        assertTrue(service.activeState.value)
        assertEquals(setOf(txidHex), service.sdkTxInfosOrNull()?.keys)

        // CUT_OVER → DUAL_RUNNING: every seam read must return to dashj.
        state.value = "DUAL_RUNNING"
        runCurrent()

        assertFalse(service.activeState.value)
        assertNull(service.sdkTxInfosOrNull())
    }

    // ── Priming-window semantics (activation flip + bounded replay) ───

    @Test
    fun activation_flipsOnlyAfterThePrimingSnapshot() = runTest {
        val gated = MutableSharedFlow<SdkSeamTxSnapshot>()
        val source = FakeSource(snapshotsOverride = gated)
        val service = buildService(source, configWithState("CUT_OVER"), backgroundScope)
        service.start()
        runCurrent()

        // Cutover committed but the pipeline hasn't primed: consumers must
        // stay routed to the (valid, stale-but-real) dashj flow.
        assertEquals(1, source.seamSubscriptions)
        assertFalse(service.activeState.value)
        assertNull(service.sdkTxInfosOrNull())

        gated.emit(snapshot(record(context = 1)))
        runCurrent()

        assertTrue(service.activeState.value)
        assertEquals(setOf(txidHex), service.sdkTxInfosOrNull()?.keys)
    }

    @Test
    fun primingWindowTx_replaysToSubscribersSwitchingAtTheFlip() = runTest {
        // Activation instant == the tx's firstSeen: the tx LANDED during the
        // priming window, so no consumer flow could have carried it.
        val gated = MutableSharedFlow<SdkSeamTxSnapshot>()
        val source = FakeSource(snapshotsOverride = gated)
        val service = buildService(
            source,
            configWithState("CUT_OVER"),
            backgroundScope,
            clockMs = { 1_753_000_000_000L }
        )
        service.start()
        runCurrent()
        assertFalse(service.activeState.value)

        gated.emit(snapshot(record(context = 0)))
        runCurrent()
        assertTrue(service.activeState.value)

        // A consumer switching to the seam flow at the flip (activeState-driven,
        // like WalletDataAdapter's flatMapLatest) still receives the tx event.
        val observed = mutableListOf<TxInfo>()
        backgroundScope.launch {
            service.observeSdkTransactions(false, emptyArray()).collect { observed += it }
        }
        runCurrent()
        assertEquals(listOf(txidHex), observed.map { it.txId })

        // The replay retires on the next snapshot: later subscribers get
        // dashj listener-attach parity (no replay).
        gated.emit(snapshot(record(context = 1)))
        runCurrent()
        val late = mutableListOf<TxInfo>()
        backgroundScope.launch {
            service.observeSdkTransactions(false, emptyArray()).collect { late += it }
        }
        runCurrent()
        assertTrue(late.isEmpty())
    }

    @Test
    fun preActivationRow_neverReplaysAtTheFlip() = runTest {
        // Activation instant strictly after the row's firstSeen: history row,
        // dashj listener-attach parity — prime silently.
        val gated = MutableSharedFlow<SdkSeamTxSnapshot>()
        val source = FakeSource(snapshotsOverride = gated)
        val service = buildService(
            source,
            configWithState("CUT_OVER"),
            backgroundScope,
            clockMs = { 1_753_000_000_001L }
        )
        service.start()
        runCurrent()
        gated.emit(snapshot(record(context = 0)))
        runCurrent()
        assertTrue(service.activeState.value)

        val observed = mutableListOf<TxInfo>()
        backgroundScope.launch {
            service.observeSdkTransactions(true, emptyArray()).collect { observed += it }
        }
        runCurrent()
        assertTrue(observed.isEmpty())
    }

    @Test
    fun rollbackRecommitFlap_reprimesAndReplaysCorrectly() = runTest {
        val state = MutableStateFlow<String?>("CUT_OVER")
        val source = FakeSource(snapshots = MutableStateFlow(snapshot(record(context = 0))))
        val service = buildService(
            source,
            configWithStateFlow(state),
            backgroundScope,
            clockMs = { 1_753_000_000_000L }
        )
        service.start()
        runCurrent()
        assertTrue(service.activeState.value)

        // Rollback: gate false, snapshot nulled.
        state.value = "DUAL_RUNNING"
        runCurrent()
        assertFalse(service.activeState.value)
        assertNull(service.sdkTxInfosOrNull())

        // Recommit: a fresh pipeline primes again and the gate flips again —
        // only after the (re-)priming snapshot.
        state.value = "CUT_OVER"
        runCurrent()
        assertEquals(2, source.seamSubscriptions)
        assertTrue(service.activeState.value)
        assertEquals(setOf(txidHex), service.sdkTxInfosOrNull()?.keys)

        // The re-primed priming-window replay serves flip-switching
        // subscribers of THIS activation too.
        val observed = mutableListOf<TxInfo>()
        backgroundScope.launch {
            service.observeSdkTransactions(false, emptyArray()).collect { observed += it }
        }
        runCurrent()
        assertEquals(listOf(txidHex), observed.map { it.txId })

        // And live events still flow after the recommit.
        val confidence = mutableListOf<TxInfo>()
        backgroundScope.launch {
            service.observeSdkTransactions(true, emptyArray()).collect { confidence += it }
        }
        runCurrent()
        source.snapshots.value = snapshot(record(context = 1))
        runCurrent()
        assertTrue(confidence.any { it.isLocked })
    }

    @Test
    fun gate_failsClosedOnUnknownState() = runTest {
        for (stored in listOf(null, "garbage", "READY_OBSERVED")) {
            val source = FakeSource()
            val service = buildService(source, configWithState(stored), backgroundScope)
            service.start()
            runCurrent()
            assertFalse("stored=$stored", service.activeState.value)
            assertEquals(0, source.seamSubscriptions)
        }
    }
}
