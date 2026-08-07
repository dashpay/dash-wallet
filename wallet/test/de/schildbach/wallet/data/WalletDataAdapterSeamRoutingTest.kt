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

package de.schildbach.wallet.data

import de.schildbach.wallet.service.platform.sdk.CutoverTxSeamService
import de.schildbach.wallet.service.platform.sdk.CutoverUiSource
import de.schildbach.wallet.service.platform.sdk.L1TxUiRecord
import de.schildbach.wallet.service.platform.sdk.SdkBalanceSplitDuffs
import de.schildbach.wallet.service.platform.sdk.SdkSeamTxSnapshot
import de.schildbach.wallet.service.platform.sdk.SeamOutputLockRegistry
import de.schildbach.wallet.service.platform.sdk.l1TxUiRecord
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.Utils
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.WalletTransaction
import org.dash.wallet.common.transactions.TxInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam-routing tests for [WalletDataAdapter]: pre-cutover every transaction
 * read takes the unchanged dashj path; post-cutover [getTransaction],
 * [getTransactions] and [observeTransactions] are served from the SDK-fed
 * [CutoverTxSeamService] (with the held-dashj union/fallback), and a
 * rollback routes live observations back to dashj.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletDataAdapterSeamRoutingTest {
    private val params = TestNet3Params.get()

    init {
        // The dashj-fed TxInfo conversion (Transaction.getConfidence) needs a
        // propagated dashj Context on the test thread.
        org.bitcoinj.core.Context.propagate(org.bitcoinj.core.Context.getOrCreate(params))
    }

    // Two distinct real testnet transactions.
    private val sdkTxData = "01000000033f90cbc2d751c77358b3ff37efd72936b389a17b9ec72bdec4678394814cfe2d000000006a473044022050d2f3b6f097f1973b29bb5a0e98f307f6fc338bb8d29e4a7eb257eebd147ccd022055f88aa06cf90aec97991db9c351fd622fa60fe2cb6bbe6df2ecfef03ca047fa012102d336120a91d7d3497056715f6078e36c56e84c41038cf630260ef3245f6ba39effffffff94cae0fa480e004218a66ea7eae8c0a1a39dbd8ebba966004ddfdcac1e11f089000000006b483045022100ed1fbe54b90c8d69e616b79ba5e03e192bdee6b26f66d40d9da14ae7c7e64a9c022062c54fb1635937a38f3b43b504777c9faf357734cad6f53130870f7e980a3be60121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff3e2611f35c7a2fefadce6b115ce8e14b31b627667af9c04909c0ddcceb8294a3000000006a473044022036bed2e8600ed1a715618ca398553254c14fcea824b77ed784cee5f5b23b84df022041c4821e6e639169ddc891e4d6b4e146e5f4684e5687daf5fcce2fd1f73392230121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff0260182300000000001976a9140205411ec940f9139ea72e3a999d21fceff671e688ac4dc27200000000001976a91425b2b9126bf32e6115a813d019e72b7b9106211b88ac00000000" // ktlint-disable max-line-length
    private val dashjOnlyTxData = "010000000188f39bfd0f75e6d4ecebe2b3efeb9da2549e374405a5b03a1c2f9cbee57c2616000000006a47304402205acbc432ec1a75922f18d8323ec224f8b0e41cd1ecc14c9b803ccb88ea3f687e022013731b89396db78550dee85c3e81fe5de75209a8064e394de12af4ba42e6650801210204d4222b4b0f992567fce5432f01085d2d7c62ee9a0fe61476429584290c164fffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a91486086148698d4cef518ec573fed2b39d4477b63988ac00000000" // ktlint-disable max-line-length

    private val sdkTx = Transaction(params, Utils.HEX.decode(sdkTxData))
    private val dashjOnlyTx = Transaction(params, Utils.HEX.decode(dashjOnlyTxData))

    private fun record(context: Int = 1): L1TxUiRecord =
        l1TxUiRecord(sdkTx.txId.reversedBytes, 2_300_000L, null, context, 0, 1_753_000_000L, 0)

    private fun snapshot(vararg records: L1TxUiRecord) = SdkSeamTxSnapshot(
        walletRecords = records.toList(),
        payloadByTxid = mapOf(sdkTx.txId.toString() to sdkTx.bitcoinSerialize()),
        mineOutpoints = emptySet(),
        spenderByOutpoint = emptyMap()
    )

    private class FakeSource(
        val snapshots: MutableStateFlow<SdkSeamTxSnapshot> = MutableStateFlow(
            SdkSeamTxSnapshot(emptyList(), emptyMap(), emptySet(), emptyMap())
        )
    ) : CutoverUiSource {
        override suspend fun boundWalletIdOrNull(): String? = "cd".repeat(32)
        override fun observeTotalDuffs(walletIdHex: String): Flow<Long> = emptyFlow()

        override suspend fun currentTotalDuffs(walletIdHex: String): Long = 0L
        override suspend fun currentBalanceSplitDuffs(walletIdHex: String): SdkBalanceSplitDuffs =
            SdkBalanceSplitDuffs(confirmed = 0L, unconfirmed = 0L)
        override fun observeWalletTxRecords(walletIdHex: String): Flow<List<L1TxUiRecord>> = emptyFlow()
        override fun observeSeamTxSnapshots(walletIdHex: String): Flow<SdkSeamTxSnapshot> =
            snapshots.map { it }
    }

    private fun emptyBag(): TransactionBag = object : TransactionBag {
        override fun isPubKeyHashMine(pubKeyHash: ByteArray, scriptType: Script.ScriptType?) = false
        override fun isWatchedScript(script: Script) = false
        override fun isPubKeyMine(pubKey: ByteArray) = false
        override fun isPayToScriptHashMine(payToScriptHash: ByteArray) = false
        override fun isCoinJoinPubKeyHashMine(pubKeyHash: ByteArray, scriptType: Script.ScriptType?) = false
        override fun isCoinJoinPubKeyMine(pubKey: ByteArray) = false
        override fun isCoinJoinPayToScriptHashMine(payToScriptHash: ByteArray) = false
        override fun getTransactionPool(pool: WalletTransaction.Pool): Map<Sha256Hash, Transaction> = mapOf()
        override fun isFullyMixed(output: TransactionOutput) = false
        override fun isLockedOutput(outPoint: TransactionOutPoint) = false
        override fun lockOutput(outPoint: TransactionOutPoint) = false
    }

    private fun mockWalletData(dashjTxs: List<Transaction>): WalletData = mockk(relaxed = true) {
        every { transactionBag } returns emptyBag()
        every { networkParameters } returns params
        every { getTransactions(*anyVararg()) } returns dashjTxs
        every { getTransaction(any()) } answers {
            val hash = firstArg<Sha256Hash>()
            dashjTxs.firstOrNull { it.txId == hash }
        }
        every { observeTransactions(any(), *anyVararg()) } returns emptyFlow()
    }

    private fun seamService(
        source: FakeSource,
        state: MutableStateFlow<String?>,
        scope: kotlinx.coroutines.CoroutineScope
    ): CutoverTxSeamService {
        val config = mockk<DashPayConfig> {
            every { observe(DashPayConfig.CUTOVER_STATE) } returns state
        }
        return CutoverTxSeamService(
            source = source,
            dashPayConfig = config,
            scope = scope,
            networkParameters = params,
            dashjTxLookup = { null }
        )
    }

    @Test
    fun preCutover_readsGoStraightToDashj() = runTest {
        val state = MutableStateFlow<String?>("DUAL_RUNNING")
        val service = seamService(FakeSource(), state, backgroundScope)
        service.start()
        runCurrent()

        val walletData = mockWalletData(listOf(dashjOnlyTx))
        val adapter = WalletDataAdapter(walletData, { service }, SeamOutputLockRegistry())

        val txs = adapter.getTransactions()
        assertEquals(listOf(dashjOnlyTx.txId.toString()), txs.map { it.txId })
        verify(exactly = 1) { walletData.getTransactions(*anyVararg()) }

        assertEquals(dashjOnlyTx.txId.toString(), adapter.getTransaction(dashjOnlyTx.txId.toString())?.txId)
        assertNull(adapter.getTransaction(sdkTx.txId.toString()))

        // observeTransactions attaches the dashj observer.
        val collected = mutableListOf<TxInfo>()
        backgroundScope.launch { adapter.observeTransactions(true).collect { collected += it } }
        runCurrent()
        verify(exactly = 1) { walletData.observeTransactions(true, *anyVararg()) }
    }

    @Test
    fun postCutover_readsServedFromSdkWithHeldDashjUnion() = runTest {
        val state = MutableStateFlow<String?>("CUT_OVER")
        val source = FakeSource(MutableStateFlow(snapshot(record(context = 1))))
        val service = seamService(source, state, backgroundScope)
        service.start()
        runCurrent()

        val walletData = mockWalletData(listOf(sdkTx, dashjOnlyTx))
        val adapter = WalletDataAdapter(walletData, { service }, SeamOutputLockRegistry())

        // getTransactions: SDK set wins for txs the SDK knows; held-dashj
        // history the SDK never saw is unioned in (dedup'd by txid).
        val txs = adapter.getTransactions()
        assertEquals(setOf(sdkTx.txId.toString(), dashjOnlyTx.txId.toString()), txs.map { it.txId }.toSet())
        val sdkFed = txs.first { it.txId == sdkTx.txId.toString() }
        // Proof it came from the SDK snapshot, not the dashj conversion:
        // the SDK record's net amount and lock state, and no raw handle.
        assertEquals(2_300_000L, sdkFed.netValueDuffs)
        assertTrue(sdkFed.isLocked)
        assertNull(sdkFed.raw)

        // getTransaction: SDK-first, dashj fallback.
        assertNull(adapter.getTransaction(sdkTx.txId.toString())!!.raw)
        assertEquals(dashjOnlyTx.txId.toString(), adapter.getTransaction(dashjOnlyTx.txId.toString())?.txId)

        // observeTransactions: served from the seam service, not dashj.
        val collected = mutableListOf<TxInfo>()
        backgroundScope.launch { adapter.observeTransactions(true).collect { collected += it } }
        runCurrent()
        verify(exactly = 0) { walletData.observeTransactions(any(), *anyVararg()) }

        // An SDK lock update reaches the observer as a confidence-style event.
        source.snapshots.value = snapshot(record(context = 3))
        runCurrent()
        assertEquals(listOf(sdkTx.txId.toString()), collected.map { it.txId })
    }

    @Test
    fun rollback_routesLiveObservationBackToDashj() = runTest {
        val state = MutableStateFlow<String?>("CUT_OVER")
        val source = FakeSource(MutableStateFlow(snapshot(record(context = 1))))
        val service = seamService(source, state, backgroundScope)
        service.start()
        runCurrent()

        val walletData = mockWalletData(listOf(dashjOnlyTx))
        val adapter = WalletDataAdapter(walletData, { service }, SeamOutputLockRegistry())

        backgroundScope.launch { adapter.observeTransactions(false).collect {} }
        runCurrent()
        verify(exactly = 0) { walletData.observeTransactions(any(), *anyVararg()) }

        // Rollback: the LIVE observation switches to the dashj observer and
        // sync reads take the dashj path again.
        state.value = "DUAL_RUNNING"
        runCurrent()
        verify(exactly = 1) { walletData.observeTransactions(false, *anyVararg()) }
        assertEquals(listOf(dashjOnlyTx.txId.toString()), adapter.getTransactions().map { it.txId })
    }

    // ── Seam output locks (post-cutover CrowdNode signup lock step) ───

    @Test
    fun postCutover_lockOutputsOfSdkOnlyTxRegistersAtTheSeamWithoutThrowing() = runTest {
        val state = MutableStateFlow<String?>("CUT_OVER")
        val source = FakeSource(MutableStateFlow(snapshot(record(context = 0))))
        val service = seamService(source, state, backgroundScope)
        service.start()
        runCurrent()

        // The held dashj wallet never saw the incoming SDK-fed tx.
        val walletData = mockWalletData(listOf(dashjOnlyTx))
        val registry = SeamOutputLockRegistry()
        val adapter = WalletDataAdapter(walletData, { service }, registry)

        // Output 0 pays 0.023 to yLW8Vfeb…; output 1 pays elsewhere.
        adapter.lockOutputsPayingTo(sdkTx.txId.toString(), "yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi")

        assertTrue(registry.hasAnyLocks())
        assertTrue(registry.isLocked(sdkTx.txId.toString(), 0))
        assertFalse(registry.isLocked(sdkTx.txId.toString(), 1))
        verify(exactly = 0) { walletData.lockOutput(any()) }
    }

    @Test
    fun lockOutputsOfHeldWalletTxStillUsesTheDashjLock() = runTest {
        val state = MutableStateFlow<String?>("DUAL_RUNNING")
        val service = seamService(FakeSource(), state, backgroundScope)
        service.start()
        runCurrent()

        val walletData = mockWalletData(listOf(sdkTx))
        val registry = SeamOutputLockRegistry()
        val adapter = WalletDataAdapter(walletData, { service }, registry)

        adapter.lockOutputsPayingTo(sdkTx.txId.toString(), "yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi")

        // Pre-cutover / self-authored behavior byte-identical: the dashj wallet
        // lock is taken (one matching P2PKH output) and the registry stays empty.
        verify(exactly = 1) { walletData.lockOutput(any()) }
        assertFalse(registry.hasAnyLocks())
    }

    @Test
    fun preCutover_lockAndWaitStillFailClosedOnAWalletMiss() = runTest {
        val state = MutableStateFlow<String?>("DUAL_RUNNING")
        val service = seamService(FakeSource(), state, backgroundScope)
        service.start()
        runCurrent()

        val registry = SeamOutputLockRegistry()
        val adapter = WalletDataAdapter(mockWalletData(emptyList()), { service }, registry)

        var lockThrew = false
        try {
            adapter.lockOutputsPayingTo(sdkTx.txId.toString(), "yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi")
        } catch (e: IllegalStateException) {
            lockThrew = true
        }
        assertTrue("lockOutputsPayingTo must fail closed pre-cutover", lockThrew)
        assertFalse(registry.hasAnyLocks())

        var waitThrew = false
        try {
            adapter.waitUntilLocked(sdkTx.txId.toString())
        } catch (e: IllegalStateException) {
            waitThrew = true
        }
        assertTrue("waitUntilLocked must fail closed pre-cutover", waitThrew)
    }

    @Test
    fun postCutover_waitUntilLockedResolvesOnASeamIslockEvent() = runTest {
        val state = MutableStateFlow<String?>("CUT_OVER")
        val source = FakeSource(MutableStateFlow(snapshot(record(context = 0))))
        val service = seamService(source, state, backgroundScope)
        service.start()
        runCurrent()

        val adapter = WalletDataAdapter(mockWalletData(emptyList()), { service }, SeamOutputLockRegistry())

        var completed = false
        val wait = backgroundScope.launch {
            adapter.waitUntilLocked(sdkTx.txId.toString())
            completed = true
        }
        runCurrent()
        assertFalse("must still be waiting while the tx is unlocked", completed)

        // The SDK records the islock — the seam confidence event resolves the wait.
        source.snapshots.value = snapshot(record(context = 1))
        runCurrent()
        assertTrue(completed)
        wait.join()
    }

    @Test
    fun postCutover_waitUntilLockedReturnsImmediatelyForAnAlreadyLockedSeamTx() = runTest {
        val state = MutableStateFlow<String?>("CUT_OVER")
        val source = FakeSource(MutableStateFlow(snapshot(record(context = 1))))
        val service = seamService(source, state, backgroundScope)
        service.start()
        runCurrent()

        val adapter = WalletDataAdapter(mockWalletData(emptyList()), { service }, SeamOutputLockRegistry())

        // Current-state replay: no further snapshot/event is needed.
        adapter.waitUntilLocked(sdkTx.txId.toString())
    }
}
