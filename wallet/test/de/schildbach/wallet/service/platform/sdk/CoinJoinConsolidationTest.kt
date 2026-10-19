package de.schildbach.wallet.service.platform.sdk

import android.app.Application
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.bitcoinj.core.*
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.persistence.entities.*
import org.dashfoundation.dashsdk.wallet.PlatformWalletManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoinJoinConsolidationTest {
    private val params = TestNet3Params.get()
    private val destination = "yTb47qEBpNmgXvYYsHEN4nh8yJwa5iC4Cs"
    private val walletId = "01".repeat(32)
    private val regular = "yXfXh3jFYHHxnJZVsXnPcktCENqPaAhcX1"
    private fun address(index: Int = 0) = CoreAddressEntity(
        destination, poolTypeTag = 0, addressIndex = index, derivationPath = "", accountId = 7L
    )
    private fun coin(index: Int, amount: Long = 1_000_000) : TxoEntity {
        val hash = Sha256Hash.of(index.toString().toByteArray())
        return TxoEntity(TransactionOutPoint(params, 0, hash).bitcoinSerialize(), 0, amount,
            destination, isConfirmed = true, walletId = Utils.HEX.decode(walletId),
            txid = hash.reversedBytes, accountId = 7L)
    }
    private fun transaction(
        coins: List<TxoEntity>, to: String = destination, outputs: List<Long> = listOf(coins.sumOf { it.amount } - 100_000)
    ): Transaction = Transaction(params).apply {
        coins.forEach { addInput(TransactionInput(params, this, byteArrayOf(), TransactionOutPoint(params, it.outpoint, 0))) }
        outputs.forEach { addOutput(Coin.valueOf(it), Address.fromBase58(params, to)) }
    }

    @Test fun addressSelectionExcludesUsedInternalAndForeignAccountRows() {
        val rows = listOf(address().copy(isUsed = true), address(1).copy(poolTypeTag = 1),
            address(2).copy(accountId = 8L), address(4), address(3))
        assertEquals(destination, unusedCoinJoinAddress(rows, 7L))
        assertNull(unusedCoinJoinAddress(rows.take(3), 7L))
    }

    @Test fun largeWalletUsesBoundedPartialConsolidation() {
        val snapshot = CoinJoinConsolidationSnapshot(walletId, (0 until 2000).map(::coin), destination)
        assertTrue(snapshot.needsConsolidation)
        assertFalse(snapshot.drain)
        assertEquals(499_839_800L, snapshot.batchAmount())
        assertFalse(snapshot.copy(coins = snapshot.coins.take(100)).needsConsolidation)
        assertTrue(snapshot.copy(coins = snapshot.coins.take(500)).drain)
    }

    /** key-wallet `LARGEST_FIRST` at 2 duffs/byte: first descending prefix reaching target + fee, change above dust. */
    private fun largestFirstBatch(coins: List<TxoEntity>, target: Long): Transaction {
        val selected = mutableListOf<TxoEntity>()
        for (coin in coins.sortedByDescending { it.amount }) {
            selected += coin
            val total = selected.sumOf { it.amount }
            if (total < target + 2L * (10 + 34 + 148 * selected.size)) continue
            val change = total - target - 2L * (10 + 34 + 34 + 148 * selected.size)
            return transaction(selected, outputs = listOf(target) + listOfNotNull(change.takeIf { it > 546 }))
        }
        throw AssertionError("insufficient funds for $target")
    }

    @Test fun dominantCoinNeverSatisfiesAPartialBatchAloneAndTheMigrationTerminates() {
        // The next 499 coins sum below the old 500-input fee allowance, so the old
        // target sat below the dominant coin: a one-input batch.
        var coins = listOf(coin(0, 100_000_000_000L)) + (1..100).map { coin(it, 1_000L) } + (101..500).map { coin(it, 140L) }
        var snapshot = CoinJoinConsolidationSnapshot(walletId, coins, destination)
        assertFalse(snapshot.drain)
        var batches = 0
        while (!snapshot.drain) {
            assertTrue("no batch may run forever", ++batches <= coins.size)
            val amount = snapshot.batchAmount()
            assertTrue(amount > 0L)
            val tx = largestFirstBatch(coins, amount)
            assertTrue(tx.inputs.size >= COIN_JOIN_BATCH_MIN_INPUTS)
            assertTrue("each batch shrinks the account", tx.inputs.size > tx.outputs.size)
            assertEquals(tx.txId.toString(),
                validateCoinJoinConsolidation(tx.bitcoinSerialize(), snapshot, destination, params, minimumInputs = 2))
            val spent = tx.inputs.map { Utils.HEX.encode(it.outpoint.bitcoinSerialize()) }.toSet()
            coins = coins.filter { Utils.HEX.encode(it.outpoint) !in spent } + tx.outputs.map { output ->
                TxoEntity(TransactionOutPoint(params, output.index.toLong(), tx.txId).bitcoinSerialize(), output.index,
                    output.value.value, destination, isConfirmed = true, walletId = Utils.HEX.decode(walletId),
                    txid = tx.txId.reversedBytes, accountId = 7L)
            }
            snapshot = snapshot.copy(coins = coins)
        }
        assertTrue(snapshot.drain)

        // A one-input partial batch is never accepted.
        val large = CoinJoinConsolidationSnapshot(walletId, (0 until 501).map(::coin), destination)
        assertThrows(IllegalArgumentException::class.java) {
            validateCoinJoinConsolidation(transaction(large.coins.take(1)).bitcoinSerialize(), large, destination, params)
        }
        // Coins that cannot pay their own inputs: no batch is planned.
        val dust = CoinJoinConsolidationSnapshot(walletId, listOf(coin(0, 100_000_000_000L)) + (1..600).map { coin(it, 200L) }, destination)
        assertEquals(0L, dust.batchAmount())
    }

    @Test fun signedBatchRejectsExcessInputsAndForeignOutputs() {
        val snapshot = CoinJoinConsolidationSnapshot(walletId, (0 until 501).map(::coin), destination)
        val tx = transaction(snapshot.coins.take(500))
        assertEquals(tx.txId.toString(), validateCoinJoinConsolidation(tx.bitcoinSerialize(), snapshot, destination, params))
        assertThrows(IllegalArgumentException::class.java) {
            validateCoinJoinConsolidation(transaction(snapshot.coins).bitcoinSerialize(), snapshot, destination, params)
        }
        tx.addOutput(Coin.COIN, Address.fromBase58(params, "yXfXh3jFYHHxnJZVsXnPcktCENqPaAhcX1"))
        assertThrows(IllegalArgumentException::class.java) {
            validateCoinJoinConsolidation(tx.bitcoinSerialize(), snapshot, destination, params)
        }
    }

    private inner class Fixture(scope: TestScope) {
        val config = mockk<DashPayConfig>(relaxed = true)
        var marker = ""
        var consolidation = ""
        var directPhase = ""
        /** Every consolidation-stage write, in order. */
        val consolidationWrites = mutableListOf<String>()
        /** [DashPayConfig.MIXED_FUNDS_MIGRATION_DONE]; null = never written. */
        var done: Boolean? = null
        /** When set, writing [done] fails with this. */
        var doneWriteFailure: Throwable? = null
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        val send = mockk<SdkL1SendService>(relaxed = true)
        val sdk = mockk<DashSdkService>(relaxed = true)
        val db = mockk<DashDatabase>(relaxed = true)
        val manager = mockk<PlatformWalletManager>(relaxed = true)
        val blockchain = mockk<BlockchainStateProvider>(relaxed = true)
        val chainState = mockk<org.dash.wallet.common.data.entity.BlockchainState>(relaxed = true)
        val wallet = mockk<WalletData>(relaxed = true)
        val coins = MutableStateFlow((0 until 101).map(::coin))
        val addresses = MutableStateFlow(listOf(address()))
        var engineBalance = coins.value.sumOf { it.amount }
        var storedTx: TransactionEntity? = null
        /** What the SDK store answers for the replayed transaction's input outpoints. */
        var inputRows = listOf<TxoEntity>()
        /** Persisted TXO rows by hex outpoint (spent inputs, transfer outputs); used when [inputRows] is empty. */
        val txos = mutableMapOf<String, TxoEntity>()
        /** [DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS]. */
        var shieldInputs = ""
        /** Display-hex txids the SDK tracks as shielded top-up asset locks. */
        val shieldLocks = mutableSetOf<String>()
        /** The SDK's persisted address table: address -> its row (owner via [accounts]). */
        val ownedAddresses = mutableMapOf(
            destination to CoreAddressEntity(destination, poolTypeTag = 0, addressIndex = 0, derivationPath = "", accountId = 7L),
            regular to CoreAddressEntity(regular, poolTypeTag = 1, addressIndex = 3, derivationPath = "", accountId = 9L)
        )
        val accounts = mutableMapOf(
            7L to AccountEntity(id = 7, walletId = Utils.HEX.decode(walletId), accountType = 1, accountIndex = 0,
                accountTypeName = "CoinJoin"),
            9L to AccountEntity(id = 9, walletId = Utils.HEX.decode(walletId), accountType = 0, accountIndex = 0,
                accountTypeName = "BIP44")
        )
        val shieldedBalance = MutableStateFlow(Dash.ZERO)
        var coinJoinLocks = 0
        /** Display-hex txids with app-persisted InstantSend lock evidence. */
        var instantLocked = setOf<String>()
        val instantSendLocks = mockk<de.schildbach.wallet.database.dao.InstantSendLockDao>()
        val service: CoinJoinFundsMigrationService
        init {
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { marker }
            coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) } answers { marker = secondArg() }
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } answers { consolidation }
            coEvery { config.set(DashPayConfig.MIXED_FUNDS_CONSOLIDATION, any()) } answers {
                consolidation = secondArg()
                consolidationWrites += consolidation
            }
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE) } answers { directPhase }
            coEvery { config.set(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE, any()) } answers { directPhase = secondArg() }
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) } answers { done }
            coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE, any()) } answers {
                doneWriteFailure?.let { throw it }
                done = secondArg()
            }
            every { sdk.walletManagerOrNull() } returns manager
            every { sdk.databaseOrNull() } returns db
            every { manager.wallets } returns MutableStateFlow(mapOf(walletId to mockk()))
            coEvery { manager.accountBalances(any()) } answers {
                """[{"typeTag":1,"index":0,"confirmed":$engineBalance}]"""
            }
            coEvery { db.accountDao().getByKey(any(), 1, 0) } returns listOf(
                AccountEntity(id = 7, walletId = Utils.HEX.decode(walletId), accountType = 1, accountIndex = 0, accountTypeName = "CoinJoin"))
            every { db.coreAddressDao().observeByAccount(7) } returns addresses
            every { db.txoDao().observeUnspentByWallet(any()) } returns coins
            coEvery { db.transactionDao().getByTxid(any()) } answers { storedTx }
            coEvery { db.txoDao().getByOutpoints(any()) } answers {
                inputRows.ifEmpty { firstArg<List<ByteArray>>().mapNotNull { txos[Utils.HEX.encode(it)] } }
            }
            coEvery { db.txoDao().getByOutpoint(any()) } answers { txos[Utils.HEX.encode(firstArg<ByteArray>())] }
            coEvery { db.coreAddressDao().getByAddress(any()) } answers { ownedAddresses[firstArg()] }
            coEvery { db.accountDao().getById(any()) } answers { accounts[firstArg()] }
            coEvery { db.assetLockDao().fundingTypeForTxid(any()) } answers {
                if (firstArg<String>() in shieldLocks) ASSET_LOCK_FUNDING_TYPE_SHIELDED_TOPUP else null
            }
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS) } answers { shieldInputs }
            coEvery { config.set(DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS, any()) } answers { shieldInputs = secondArg() }
            // Post-cutover, not yet handled: a fresh move is allowed.
            coEvery { send.cutoverCommitted() } returns true
            coEvery { send.coinJoinConsolidationGateOpen() } returns true
            // The pinned SDK has no raw-bytes broadcast.
            coEvery { send.rebroadcastCoinJoinConsolidation(any(), any()) } returns ConsolidationReplay.UNAVAILABLE
            coEvery { blockchain.getState() } returns chainState
            every { chainState.isSynced() } returns true
            coEvery { shield.ensureShieldedReady() } returns true
            coEvery { shield.pendingCoinJoinShieldLockCount() } answers { coinJoinLocks }
            every { shield.observeShieldedBalance() } returns shieldedBalance
            coEvery { shield.shieldMixedFundsFromWallet(any(), any(), any()) } returns SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
            every { wallet.networkParameters } returns params
            coEvery { instantSendLocks.getLockedTxIds(any()) } answers { firstArg<List<String>>().filter { it in instantLocked } }
            service = CoinJoinFundsMigrationService(
                wallet, shield, send, blockchain, config, sdk, scope.backgroundScope, instantSendLocks
            )
        }
    }

    @Test fun consolidationWaitsForFinalityAndPersistedOutputsBeforeShielding() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) } coAnswers {
            arg<suspend (ByteArray) -> Unit>(4)(tx.bitcoinSerialize())
            val prepared = CoinJoinConsolidationState.decode(f.consolidation)
            assertEquals(tx.txId.toString(), prepared.txid)
            // The signed bytes are persisted in the same write, before broadcast.
            assertEquals(Utils.HEX.encode(tx.bitcoinSerialize()), prepared.rawTx)
            SdkWriteResult.Broadcast(tx.txId.toString())
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, destination, 1L, true, any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 1)
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        val output = coin(999).copy(outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
            txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
        f.coins.value = listOf(output)
        f.engineBalance = output.amount
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any()) }
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        // Broadcast by this process: no replay; the settled stage drops the bytes.
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any()) }
        assertEquals("", CoinJoinConsolidationState.decode(f.consolidation).rawTx)
    }

    private fun waitingState(tx: Transaction, raw: ByteArray = tx.bitcoinSerialize()) =
        CoinJoinConsolidationState(walletId, destination, CoinJoinConsolidationState.WAITING,
            tx.txId.toString(), rawTx = Utils.HEX.encode(raw)).encode()

    @Test fun consolidationStateKeepsSignedBytesAndDecodesLegacyStates() {
        val tx = transaction((0 until 3).map(::coin))
        val state = CoinJoinConsolidationState.decode(waitingState(tx))
        assertArrayEquals(tx.bitcoinSerialize(), state.replayBytesOrNull(params))
        val legacy = """{"walletId":"$walletId","destination":"$destination","phase":"waiting",""" +
            """"txid":"${tx.txId}","regularDestination":""}"""
        val decoded = CoinJoinConsolidationState.decode(legacy)
        assertEquals("", decoded.rawTx)
        assertNull(decoded.replayBytesOrNull(params))
        val other = transaction((0 until 2).map(::coin))
        assertNull(state.copy(rawTx = Utils.HEX.encode(other.bitcoinSerialize())).replayBytesOrNull(params))
        assertNull(state.copy(rawTx = "zz").replayBytesOrNull(params))
    }

    @Test fun replayAfterProcessDeathBroadcastsTheSameBytesOnceAndNeverRebuilds() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        val raw = tx.bitcoinSerialize()
        val replayed = mutableListOf<ByteArray>()
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any()) } coAnswers {
            replayed += firstArg<ByteArray>()
            ConsolidationReplay.SUBMITTED
        }
        // Process death after the prepared checkpoint, before the native broadcast.
        f.consolidation = waitingState(tx)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, replayed.size)
        assertArrayEquals(raw, replayed.single())
        coVerify(exactly = 1) { f.send.rebroadcastCoinJoinConsolidation(any(), tx.txId.toString()) }
        // Try again replays the identical bytes once more and resumes observation.
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.retryPendingMigration())
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, replayed.size)
        assertArrayEquals(raw, replayed[1])
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        // Submitted: the stage settles like any broadcast consolidation.
        f.storedTx = TransactionEntity(tx.txId.reversedBytes, raw, context = 1)
        val output = coin(999).copy(outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
            txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
        f.coins.value = listOf(output)
        f.engineBalance = output.amount
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        assertEquals(2, replayed.size)
    }

    @Test fun replayConflictFailsClosedWithoutRebuilding() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any()) } returns ConsolidationReplay.CONFLICT
        f.consolidation = waitingState(tx)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertTrue(requireNotNull(f.service.inFlightMigration()).isExpired())
        assertEquals(CoinJoinConsolidationState.WAITING, CoinJoinConsolidationState.decode(f.consolidation).phase)
        // Try again replays the same bytes again and still never rebuilds.
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.retryPendingMigration())
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertTrue(requireNotNull(f.service.inFlightMigration()).isExpired())
        coVerify(exactly = 2) { f.send.rebroadcastCoinJoinConsolidation(any(), tx.txId.toString()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        assertEquals(tx.txId.toString(), CoinJoinConsolidationState.decode(f.consolidation).txid)
    }

    @Test fun inputSpentByAnotherTransactionIsAConflictWithoutBroadcasting() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        val other = Sha256Hash.of("other".toByteArray())
        f.inputRows = listOf(f.coins.value.first().copy(spendingTxid = other.reversedBytes))
        f.consolidation = waitingState(tx)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertTrue(requireNotNull(f.service.inFlightMigration()).isExpired())
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
    }

    @Test fun inputsSpentByTheRecordedTransactionItselfStillReplay() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        f.inputRows = f.coins.value.map { it.copy(spendingTxid = tx.txId.reversedBytes) }
        f.consolidation = waitingState(tx)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.send.rebroadcastCoinJoinConsolidation(any(), tx.txId.toString()) }
        assertFalse(requireNotNull(f.service.inFlightMigration()).isExpired())
    }

    @Test fun storedBytesThatDoNotMatchTheTxidAreNeverBroadcast() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        val other = transaction(f.coins.value.take(2))
        f.consolidation = waitingState(tx, other.bitcoinSerialize())
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        // Observation continues as for a legacy state.
        assertFalse(requireNotNull(f.service.inFlightMigration()).isExpired())
    }

    @Test fun restartWithPendingConsolidationDoesNotBuildAnotherTransaction() = runTest {
        val f = Fixture(this)
        f.consolidation = CoinJoinConsolidationState(walletId, destination,
            CoinJoinConsolidationState.WAITING, "02".repeat(32)).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        // A legacy state carries no signed bytes: observation only, as before.
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any()) }
    }

    @Test fun missingAddressOrIncompleteSyncCannotStartConsolidation() = runTest {
        val f = Fixture(this)
        f.addresses.value = emptyList()
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        f.addresses.value = listOf(address())
        every { f.chainState.isSynced() } returns false
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals("", f.consolidation)
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
    }

    @Test fun smallAccountShieldsDirectlyWithMarkerBeforeFunding() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) } coAnswers {
            assertNotNull(parseInFlightMigration(f.marker))
            SdkWriteResult.NotBroadcast("definitive rejection")
        }
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertNull(f.service.inFlightMigration())
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
    }

    @Test fun databaseBalanceMismatchPreventsSpending() = runTest {
        val f = Fixture(this)
        f.engineBalance += 100
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun thousandsOfOutputsConsolidateInBoundedBatches() = runTest {
        val f = Fixture(this)
        f.coins.value = (0 until 2000).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        var batches = 0
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) } coAnswers {
            val selected = f.coins.value.sortedByDescending { it.amount }.take(500)
            val tx = transaction(selected)
            arg<suspend (ByteArray) -> Unit>(4)(tx.bitcoinSerialize())
            batches++
            f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 2)
            val output = coin(10_000 + batches).copy(
                outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
                txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
            f.coins.value = f.coins.value.filter { it !in selected } + output
            f.engineBalance = f.coins.value.sumOf { it.amount }
            SdkWriteResult.Broadcast(tx.txId.toString())
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        advanceTimeBy(90_000); runCurrent()
        assertEquals(4, batches)
        assertEquals(4, f.coins.value.size)
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun regularTransferBatchesLargeAccountThenDrainsToBip44() = runTest {
        val f = Fixture(this)
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        f.coins.value = (0 until 2000).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val batchSizes = mutableListOf<Int>()
        val destinations = mutableListOf<String>()
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) } coAnswers {
            val to = arg<String>(1)
            val selected = f.coins.value.sortedByDescending { it.amount }.take(500)
            val tx = transaction(selected, to)
            arg<suspend (ByteArray) -> Unit>(4)(tx.bitcoinSerialize())
            batchSizes += selected.size
            destinations += to
            f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 2)
            val output = coin(10_000 + batchSizes.size).copy(
                outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
                txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
            if (to == regular) f.persistSettled(tx)
            f.coins.value = f.coins.value.filter { it !in selected } +
                if (to == regular) emptyList() else listOf(output)
            f.engineBalance = f.coins.value.sumOf { it.amount }
            SdkWriteResult.Broadcast(tx.txId.toString())
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        assertEquals(MixedFundsMigrationAction.COMBINE, f.service.inFlightMigration()?.action)
        advanceTimeBy(90_000); runCurrent()
        assertEquals(listOf(500, 500, 500, 500, 4), batchSizes)
        assertEquals(List(4) { destination } + regular, destinations)
        assertNull(f.service.inFlightMigration())
        assertEquals("", f.consolidation)
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        coVerify(exactly = 0) { f.send.drainCoinJoinAccountTo(any()) }
    }

    @Test fun interruptedRegularTransferKeepsDestinationAndWaitsForFinality() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value, regular)
        f.consolidation = CoinJoinConsolidationState(walletId, destination,
            CoinJoinConsolidationState.TRANSFERRING, tx.txId.toString(), regular).encode()
        f.coins.value = emptyList()
        f.engineBalance = 0
        f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 0)
        // Persisted, but not final yet: finality still gates completion.
        f.persistSettled(tx)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertEquals(MixedFundsMigrationAction.COMBINE, f.service.inFlightMigration()?.action)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.retryPendingMigration())
        runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        f.storedTx = f.storedTx!!.copy(context = 1)
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        verify(exactly = 0) { f.wallet.unadvertisedDestinationLive() }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun singleInputRegularTransferIsAllowedAndTracked() = runTest {
        val f = Fixture(this)
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        f.coins.value = listOf(coin(0))
        f.engineBalance = f.coins.value.sumOf { it.amount }
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) } coAnswers {
            val tx = transaction(f.coins.value, regular)
            arg<suspend (ByteArray) -> Unit>(4)(tx.bitcoinSerialize())
            assertEquals(CoinJoinConsolidationState.TRANSFERRING,
                CoinJoinConsolidationState.decode(f.consolidation).phase)
            SdkWriteResult.Broadcast(tx.txId.toString())
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, regular, 1L, true, any()) }
    }

    @Test fun unrelatedBip44LockNeitherSkipsTheDrainNorCompletesTheMigration() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        // An ordinary "transfer to shielded" lock is pending; it is not CoinJoin-funded.
        coEvery { f.shield.pendingWalletShieldLockCount() } returns 1
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any()) }
        coVerify(exactly = 0) { f.shield.resumePendingWalletShields() }

        // The unrelated shield lands first: the balance rises, but the CoinJoin coins have not moved.
        f.shieldedBalance.value = Dash.COIN
        advanceTimeBy(60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())

        // The drain is confirmed: its recorded inputs are spent by the shield lock.
        f.settleShieldLock()
        f.coins.value = emptyList()
        f.engineBalance = 0
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
    }

    @Test fun lockedDeviceSigningFailureWaitsAndRetriesInsteadOfExpiring() = runTest {
        // MO-1099: the drain into the shield asset lock ran while the phone was
        // locked; the Keystore refused the seed. Nothing was broadcast, so the
        // migration must keep its processing state and retry the same step,
        // not expire into "could not confirm".
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val locked = SdkWriteResult.NotBroadcast(
            REASON_PRE_BROADCAST_ASSET_LOCK_SIGNING,
            Exception("Asset lock transaction failed: Asset lock builder failed: Transaction builder error: " +
                "Signing failed: mnemonic resolver failed with code 3")
        )
        var deviceLocked = true
        var attempts = 0
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) } coAnswers {
            attempts++
            if (deviceLocked) locked else SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
        }
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(false, f.service.inFlightMigration()?.isExpired())
        assertEquals(CoinJoinConsolidationState.READY, CoinJoinConsolidationState.decode(f.consolidation).phase)

        // Locked for 45 minutes — well past the 30-minute window: it keeps
        // retrying every 30 s and never expires.
        advanceTimeBy(45L * 60_000); runCurrent()
        assertTrue("retried while locked, got $attempts", attempts >= 80)
        assertEquals(false, f.service.inFlightMigration()?.isExpired())
        assertEquals(CoinJoinConsolidationState.READY, CoinJoinConsolidationState.decode(f.consolidation).phase)

        // Unlocked: the next retry signs and moves on to the shield stage.
        deviceLocked = false
        advanceTimeBy(30_000); runCurrent()
        assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
        assertEquals(false, f.service.inFlightMigration()?.isExpired())
    }

    @Test fun otherPreBroadcastShieldFailureEndsTheAttemptAsADefinitiveFailure() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) } returns
            SdkWriteResult.NotBroadcast(REASON_PRE_BROADCAST_ASSET_LOCK_SELECTION, Exception("coin selection is short"))
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        // Recorded as failed (choice + fallback offered), not as "could not confirm".
        assertEquals(true, f.service.inFlightMigration()?.failed)
        assertEquals(false, f.service.inFlightMigration()?.isExpired())
        advanceTimeBy(5L * 60_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun signerLockedFailureIsRecognisedOnlyFromTheLockedSeedSignature() {
        assertTrue(isSignerLockedFailure(SdkWriteResult.NotBroadcast("consolidation preparation failed",
            RuntimeException("wrapped", Exception("Signing failed: mnemonic resolver failed with code 3")))))
        assertFalse(isSignerLockedFailure(SdkWriteResult.NotBroadcast("consolidation preparation failed",
            Exception("Signing failed: mnemonic resolver failed with code 5"))))
        assertFalse(isSignerLockedFailure(SdkWriteResult.NotBroadcast("funding gate closed")))
    }

    @Test fun persistedShieldStageDrainsDespiteAnUnrelatedPendingLock() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        coEvery { f.shield.pendingWalletShieldLockCount() } returns 1
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any()) }
        coVerify(exactly = 0) { f.shield.resumePendingWalletShields() }
        assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
    }

    @Test fun shieldingStageWithoutACoinJoinLockNeverBuildsAnotherDrain() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        // The funding call was reached (e.g. it returned Ambiguous): a missing
        // lock row does not prove nothing was broadcast, and the coins still
        // reading spendable does not either.
        f.consolidation = CoinJoinConsolidationState(walletId, destination, CoinJoinConsolidationState.SHIELDING).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
        assertEquals(false, f.service.inFlightMigration()?.isExpired())
        // The lock row shows up late: it is resumed, never rebuilt.
        f.coinJoinLocks = 1
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.resumePendingWalletShields() }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun shieldInterruptedBeforeTheFundingCallKeepsPollingThenDrainsOnce() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        // Persistence and the engine disagree for now: the snapshot is unknown.
        f.engineBalance = f.coins.value.sumOf { it.amount } + 1
        f.consolidation = CoinJoinConsolidationState(walletId, destination,
            CoinJoinConsolidationState.SHIELD_PREPARING).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        // An unknown snapshot is neither "done" nor a reason to drain.
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        assertEquals(false, f.service.inFlightMigration()?.isExpired())
        f.engineBalance = f.coins.value.sumOf { it.amount }
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any()) }
        assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
    }

    @Test fun shieldStageRecordsTheSubmissionRightBeforeTheFundingCall() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) } coAnswers {
            assertEquals(CoinJoinConsolidationState.SHIELD_PREPARING,
                CoinJoinConsolidationState.decode(f.consolidation).phase)
            arg<suspend () -> Unit>(2)()
            assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
            SdkWriteResult.Ambiguous(Exception("broadcast outcome unknown"))
        }
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun directShieldInterruptedBeforeSubmissionIsReconciledButASubmittedOneIsKept() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val stale = formatInFlightMigration(
            InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis(), 0L)
        )

        // A legacy marker with no journal, or a pending CoinJoin lock: kept.
        f.marker = stale
        assertTrue(f.service.restorePendingShieldMigration())
        f.directPhase = CoinJoinFundsMigrationService.DIRECT_SHIELD_PREPARING
        f.coinJoinLocks = 1
        assertTrue(f.service.restorePendingShieldMigration())
        assertNotNull(f.service.inFlightMigration())

        // Died before the native call, nothing tracked: the marker is dropped.
        f.coinJoinLocks = 0
        assertFalse(f.service.restorePendingShieldMigration())
        assertNull(f.service.inFlightMigration())
        assertEquals("", f.directPhase)

        // The move can run again; once the native call is reached the attempt
        // is never reconciled away.
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) } coAnswers {
            assertEquals(CoinJoinFundsMigrationService.DIRECT_SHIELD_PREPARING, f.directPhase)
            assertNotNull(parseInFlightMigration(f.marker))
            arg<suspend () -> Unit>(2)()
            SdkWriteResult.Ambiguous(Exception("broadcast outcome unknown"))
        }
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(CoinJoinFundsMigrationService.DIRECT_SHIELD_SUBMITTED, f.directPhase)
        assertTrue(f.service.restorePendingShieldMigration())
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun pendingCoinJoinLockIsResumedWithoutASecondDrain() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        f.coinJoinLocks = 1
        f.consolidation = CoinJoinConsolidationState(walletId, destination, CoinJoinConsolidationState.SHIELDING).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        coVerify(exactly = 1) { f.shield.resumePendingWalletShields() }
        // The shielded balance rising is not enough while the CoinJoin lock is unresolved.
        f.shieldedBalance.value = Dash.COIN
        f.coins.value = emptyList()
        f.engineBalance = 0
        advanceTimeBy(60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        f.coinJoinLocks = 0
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
    }

    @Test fun pendingCoinJoinLockIsAdoptedOnlyByAUserActionAndAnUnrelatedOneNever() = runTest {
        val f = Fixture(this)
        coEvery { f.shield.pendingWalletShieldLockCount() } returns 1
        assertFalse(f.service.restorePendingShieldMigration())
        assertNull(f.service.inFlightMigration())
        // MO-1099: the automatic path (startup, resume after unlock, Tools) must
        // not turn an unrecorded CoinJoin lock into a migration nobody chose.
        f.coinJoinLocks = 1
        assertFalse(f.service.restorePendingShieldMigration())
        assertNull(f.service.inFlightMigration())
        // The user's (confirmed) move adopts it instead of spending again.
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(MixedFundsMigrationAction.SHIELD, f.service.inFlightMigration()?.action)
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
    }

    // ── Field failure (build 41): a second drain while lock A is unresolved ──

    /** Lock A: a CoinJoin-funded shield lock in SDK [status]. */
    private fun lockA(status: Int) =
        PendingWalletShieldLock("0205203c".padEnd(64, '0') + ":0", status, 84_997_634L, coinJoinFunded = true)

    /**
     * The shielded runtime over [initial] (mutable: a test resolves or records locks), counted with the
     * REAL [PendingWalletShieldLock] predicates; every resume fails, like the field log.
     */
    private fun Fixture.trackLocks(vararg initial: PendingWalletShieldLock): MutableList<PendingWalletShieldLock> {
        val locks = initial.toMutableList()
        coEvery { shield.pendingCoinJoinShieldLockCount() } answers { locks.count { it.pendingCoinJoinDrain } }
        coEvery { shield.recoveredCoinJoinShieldLockCount() } answers {
            locks.count { it.pendingCoinJoinDrain && it.statusRaw == ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN }
        }
        coEvery { shield.pendingWalletShieldLockCount() } answers { locks.count { it.blocksCutover } }
        coEvery { shield.resumePendingWalletShields() } returns 0
        coEvery { shield.resumePendingWalletShieldsOnUserRequest() } returns 0
        return locks
    }

    /** New CoinJoin coins (SDK CoinJoin balance 1.0961976 DASH): a clean, directly shieldable snapshot. */
    private fun Fixture.newCoinJoinCoinsArrive() {
        coins.value = (200 until 220).map { coin(it, 5_480_988L) }
        engineBalance = coins.value.sumOf { it.amount }
    }

    private fun Fixture.assertNoSecondDrain() {
        assertNothingBuilt()
        coVerify(exactly = 0) { send.drainCoinJoinAccountTo(any()) }
    }

    /** IS-locked (2), ChainLocked (3) and RecoveredFromChain (5) all still hold A's CoinJoin inputs. */
    private val unresolvedLockStatuses = listOf(2, 3, 5)

    @Test fun expiredAcknowledgedShieldWithAFailingLockNeverStartsASecondDrain() = runTest {
        for (status in unresolvedLockStatuses) {
            val f = Fixture(this)
            f.trackLocks(lockA(status))
            // The direct shield of A reached the native call and its window expired.
            val startedAt = System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 60_000L
            f.marker = formatInFlightMigration(
                InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, startedAt, 0L)
            )
            f.directPhase = CoinJoinFundsMigrationService.DIRECT_SHIELD_SUBMITTED
            f.done = true
            var acknowledgedAt: Long? = null
            coEvery {
                f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT)
            } answers { acknowledgedAt }
            coEvery { f.config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT, any()) } answers {
                acknowledgedAt = secondArg()
            }
            assertTrue("status $status", f.expired())
            // The user closed the "could not confirm" guidance.
            f.service.acknowledgeUnconfirmedMigration()
            assertEquals("status $status", startedAt, acknowledgedAt)
            f.newCoinJoinCoinsArrive()
            assertNotNull("status $status: the new coins are visible", f.service.mixedFundsToMigrate())

            // Automatic triggers stay quiet; Tools offers the RECORDED migration.
            assertFalse("status $status", f.service.shouldPresentPrompt())
            assertTrue("status $status", f.service.shouldOfferInTools())
            // Shield (sheet or Tools) and Combine: resume-as-started, never a fresh spend.
            assertEquals("status $status", MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
            assertEquals("status $status", MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
            f.assertNoSecondDrain()
            val kept = requireNotNull(parseInFlightMigration(f.marker))
            assertEquals("status $status", MixedFundsMigrationAction.SHIELD, kept.action)
            assertFalse("status $status", kept.failed)
            assertEquals("status $status: still the uncertain window", startedAt, kept.startedAtMillis)
            assertEquals("status $status", "", f.consolidation)
            assertEquals("status $status", CoinJoinFundsMigrationService.DIRECT_SHIELD_SUBMITTED, f.directPhase)

            // Try again: lock A's one extra resume, nothing else.
            assertEquals("status $status", MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
            coVerify(exactly = 1) { f.shield.resumePendingWalletShieldsOnUserRequest() }
            runCurrent()
            f.assertNoSecondDrain()
            assertNotNull("status $status", f.service.inFlightMigration())
        }
    }

    @Test fun pendingCoinJoinLockWithoutAnyRecordStillBlocksASecondDrain() = runTest {
        for (status in unresolvedLockStatuses) {
            val f = Fixture(this)
            f.trackLocks(lockA(status))
            // Marker, journal and stage cleared or lost; only lock A remains.
            f.newCoinJoinCoinsArrive()
            assertFalse("status $status: no record to restore", f.service.restorePendingShieldMigration())
            if (status == ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN) {
                // Never adopted (it may be a restored historical shield): each move
                // resumes it once, and while it stays unresolved nothing spends and
                // nothing is recorded — no marker, no DONE.
                assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
                assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
                assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
                coVerify(exactly = 3) { f.shield.resumePendingWalletShieldsOnUserRequest() }
                runCurrent()
                f.assertNoSecondDrain()
                assertEquals("", f.marker)
                assertEquals("", f.consolidation)
                assertNull(f.done)
                continue
            }

            assertEquals("status $status", MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
            assertEquals("status $status", MixedFundsMigrationAction.SHIELD, f.service.inFlightMigration()?.action)
            f.assertNoSecondDrain()

            // Lost again before Combine / Try again: each adopts A instead of spending.
            f.marker = ""
            assertEquals("status $status", MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
            f.assertNoSecondDrain()
            f.marker = ""
            assertEquals("status $status", MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
            coVerify(exactly = 1) { f.shield.resumePendingWalletShieldsOnUserRequest() }
            runCurrent()
            f.assertNoSecondDrain()
            assertEquals("status $status", "", f.consolidation)
        }
    }

    // ── Adoption: RecoveredFromChain locks are never adopted; adopted ones resolve ──

    /**
     * Restore on a second device: a historical CoinJoin shield comes back as
     * RecoveredFromChain (5), unrecorded, while CoinJoin coins remain. Every
     * entry point refuses without a record; once the sweep records Platform's
     * already-consumed answer, the next move really spends.
     */
    @Test fun historicalRecoveredLockIsNeverAdoptedAndTheNextMoveSpendsOnceRecorded() = runTest {
        for (entry in listOf("shield", "combine", "retry")) {
            val f = Fixture(this)
            val locks = f.trackLocks(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN))
            f.newCoinJoinCoinsArrive()
            every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
            val first = when (entry) {
                "shield" -> f.service.shieldMixedFunds(Dash.COIN)
                "combine" -> f.service.combineIntoUnmixedBalance()
                else -> f.service.retryPendingMigration()
            }
            assertEquals(entry, MixedFundsMigrationOutcome.NOT_ATTEMPTED, first)
            assertEquals(entry, "", f.marker)
            assertEquals(entry, "", f.shieldInputs)
            assertNull(entry, f.done)
            f.assertNoSecondDrain()

            // The (background) sweep records it: Platform reports it consumed.
            locks[0] = locks[0].copy(platformConsumed = true)
            if (entry == "combine") {
                assertEquals(entry, MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
                assertEquals(entry, regular, CoinJoinConsolidationState.decode(f.consolidation).regularDestination)
                runCurrent()
                coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, regular, any(), true, any()) }
            } else {
                assertEquals(entry, MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
                coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any()) }
            }
        }

        // The tap's own resume can settle it: the same tap then spends.
        val f = Fixture(this)
        val locks = f.trackLocks(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN))
        f.newCoinJoinCoinsArrive()
        coEvery { f.shield.resumePendingWalletShieldsOnUserRequest() } answers {
            locks[0] = locks[0].copy(platformConsumed = true)
            0
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        assertNotEquals(CoinJoinFundsMigrationService.ADOPTED_SHIELD_INPUTS, f.shieldInputs)
    }

    /**
     * A LIVE (status 3) unrecorded CoinJoin lock is adopted and blocks every
     * move until it resolves. Resolved without a verified credit, Try again
     * returns to the choice — no success, no DONE — and the next move spends.
     */
    @Test fun adoptedLiveLockBlocksUntilResolvedThenReturnsToTheChoiceWithoutSuccess() = runTest {
        for (next in listOf("retry", "shield")) {
            val f = Fixture(this)
            val locks = f.trackLocks(lockA(3))
            f.newCoinJoinCoinsArrive()
            assertEquals(next, MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
            assertEquals(next, CoinJoinFundsMigrationService.ADOPTED_SHIELD_INPUTS, f.shieldInputs)
            assertEquals(next, MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
            assertEquals(next, MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
            assertEquals(next, MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
            advanceTimeBy(60_000); runCurrent()
            f.assertNoSecondDrain()
            assertNotNull(next, f.service.inFlightMigration())

            // Resolved elsewhere: no CoinJoin lock pending, no credit to us.
            locks.clear()
            advanceTimeBy(60_000); runCurrent()
            assertNotNull("$next: the watcher never reports it landed", f.service.inFlightMigration())
            if (next == "retry") {
                assertEquals(next, MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
                assertEquals(next, "", f.marker)
                assertEquals(next, "", f.shieldInputs)
                assertNull(next, f.done)
                assertEquals(next, 0, f.service.verifiedLandings)
                f.assertNoSecondDrain()
            }
            // The next move spends the remaining coins.
            assertEquals(next, MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
            coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any()) }
            assertEquals(next, 0, f.service.verifiedLandings)
        }
    }

    /** An adopted lock that resolves WITH our credit is a verified landing, as before. */
    @Test fun adoptedLockThatLandsIsAVerifiedLanding() = runTest {
        val f = Fixture(this)
        val locks = f.trackLocks(lockA(3))
        f.newCoinJoinCoinsArrive()
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        locks.clear()
        f.shieldedBalance.value = Dash.COIN
        advanceTimeBy(15_000); runCurrent()
        assertEquals("", f.marker)
        assertEquals(true, f.done)
        assertEquals(1, f.service.verifiedLandings)
        f.assertNoSecondDrain()
    }

    @Test fun persistedInstantSendLockAdvancesConsolidationBeforeABlock() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) } coAnswers {
            arg<suspend (ByteArray) -> Unit>(4)(tx.bitcoinSerialize())
            SdkWriteResult.Broadcast(tx.txId.toString())
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        // The mirror keeps context=0 and isConfirmed/isInstantLocked=false until
        // the block (txos.isInstantLocked is never set on Android).
        f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 0)
        val output = coin(999).copy(outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
            txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value, isConfirmed = false)
        f.coins.value = listOf(output)
        f.engineBalance = output.amount
        advanceTimeBy(30_000); runCurrent()
        // No lock evidence: still waiting for a block, nothing spent.
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        assertEquals(CoinJoinConsolidationState.WAITING, CoinJoinConsolidationState.decode(f.consolidation).phase)
        // The persisted InstantSend lock is finality for both the checkpoint and the snapshot.
        f.instantLocked = setOf(tx.txId.toString())
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any()) }
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
    }

    @Test fun unlockedUnconfirmedCoinBlocksTheSnapshotButAPersistedLockDoesNot() = runTest {
        val f = Fixture(this)
        val pending = coin(5).copy(isConfirmed = false)
        f.coins.value = listOf(coin(1), pending)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val locks: suspend (Collection<String>) -> Set<String> = { ids -> ids.filter { it in f.instantLocked }.toSet() }
        assertNull(coinJoinConsolidationSnapshot(f.sdk, locks))
        f.instantLocked = setOf(Utils.HEX.encode(pending.txid!!.reversedArray()))
        assertEquals(2, coinJoinConsolidationSnapshot(f.sdk, locks)?.coins?.size)
        // Lock evidence never overrides a coin already claimed by another spend.
        // (TxoEntity equality is by outpoint: reset first so the StateFlow takes the new row.)
        f.coins.value = emptyList()
        f.coins.value = listOf(coin(1), pending.copy(spendingTxid = ByteArray(32)))
        assertNull(coinJoinConsolidationSnapshot(f.sdk, locks))
    }

    @Test fun definitiveShieldFailureAfterConsolidationOffersTheWalletBalanceFallback() = runTest {
        val f = Fixture(this)
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) } returns
            SdkWriteResult.NotBroadcast(REASON_PRE_BROADCAST_ASSET_LOCK_SELECTION, Exception("pool fee not covered"))
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        // A distinct, persisted definitive failure — not the "could not confirm" expiry.
        val failed = f.service.inFlightMigration()
        assertEquals(true, failed?.failed)
        assertEquals(false, failed?.isExpired())
        // The watcher stands down and a resume never re-runs the failed stage.
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
        advanceTimeBy(5L * 60_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }

        // A CoinJoin-funded lock showing up keeps the record: no fresh spend.
        f.coinJoinLocks = 1
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
        assertEquals(true, f.service.inFlightMigration()?.failed)
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }

        // Settled and nothing pending: the switch to the wallet balance runs.
        f.coinJoinLocks = 0
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) } coAnswers {
            val tx = transaction(f.coins.value, regular)
            arg<suspend (ByteArray) -> Unit>(4)(tx.bitcoinSerialize())
            SdkWriteResult.Broadcast(tx.txId.toString())
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        val marker = f.service.inFlightMigration()
        assertEquals(MixedFundsMigrationAction.COMBINE, marker?.action)
        assertEquals(false, marker?.failed)
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, regular, 1L, true, any()) }
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun failedRecordIsKeptWhileAConsolidationStageIsUnsettled() = runTest {
        val f = Fixture(this)
        f.marker = formatInFlightMigration(InFlightMixedFundsMigration(
            MixedFundsMigrationAction.SHIELD, System.currentTimeMillis(), 0L, failed = true))
        f.consolidation = CoinJoinConsolidationState(walletId, destination,
            CoinJoinConsolidationState.SHIELDING).encode()
        assertFalse(f.service.releaseFailedMigration())
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(true, f.service.inFlightMigration()?.failed)
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
    }

    /**
     * Mirror the production source: the build skips [skipped] (still
     * reserved) coins, and a `prepared` rejection abandons it (counted) as a
     * pre-broadcast failure; otherwise [onBroadcast] runs. Returns the
     * abandon count.
     */
    private fun Fixture.mockConsolidation(
        skipped: Int = 0, to: String = destination, onBroadcast: (Transaction) -> Unit = {}
    ): () -> Int {
        var abandoned = 0
        coEvery { send.consolidateCoinJoin(any(), any(), any(), any(), any()) } coAnswers {
            val tx = transaction(coins.value.drop(skipped), to)
            try {
                arg<suspend (ByteArray) -> Unit>(4)(tx.bitcoinSerialize())
            } catch (t: Throwable) {
                abandoned++
                return@coAnswers SdkWriteResult.NotBroadcast("consolidation preparation failed", t)
            }
            onBroadcast(tx)
            SdkWriteResult.Broadcast(tx.txId.toString())
        }
        return { abandoned }
    }

    /** Persist [outpoints] as spent by [spender] (wire-order txid). */
    private fun Fixture.markSpent(outpoints: List<ByteArray>, spender: ByteArray) = outpoints.forEach { op ->
        txos[Utils.HEX.encode(op)] = TxoEntity(op, 0, 1_000_000, destination, isSpent = true,
            walletId = Utils.HEX.decode(walletId), spendingTxid = spender)
    }

    /** Persist [tx] as settled in the SDK store: its inputs spent BY it, its outputs as the wallet's TXOs. */
    private fun Fixture.persistSettled(tx: Transaction) {
        val own = tx.txId.reversedBytes
        markSpent(tx.inputs.map { it.outpoint.bitcoinSerialize() }, own)
        tx.outputs.forEach { output ->
            val op = TransactionOutPoint(params, output.index.toLong(), tx.txId).bitcoinSerialize()
            txos[Utils.HEX.encode(op)] = TxoEntity(op, output.index, output.value.value,
                output.scriptPubKey.getToAddress(params).toString(), walletId = Utils.HEX.decode(walletId), txid = own)
        }
    }

    /** The inputs recorded for the shield, persisted as spent by one shielded top-up lock. */
    private fun Fixture.settleShieldLock(lockTxid: Sha256Hash = Sha256Hash.of("shield-lock".toByteArray())) {
        markSpent(decodeOutpoints(shieldInputs), lockTxid.reversedBytes)
        shieldLocks += lockTxid.toString()
    }

    // ── Completion flag on the consolidated routes ────────────────────

    /** A consolidated SHIELD past its consolidation stage, the shield not yet landed. */
    private suspend fun TestScope.consolidatedShieldInFlight(f: Fixture) {
        f.mockConsolidation { tx ->
            f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 1)
            val output = coin(999).copy(outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
                txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
            f.coins.value = listOf(output)
            f.engineBalance = output.amount
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any()) }
        // STARTED without finish(): not handled until the result is verified.
        assertNull(f.done)
        assertNotNull(f.service.inFlightMigration())
    }

    /** The CoinJoin account drained and the shielded credit visible. */
    private fun landShield(f: Fixture) {
        f.settleShieldLock()
        f.coins.value = emptyList()
        f.engineBalance = 0
        f.coinJoinLocks = 0
        f.shieldedBalance.value = Dash.COIN
    }

    /** A single-input COMBINE broadcast, its transfer not yet final. */
    private fun combineInFlight(f: Fixture) {
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        f.coins.value = listOf(coin(0))
        f.engineBalance = f.coins.value.sumOf { it.amount }
        f.mockConsolidation(to = regular) { tx ->
            f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 0)
            f.persistSettled(tx)
            f.coins.value = emptyList()
            f.engineBalance = 0
        }
    }

    @Test fun consolidatedShieldSetsTheCompletionFlagOnlyOnAVerifiedLanding() = runTest {
        val f = Fixture(this)
        consolidatedShieldInFlight(f)
        landShield(f)
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(true, f.done)
        assertEquals(1, f.service.verifiedLandings)
        // A later CoinJoin payment is not re-offered the one-time migration:
        // the completion flag is what answers, not an empty account.
        coEvery { f.send.cutoverCommitted() } returns true
        f.coins.value = listOf(coin(500))
        f.engineBalance = f.coins.value.sumOf { it.amount }
        assertFalse(f.service.shouldPrompt())
        coVerify { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) }
    }

    @Test fun combineSetsTheCompletionFlagOnlyOnAVerifiedLanding() = runTest {
        val f = Fixture(this)
        combineInFlight(f)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        advanceTimeBy(15_000); runCurrent()
        // Broadcast but not final: still in flight, not handled.
        assertEquals(MixedFundsMigrationAction.COMBINE, f.service.inFlightMigration()?.action)
        assertNull(f.done)
        f.storedTx = f.storedTx?.copy(context = 1)
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(true, f.done)
        assertEquals(1, f.service.verifiedLandings)
    }

    @Test fun completionFlagWriteFailureKeepsTheMarkerUntilARetrySavesIt() = runTest {
        val f = Fixture(this)
        consolidatedShieldInFlight(f)
        f.doneWriteFailure = java.io.IOException("disk full")
        landShield(f)
        advanceTimeBy(15_000); runCurrent()
        // Never cleared without the flag, and not counted as a verified landing.
        assertNotNull(f.service.inFlightMigration())
        assertNull(f.done)
        assertEquals(0, f.service.verifiedLandings)
        // The next watcher run re-verifies the landing and saves the flag first.
        f.doneWriteFailure = null
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(true, f.done)
        assertEquals(1, f.service.verifiedLandings)
    }

    @Test fun consolidatedShieldThatNeverLandsLeavesTheFlagUnset() = runTest {
        val f = Fixture(this)
        consolidatedShieldInFlight(f)
        // The honesty window runs out: the watcher stands down without clearing.
        advanceTimeBy(MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS + 60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertNull(f.done)
        assertEquals(0, f.service.verifiedLandings)
    }

    @Test fun combineThatNeverBecomesFinalLeavesTheFlagUnset() = runTest {
        val f = Fixture(this)
        combineInFlight(f)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        advanceTimeBy(MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS + 60_000); runCurrent()
        assertEquals(MixedFundsMigrationAction.COMBINE, f.service.inFlightMigration()?.action)
        assertNull(f.done)
        assertEquals(0, f.service.verifiedLandings)
    }

    @Test fun definitiveFailureAndItsReleaseLeaveTheFlagUnset() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) } returns
            SdkWriteResult.NotBroadcast(REASON_PRE_BROADCAST_ASSET_LOCK_SELECTION, Exception("coin selection is short"))
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertEquals(true, f.service.inFlightMigration()?.failed)
        assertNull(f.done)
        // Releasing the failed record clears it WITHOUT a verified landing.
        assertTrue(f.service.releaseFailedMigration())
        assertNull(f.service.inFlightMigration())
        assertNull(f.done)
        assertEquals(0, f.service.verifiedLandings)
    }

    // ── A drain must spend the whole snapshot ─────────────────────────

    @Test fun drainValidationRequiresEverySnapshotCoin() {
        fun validate(spent: List<TxoEntity>, snapshot: CoinJoinConsolidationSnapshot) =
            validateCoinJoinConsolidation(transaction(spent).bitcoinSerialize(), snapshot, destination, params)
        val small = CoinJoinConsolidationSnapshot(walletId, (0 until 3).map(::coin), destination)
        assertTrue(small.drain)
        assertEquals(transaction(small.coins).txId.toString(), validate(small.coins, small))
        // ALL skipped a still-reserved UTXO, or a repeated input pads the count.
        assertThrows(IllegalArgumentException::class.java) { validate(small.coins.take(2), small) }
        assertThrows(IllegalArgumentException::class.java) { validate(small.coins.take(2) + small.coins[0], small) }
        // A non-drain batch (over the input limit) still takes a subset.
        val large = CoinJoinConsolidationSnapshot(walletId, (0 until 501).map(::coin), destination)
        assertFalse(large.drain)
        assertEquals(transaction(large.coins.take(500)).txId.toString(), validate(large.coins.take(500), large))
    }

    @Test fun partialCombineDrainIsRejectedBeforeBroadcastAsADefinitiveFailure() = runTest {
        val f = Fixture(this)
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        f.coins.value = (0 until 3).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val abandoned = f.mockConsolidation(skipped = 1, to = regular)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, regular, 1L, true, any()) }
        assertEquals(1, abandoned())
        // Never persisted as TRANSFERRING (nor with a txid/bytes to replay).
        assertTrue(f.consolidationWrites.none {
            CoinJoinConsolidationState.decode(it).phase == CoinJoinConsolidationState.TRANSFERRING
        })
        val stage = CoinJoinConsolidationState.decode(f.consolidation)
        assertEquals(CoinJoinConsolidationState.READY, stage.phase)
        assertEquals("", stage.txid)
        // Definitive and retryable: the choice is offered again, not "could not confirm".
        val marker = f.service.inFlightMigration()
        assertEquals(true, marker?.failed)
        assertEquals(false, marker?.isExpired())
        assertNull(f.done)
        advanceTimeBy(5L * 60_000); runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        // Once the reservation is gone the user's next move drains everything.
        f.mockConsolidation(to = regular)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        assertEquals(CoinJoinConsolidationState.TRANSFERRING, CoinJoinConsolidationState.decode(f.consolidation).phase)
        assertEquals(false, f.service.inFlightMigration()?.failed)
    }

    @Test fun partialShieldPathDrainConsolidationIsRejectedBeforeBroadcast() = runTest {
        val f = Fixture(this)
        // 101 coins: needs consolidation, and the consolidation is a drain.
        val abandoned = f.mockConsolidation(skipped = 1)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, destination, 1L, true, any()) }
        assertEquals(1, abandoned())
        assertTrue(f.consolidationWrites.none {
            CoinJoinConsolidationState.decode(it).phase == CoinJoinConsolidationState.WAITING
        })
        assertEquals(CoinJoinConsolidationState.READY, CoinJoinConsolidationState.decode(f.consolidation).phase)
        assertEquals(true, f.service.inFlightMigration()?.failed)
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun fullShieldPathDrainConsolidationIsPersistedAndBroadcast() = runTest {
        val f = Fixture(this)
        val abandoned = f.mockConsolidation()
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        assertEquals(0, abandoned())
        val stage = CoinJoinConsolidationState.decode(f.consolidation)
        assertEquals(CoinJoinConsolidationState.WAITING, stage.phase)
        assertEquals(transaction(f.coins.value).txId.toString(), stage.txid)
        assertEquals(false, f.service.inFlightMigration()?.failed)
    }

    @Test fun retryRestartsTheWatcherWithoutEverRunningTwo() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        // An unsettled consolidation with no bytes to replay: the watcher only polls.
        f.consolidation = CoinJoinConsolidationState(walletId, destination,
            CoinJoinConsolidationState.WAITING, tx.txId.toString()).encode()
        f.marker = formatInFlightMigration(InFlightMixedFundsMigration(
            MixedFundsMigrationAction.SHIELD, System.currentTimeMillis(), 0L))
        // Each watcher pass calls ensureStarted() once, then polls. The first
        // watcher parks there; while the retry cancels it, a concurrent start
        // (e.g. MainViewModel init) fires before it has finished.
        coEvery { f.sdk.ensureStarted() } coAnswers {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                f.service.startInFlightWatcherIfNeeded()
            }
        }
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        var passes = 0
        coEvery { f.sdk.ensureStarted() } coAnswers { passes++ }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.retryPendingMigration())
        runCurrent()
        // One watcher = one pass now plus one per 15 s poll.
        advanceTimeBy(60_000); runCurrent()
        assertEquals("watcher passes in 60 s", 5, passes)
    }

    // ── Fresh-start policy (Tools entry + the moves themselves) ──────

    private fun shieldMarker(failed: Boolean = false) = formatInFlightMigration(
        InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis(), 0L, failed = failed)
    )

    private fun Fixture.assertNothingBuilt() {
        coVerify(exactly = 0) { send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun freshMovesAndToolsEntryRequireCommittedCutoverAndAReadablePositiveBalance() = runTest {
        val f = Fixture(this)
        // Post-cutover with a CoinJoin balance: offered.
        assertTrue(f.service.shouldOfferInTools())

        // Pre-cutover: dashj still owns L1 — neither offered nor spendable,
        // even though the probe reads a positive CoinJoin balance.
        coEvery { f.send.cutoverCommitted() } returns false
        assertFalse(f.service.shouldOfferInTools())
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())

        // Post-cutover but the SDK CoinJoin balance cannot be read: fail closed.
        coEvery { f.send.cutoverCommitted() } returns true
        coEvery { f.manager.accountBalances(any()) } throws IllegalStateException("engine unavailable")
        assertFalse(f.service.shouldOfferInTools())
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())

        // A cutover read error fails closed too (cutoverCommitted() answers false).
        coEvery { f.manager.accountBalances(any()) } answers {
            """[{"typeTag":1,"index":0,"confirmed":${f.engineBalance}}]"""
        }
        coEvery { f.send.cutoverCommitted() } returns false
        assertFalse(f.service.shouldOfferInTools())

        assertEquals("", f.marker)
        assertEquals("", f.consolidation)
        f.assertNothingBuilt()
    }

    @Test fun closedConsolidationSendGateNeitherOffersNorStartsCombine() = runTest {
        val f = Fixture(this)
        // Post-cutover with a balance, but the send gate is shut and shielding unavailable.
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        coEvery { f.shield.isWalletShieldingAvailable() } returns false
        coEvery { f.send.coinJoinConsolidationGateOpen() } returns false
        assertFalse(f.service.shouldOfferInTools())
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
        // A large account's shield also starts with a consolidation.
        f.coins.value = (0 until 501).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals("", f.marker)
        assertEquals("", f.consolidation)
        f.assertNothingBuilt()
        // A gate read failure fails closed.
        coEvery { f.send.coinJoinConsolidationGateOpen() } throws IllegalStateException("progress unreadable")
        assertFalse(f.service.shouldOfferInTools())
        // Once the gate opens, the same wallet is offered the move and it starts.
        coEvery { f.send.coinJoinConsolidationGateOpen() } returns true
        assertTrue(f.service.shouldOfferInTools())
        f.coins.value = (0 until 3).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
    }

    @Test fun coinsArrivingAfterACompletedMigrationStayMovableFromTools() = runTest {
        val f = Fixture(this)
        // The one-time migration completed earlier; coins reached the
        // CoinJoin account since (another device mixing the same seed, a
        // payment to a CoinJoin address, a late mix confirmation).
        f.done = true
        // The AUTOMATIC prompt stays suppressed ...
        assertFalse(f.service.shouldPrompt())
        // ... but Tools offers the move, and the user's move runs.
        assertTrue(f.service.shouldOfferInTools())
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(MixedFundsMigrationAction.SHIELD, CoinJoinConsolidationState.decode(f.consolidation).action)
        assertNotNull(parseInFlightMigration(f.marker))
    }

    @Test fun toolsEntryStillOffersARecordedMigrationRegardlessOfTheFreshPolicy() = runTest {
        val f = Fixture(this)
        coEvery { f.send.cutoverCommitted() } returns false
        f.done = true
        f.marker = shieldMarker(failed = true)
        assertTrue(f.service.shouldOfferInTools())
    }

    // ── Unknown shield-lock state is never "no lock" ─────────────────

    @Test fun unavailableShieldRuntimeOrUnreadableLockCountBlocksFreshSpends() = runTest {
        for (unknown in listOf("not ready", "count unreadable")) {
            val f = Fixture(this)
            if (unknown == "not ready") {
                coEvery { f.shield.ensureShieldedReady() } returns false
            } else {
                coEvery { f.shield.pendingCoinJoinShieldLockCount() } returns null
            }
            assertEquals(unknown, MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
            assertEquals(unknown, MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
            assertEquals(unknown, MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
            assertEquals(unknown, "", f.marker)
            assertEquals(unknown, "", f.consolidation)
            f.assertNothingBuilt()
        }
    }

    @Test fun failedRecordIsKeptWhileTheShieldRuntimeCannotConfirmNoLock() = runTest {
        val f = Fixture(this)
        val failed = shieldMarker(failed = true)
        f.marker = failed
        coEvery { f.shield.ensureShieldedReady() } returns false
        assertFalse(f.service.releaseFailedMigration())
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
        assertEquals(failed, f.marker)
        // Once the runtime affirmatively reports no CoinJoin-funded lock, it is released.
        coEvery { f.shield.ensureShieldedReady() } returns true
        assertTrue(f.service.releaseFailedMigration())
        assertEquals("", f.marker)
    }

    // ── Malformed persisted marker is repaired, never a dead end ─────

    private val malformedMarkers = listOf(
        "SHIELD|notanumber|0", "garbage", "SHIELD|1|2|bogus", "UNKNOWN|1|2",
        // Negative values are malformed: a -1 baseline would make a zero balance a "landing".
        "SHIELD|${System.currentTimeMillis()}|-1", "SHIELD|-1|0"
    )

    @Test fun negativeMarkerValuesAreMalformedNeverALanding() {
        assertNull(parseInFlightMigration("SHIELD|${System.currentTimeMillis()}|-1"))
        assertNull(parseInFlightMigration("COMBINE|-1|0"))
        assertNull(parseInFlightMigration("SHIELD|-1|0|failed"))
        assertEquals(0L, parseInFlightMigration("SHIELD|0|0")?.baselineShieldedDuffs)
    }

    @Test fun malformedMarkerWithoutStageIsRewrittenAsAnExpiredUncertainShield() = runTest {
        for (malformed in malformedMarkers) {
            for (entry in listOf("restore", "shield", "combine")) {
                val f = Fixture(this)
                f.marker = malformed
                val label = "$malformed via $entry"
                when (entry) {
                    // The automatic path (startup prompt / Tools).
                    "restore" -> assertTrue(label, f.service.restorePendingShieldMigration())
                    // The user's move: pending progress, never a fresh spend.
                    "shield" -> assertEquals(label, MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
                    else -> assertEquals(label, MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
                }
                val repaired = parseInFlightMigration(f.marker)
                assertNotNull(label, repaired)
                assertEquals(label, MixedFundsMigrationAction.SHIELD, repaired!!.action)
                assertFalse(label, repaired.failed)
                assertEquals(label, 0L, repaired.baselineShieldedDuffs)
                assertTrue(label, repaired.isExpired())
                assertEquals(label, "", f.consolidation)
                // Still a recorded migration: blocks a fresh spend, stays resumable from Tools.
                assertTrue(label, f.service.restorePendingShieldMigration())
                assertTrue(label, f.service.shouldOfferInTools())
                advanceTimeBy(60_000); runCurrent()
                f.assertNothingBuilt()
            }
        }
    }

    @Test fun malformedMarkerWithASavedStageIsRecreatedFromTheStage() = runTest {
        val tx = transaction((0 until 101).map(::coin))
        for (malformed in malformedMarkers) {
            val f = Fixture(this)
            f.marker = malformed
            // An unsettled COMBINE consolidation: the stage decides what was spent.
            val stage = CoinJoinConsolidationState(walletId, destination, CoinJoinConsolidationState.WAITING,
                tx.txId.toString(), regularDestination = destination).encode()
            f.consolidation = stage
            assertTrue(malformed, f.service.restorePendingShieldMigration())
            val repaired = parseInFlightMigration(f.marker)
            assertNotNull(malformed, repaired)
            assertEquals(malformed, MixedFundsMigrationAction.COMBINE, repaired!!.action)
            assertFalse(malformed, repaired.failed)
            assertFalse(malformed, repaired.isExpired())
            assertEquals(malformed, stage, f.consolidation)
            // The watcher only observes the recorded txid: never a rebuild.
            advanceTimeBy(60_000); runCurrent()
            assertEquals(malformed, stage, f.consolidation)
            f.assertNothingBuilt()
        }
    }

    // ── Wallet identity pinned through the consolidation build ───────

    @Test fun rebindBetweenWalletCheckAndSnapshotNeverBuildsForTheOldState() = runTest {
        val f = Fixture(this)
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        f.marker = shieldMarker()
        // The watcher's wallet check sees the migration's wallet; the snapshot
        // read right after it already sees a different, rebound wallet.
        every { f.manager.wallets } returnsMany listOf(
            MutableStateFlow(mapOf(walletId to mockk())),
            MutableStateFlow(mapOf("02".repeat(32) to mockk()))
        )
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        f.assertNothingBuilt()
    }

    // ── The watcher never exits silently on the SDK wallet ───────────

    @Test fun unavailableSdkWalletKeepsTheWatcherPollingUntilItReturns() = runTest {
        val f = Fixture(this)
        val wallets = MutableStateFlow<Map<String, org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet>>(emptyMap())
        every { f.manager.wallets } returns wallets
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        f.marker = shieldMarker()
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        // Unavailable: no build, no terminal state — still observing.
        f.assertNothingBuilt()
        assertFalse(f.expired())
        // Back (same wallet): the recorded stage continues in the same watcher.
        wallets.value = mapOf(walletId to mockk())
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, destination, any(), any(), any()) }
    }

    @Test fun reboundSdkWalletStandsDownIntoTheGuidanceAndNeverBuilds() = runTest {
        val tx = transaction((0 until 3).map(::coin))
        val stages = listOf(
            CoinJoinConsolidationState(walletId, destination).encode(),
            // An invalid stage observed by its txid takes the same terminal path.
            rawStage("consolidating", tx.txId.toString())
        )
        for (stage in stages) {
            val f = Fixture(this)
            every { f.manager.wallets } returns MutableStateFlow(mapOf("02".repeat(32) to mockk()))
            f.consolidation = stage
            f.marker = shieldMarker()
            f.service.startInFlightWatcherIfNeeded()
            runCurrent()
            assertTrue(stage, f.expired())
            assertEquals(stage, f.consolidation)
            f.assertNothingBuilt()
        }
    }

    // ── Persisted wallet id: exactly the 32-byte id, normalised ──────

    @Test fun stageWalletIdMustBeTheFull32ByteIdAndIsNormalised() {
        val tx = transaction((0 until 3).map(::coin))
        for (bad in listOf("01", "01".repeat(31), "01".repeat(33), "0".repeat(63))) {
            val read = invalid(rawStage("ready", wallet = bad))
            assertEquals(bad, "invalid walletId", read.reason)
            assertNull(bad, read.walletId)
            assertNull(bad, invalid(rawStage("waiting", tx.txId.toString(), wallet = bad)).observation)
        }
        val upper = CoinJoinConsolidationState.parse(rawStage("ready", wallet = "AB".repeat(32)))
        assertEquals("ab".repeat(32), (upper as ConsolidationStageRead.Valid).state.walletId)
    }

    @Test fun shortWalletIdStageTakesTheInvalidStagePathNotASilentStop() = runTest {
        val f = Fixture(this)
        f.consolidation = rawStage("ready", wallet = "01")
        f.marker = shieldMarker()
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        // Never continued: straight to the guidance.
        assertTrue(f.expired())
        f.assertNothingBuilt()
        // Released only on proof nothing is out; then the user's move starts afresh.
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(walletId, CoinJoinConsolidationState.decode(f.consolidation).walletId)
    }

    // ── App-locked CoinJoin outputs: the drain cannot skip them ──────

    @Test fun appLockedCoinJoinOutputRefusesBothShieldRoutesBeforeAnyRecord() = runTest {
        for (coinCount in listOf(20, 101)) { // direct shield / consolidate first
            val f = Fixture(this)
            f.coins.value = (0 until coinCount).map(::coin)
            f.engineBalance = f.coins.value.sumOf { it.amount }
            every { f.send.coinJoinOutputsLocked() } returns true
            assertEquals("$coinCount", MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
            assertEquals("$coinCount", "", f.marker)
            assertEquals("$coinCount", "", f.consolidation)
            assertEquals("$coinCount", "", f.directPhase)
            f.assertNothingBuilt()
            // Unlocked: the same move runs.
            every { f.send.coinJoinOutputsLocked() } returns false
            assertEquals("$coinCount", MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        }
    }

    // ── Completion tracks the RECORDED transaction, not the whole account ──

    /** A final COMBINE transfer of [spent], recorded as TRANSFERRING and final (IS-locked context). */
    private fun Fixture.finalTransferInFlight(spent: List<TxoEntity>): Transaction {
        val tx = transaction(spent, regular)
        consolidation = CoinJoinConsolidationState(walletId, destination, CoinJoinConsolidationState.TRANSFERRING,
            tx.txId.toString(), regular, Utils.HEX.encode(tx.bitcoinSerialize())).encode()
        marker = formatInFlightMigration(
            InFlightMixedFundsMigration(MixedFundsMigrationAction.COMBINE, System.currentTimeMillis(), 0L)
        )
        storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 1)
        coins.value = emptyList()
        engineBalance = 0
        return tx
    }

    @Test fun finalTransferCompletesOnlyOnceItsBip44OutputIsPersisted() = runTest {
        val f = Fixture(this)
        val tx = f.finalTransferInFlight((0 until 3).map(::coin))
        // Final, inputs spent by it and the CoinJoin account empty — but the
        // BIP44 output is not in the SDK's persisted TXOs yet.
        f.persistSettled(tx)
        val output = Utils.HEX.encode(TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize())
        val persistedOutput = f.txos.remove(output)
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertNull(f.done)
        // A row at that outpoint for another address does not count either.
        f.txos[output] = persistedOutput!!.copy(address = destination)
        advanceTimeBy(30_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertNull(f.done)
        // Persisted on the destination: DONE, then cleared.
        f.txos[output] = persistedOutput
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(true, f.done)
        assertEquals("", f.consolidation)
        assertEquals(1, f.service.verifiedLandings)
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
    }

    @Test fun finalTransferCompletesDespiteACoinJoinCoinReceivedAfterItWasBuilt() = runTest {
        val f = Fixture(this)
        val tx = f.finalTransferInFlight((0 until 3).map(::coin))
        // A new CoinJoin coin arrives after the transfer was built: not part of it.
        val late = coin(700)
        f.coins.value = listOf(late)
        f.engineBalance = late.amount
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        // Its own inputs are not yet persisted as spent by it: not complete.
        advanceTimeBy(60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertNull(f.done)
        f.persistSettled(tx)
        advanceTimeBy(15_000); runCurrent()
        // The recorded transaction settled: complete, without touching the late coin.
        assertNull(f.service.inFlightMigration())
        assertEquals(true, f.done)
        assertEquals("", f.consolidation)
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        // The late coin is not stranded: Tools still offers a NEW migration, which moves it.
        assertTrue(f.service.shouldOfferInTools())
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        f.mockConsolidation(to = regular)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, regular, 1L, true, any()) }
        // ... while the automatic prompt stays suppressed by the completed run.
        f.service.clearInFlightMigration()
        assertFalse(f.service.shouldPrompt())
    }

    @Test fun finalTransferWhoseInputWentElsewhereNeverCompletes() = runTest {
        val f = Fixture(this)
        val tx = f.finalTransferInFlight((0 until 3).map(::coin))
        f.persistSettled(tx)
        // One input is persisted as spent by a DIFFERENT transaction.
        val first = Utils.HEX.encode(tx.inputs[0].outpoint.bitcoinSerialize())
        f.txos[first] = f.txos.getValue(first).copy(spendingTxid = ByteArray(32) { 7 })
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        advanceTimeBy(5L * 60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertNull(f.done)
        // EVERY input spent by one other transaction (its output row still present): not ours either.
        tx.inputs.forEach { input ->
            val key = Utils.HEX.encode(input.outpoint.bitcoinSerialize())
            f.txos[key] = f.txos.getValue(key).copy(spendingTxid = ByteArray(32) { 7 })
        }
        advanceTimeBy(5L * 60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertNull(f.done)
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
    }

    @Test fun shieldCompletesOnItsRecordedInputsDespiteALaterCoinJoinCoin() = runTest {
        val f = Fixture(this)
        val spent = f.coins.value.take(3)
        f.coins.value = spent
        f.engineBalance = spent.sumOf { it.amount }
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) } coAnswers {
            // Recorded BEFORE the native call: exactly the snapshot's coins.
            assertEquals(encodeOutpoints(spent.map { it.outpoint }), f.shieldInputs)
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        // The shield landed, and a NEW CoinJoin coin arrived meanwhile.
        val late = coin(700)
        f.coins.value = listOf(late)
        f.engineBalance = late.amount
        f.shieldedBalance.value = Dash.COIN
        advanceTimeBy(60_000); runCurrent()
        // The recorded inputs are not spent yet: not complete.
        assertNotNull(f.service.inFlightMigration())
        // Spent by one transaction that is NOT a shielded top-up lock: not complete.
        val lock = Sha256Hash.of("shield-lock".toByteArray())
        f.settleShieldLock(lock)
        f.shieldLocks.clear()
        advanceTimeBy(60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertEquals(0, f.service.verifiedLandings)
        // Spent by the shield lock: complete, despite the late coin.
        f.shieldLocks += lock.toString()
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(1, f.service.verifiedLandings)
        assertEquals("", f.shieldInputs)
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        // The late coin stays movable from Tools.
        assertTrue(f.service.shouldOfferInTools())
    }

    @Test fun shieldWithoutRecordedInputsKeepsTheAccountLevelCheck() = runTest {
        val f = Fixture(this)
        // An older record: a SHIELD marker with no recorded inputs.
        f.marker = shieldMarker()
        f.directPhase = CoinJoinFundsMigrationService.DIRECT_SHIELD_SUBMITTED
        f.coins.value = listOf(coin(700))
        f.engineBalance = f.coins.value.sumOf { it.amount }
        f.shieldedBalance.value = Dash.COIN
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        f.coins.value = emptyList()
        f.engineBalance = 0
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
    }

    // ── The BIP44 destination is bound to the building SDK wallet ──────

    @Test fun combineRefusesADestinationTheSdkWalletDoesNotOwn() = runTest {
        val f = Fixture(this)
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        f.coins.value = (0 until 3).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        // The address is another wallet's BIP44 address ...
        f.accounts[9L] = f.accounts.getValue(9L).copy(walletId = Utils.HEX.decode("02".repeat(32)))
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
        // ... or this wallet's CoinJoin address ...
        f.accounts[9L] = f.accounts.getValue(7L).copy(id = 9)
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
        // ... or absent from the persisted address table: never saved, never built.
        f.ownedAddresses.remove(regular)
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
        assertEquals("", f.consolidation)
        assertEquals("", f.marker)
        f.assertNothingBuilt()
    }

    @Test fun savedDestinationNoLongerOwnedIsADefinitiveFailureBeforeBuilding() = runTest {
        val f = Fixture(this)
        f.coins.value = (0 until 3).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        f.consolidation = CoinJoinConsolidationState(walletId, destination, regularDestination = regular).encode()
        f.ownedAddresses.remove(regular)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        f.assertNothingBuilt()
        assertEquals(true, f.service.inFlightMigration()?.failed)
        assertEquals(CoinJoinConsolidationState.READY, CoinJoinConsolidationState.decode(f.consolidation).phase)
    }

    @Test fun ownDestinationAccountReadsThePersistedAddressTable() = runTest {
        val f = Fixture(this)
        assertEquals(OwnDestinationAccount.BIP44, ownDestinationAccount(f.db, walletId, regular))
        assertEquals(OwnDestinationAccount.COIN_JOIN, ownDestinationAccount(f.db, walletId, destination))
        assertNull(ownDestinationAccount(f.db, "02".repeat(32), regular))
        f.accounts[9L] = f.accounts.getValue(9L).copy(accountIndex = 1)
        assertNull(ownDestinationAccount(f.db, walletId, regular))
        f.accounts[9L] = f.accounts.getValue(9L).copy(accountIndex = 0, standardTag = STANDARD_ACCOUNT_TAG_BIP32)
        assertNull(ownDestinationAccount(f.db, walletId, regular))
        assertNull(ownDestinationAccount(f.db, walletId, "yTsGq4wV8WF5GKLaYV2C43zrkr2sfTtysT"))
    }

    // ── Semantically invalid persisted stages ─────────────────────────

    /** A persisted stage as raw JSON, so invalid combinations can be written. */
    private fun rawStage(
        phase: String, txid: String = "", regularDestination: String? = null, rawTx: String? = null,
        wallet: String = walletId
    ): String = org.json.JSONObject().put("walletId", wallet).put("destination", destination)
        .put("phase", phase).put("txid", txid)
        .apply {
            regularDestination?.let { put("regularDestination", it) }
            rawTx?.let { put("rawTx", it) }
        }.toString()

    private fun Fixture.store(tx: Transaction, context: Int = 1) {
        storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = context)
    }

    private suspend fun Fixture.expired(): Boolean = requireNotNull(service.inFlightMigration()).isExpired()

    private fun invalid(raw: String): ConsolidationStageRead.Invalid =
        CoinJoinConsolidationState.parse(raw) as? ConsolidationStageRead.Invalid
            ?: throw AssertionError("expected an invalid stage: $raw")

    @Test fun legacyStagesWrittenByEarlierBuildsStillDecode() {
        val tx = transaction((0 until 3).map(::coin))
        val txid = tx.txId.toString()
        val hex = Utils.HEX.encode(tx.bitcoinSerialize())
        // c37eb3c14 / 5942e46a8: no regularDestination, no rawTx.
        for (phase in listOf("ready", "shielding")) {
            assertEquals(phase, CoinJoinConsolidationState.decode(rawStage(phase)).phase)
        }
        assertEquals(txid, CoinJoinConsolidationState.decode(rawStage("waiting", txid)).txid)
        // 86f9192b2 … c5ca9b2de: regularDestination always written, no rawTx.
        for (phase in listOf("ready", "shield_preparing", "shielding")) {
            assertEquals(phase, CoinJoinConsolidationState.decode(rawStage(phase, regularDestination = "")).phase)
        }
        assertEquals(MixedFundsMigrationAction.COMBINE,
            CoinJoinConsolidationState.decode(rawStage("ready", regularDestination = regular)).action)
        val transfer = CoinJoinConsolidationState.decode(rawStage("transferring", txid, regular))
        assertEquals(regular, transfer.regularDestination)
        assertEquals("", transfer.rawTx)
        // 882aa6a9e on: rawTx in the same write as the txid.
        val replayable = CoinJoinConsolidationState.decode(rawStage("waiting", txid, "", hex))
        assertArrayEquals(tx.bitcoinSerialize(), replayable.replayBytesOrNull(params))
        // Every state this build encodes round-trips.
        val current = CoinJoinConsolidationState(walletId, destination, CoinJoinConsolidationState.TRANSFERRING,
            txid, regular, hex)
        assertEquals(current, CoinJoinConsolidationState.decode(current.encode()))
        assertEquals(CoinJoinConsolidationState(walletId, destination),
            CoinJoinConsolidationState.decode(CoinJoinConsolidationState(walletId, destination).encode()))
    }

    @Test fun semanticallyInvalidStagesAreInvalidNeverReady() {
        val tx = transaction((0 until 3).map(::coin))
        val txid = tx.txId.toString()
        val hex = Utils.HEX.encode(tx.bitcoinSerialize())
        val other = Utils.HEX.encode(transaction((0 until 2).map(::coin)).bitcoinSerialize())

        // Unknown phase: with a txid it is observed (bytes kept: they hash to it).
        val unknown = invalid(rawStage("consolidating", txid, "", hex))
        assertEquals(txid, unknown.observation?.txid)
        assertEquals(hex, unknown.observation?.rawTx)
        assertEquals(walletId, unknown.observation?.walletId)
        assertNull(invalid(rawStage("consolidating")).observation)
        assertNull(invalid(rawStage("")).observation)
        // WAITING/TRANSFERRING need a txid.
        assertNull(invalid(rawStage("waiting")).observation)
        assertNull(invalid(rawStage("transferring", regularDestination = regular)).observation)
        assertNull(invalid(rawStage("waiting", "zz".repeat(32))).observation)
        // Bytes that do not hash to the txid: observed by txid, never replayed.
        val mismatched = invalid(rawStage("waiting", txid, "", other))
        assertEquals(txid, mismatched.observation?.txid)
        assertEquals("", mismatched.observation?.rawTx)
        assertEquals("", invalid(rawStage("waiting", txid, "", "not hex")).observation?.rawTx)
        // TRANSFERRING without its BIP44 destination.
        assertEquals(txid, invalid(rawStage("transferring", txid, "")).observation?.txid)
        assertEquals(txid, invalid(rawStage("transferring", txid)).observation?.txid)
        // Action-implied fields: a COMBINE stage never shields.
        assertEquals(MixedFundsMigrationAction.COMBINE, invalid(rawStage("shielding", regularDestination = regular)).action)
        invalid(rawStage("shield_preparing", regularDestination = regular))
        // A txid or bytes on a phase that never records one.
        assertEquals(txid, invalid(rawStage("ready", txid)).observation?.txid)
        assertEquals(txid, invalid(rawStage("shielding", txid)).observation?.txid)
        assertNull(invalid(rawStage("ready", rawTx = hex)).observation)
        // Missing/garbled fields and unparseable records.
        invalid("""{"walletId":"$walletId","destination":"$destination","phase":"ready"}""")
        invalid("""{"walletId":"$walletId","phase":"ready","txid":""}""")
        invalid("""{"walletId":"$walletId","destination":"$destination","phase":"ready","txid":"","regularDestination":7}""")
        assertNull(invalid(rawStage("waiting", txid, wallet = "")).observation)
        assertNull(invalid(rawStage("waiting", txid, wallet = "wallet")).observation)
        assertNull(invalid("{not json").observation)
        assertThrows(InvalidConsolidationStageException::class.java) {
            CoinJoinConsolidationState.decode(rawStage("consolidating"))
        }
    }

    @Test fun unknownPhaseWithoutTxidNeverBuildsAndKeepsTheRecord() = runTest {
        val f = Fixture(this)
        f.consolidation = rawStage("consolidating")
        val saved = f.consolidation
        // Nothing provable yet: a CoinJoin-funded lock is pending.
        f.coinJoinLocks = 1
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertTrue(f.expired())
        assertTrue(f.service.shouldPresentPrompt())
        // Neither a user move nor Try again treats it as absent or READY.
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
        advanceTimeBy(60_000); runCurrent()
        f.assertNothingBuilt()
        assertEquals(saved, f.consolidation)
        assertTrue(f.expired())
    }

    @Test fun waitingWithoutTxidUnderALiveMarkerNeverBuilds() = runTest {
        val f = Fixture(this)
        f.consolidation = rawStage("waiting")
        f.marker = formatInFlightMigration(
            InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis(), 0L)
        )
        f.coinJoinLocks = 1
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        f.assertNothingBuilt()
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any()) }
        // Stood down into the guidance, the record kept.
        assertTrue(f.expired())
        assertEquals(rawStage("waiting"), f.consolidation)
    }

    @Test fun mismatchedBytesAreObservedByTxidAndNeverContinuedOnceFinal() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        val other = transaction(f.coins.value.take(2))
        f.consolidation = waitingState(tx, other.bitcoinSerialize())
        f.coinJoinLocks = 1
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertFalse(f.expired())
        // Final, with its output settled: a VALID waiting stage would go on to
        // shield here; the invalid one only stands down.
        f.store(tx)
        val output = coin(999).copy(outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
            txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
        f.coins.value = listOf(output)
        f.engineBalance = output.amount
        advanceTimeBy(15_000); runCurrent()
        f.assertNothingBuilt()
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any()) }
        assertTrue(f.expired())
        assertEquals(tx.txId.toString(), invalid(f.consolidation).txid)
    }

    @Test fun transferWithoutDestinationIsObservedNeverSettledNorRebuilt() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value, regular)
        f.consolidation = rawStage("transferring", tx.txId.toString(), "", Utils.HEX.encode(tx.bitcoinSerialize()))
        f.coinJoinLocks = 1
        val replayed = mutableListOf<ByteArray>()
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any()) } coAnswers {
            replayed += firstArg<ByteArray>()
            ConsolidationReplay.SUBMITTED
        }
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        // Only the recorded bytes are replayed.
        assertEquals(1, replayed.size)
        assertArrayEquals(tx.bitcoinSerialize(), replayed.single())
        f.coins.value = emptyList()
        f.engineBalance = 0
        f.persistSettled(tx)
        f.store(tx)
        advanceTimeBy(15_000); runCurrent()
        // No BIP44 destination recorded: never "settled", never DONE.
        assertTrue(f.expired())
        assertNull(f.done)
        f.assertNothingBuilt()
    }

    @Test fun validTxidRecoveryObservesReplaysAndCompletesOnlyOnProvenSettlement() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value, regular)
        val hex = Utils.HEX.encode(tx.bitcoinSerialize())
        f.consolidation = rawStage("finalizing", tx.txId.toString(), regular, hex)
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any()) } returns ConsolidationReplay.SUBMITTED
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertEquals(MixedFundsMigrationAction.COMBINE, f.service.inFlightMigration()?.action)
        advanceTimeBy(30_000); runCurrent()
        coVerify(exactly = 1) { f.send.rebroadcastCoinJoinConsolidation(tx.bitcoinSerialize(), tx.txId.toString()) }
        assertFalse(f.expired())
        // Final but not yet persisted as settled: keeps observing.
        f.store(tx)
        advanceTimeBy(15_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        f.coins.value = emptyList()
        f.engineBalance = 0
        f.persistSettled(tx)
        advanceTimeBy(15_000); runCurrent()
        // Proven: its inputs spent by it and its own BIP44 output persisted.
        assertEquals(true, f.done)
        assertNull(f.service.inFlightMigration())
        assertEquals("", f.consolidation)
        f.assertNothingBuilt()
    }

    @Test fun recoveredTxidThatConflictsStandsDownWithoutBuilding() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        f.consolidation = rawStage("ready", tx.txId.toString(), "", Utils.HEX.encode(tx.bitcoinSerialize()))
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any()) } returns ConsolidationReplay.CONFLICT
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertTrue(f.expired())
        f.assertNothingBuilt()
    }

    @Test fun invalidStageWithoutTxidIsReleasedOnlyOnProofNothingIsOut() = runTest {
        val f = Fixture(this)
        f.consolidation = rawStage("shielding", regularDestination = regular)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertTrue(f.expired())
        val saved = f.consolidation

        // A CoinJoin-funded lock is pending.
        f.coinJoinLocks = 1
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
        assertEquals(saved, f.consolidation)
        f.coinJoinLocks = 0
        // The lock count cannot be read.
        coEvery { f.shield.ensureShieldedReady() } returns false
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
        coEvery { f.shield.ensureShieldedReady() } returns true
        // A coin is claimed by a spend. (TxoEntity equality ignores these
        // fields, so the StateFlow would conflate them: serve them directly.)
        val coins = f.coins.value
        val claimed = coins.first().copy(spendingTxid = Sha256Hash.of("x".toByteArray()).reversedBytes)
        every { f.db.txoDao().observeUnspentByWallet(any()) } returns MutableStateFlow(listOf(claimed) + coins.drop(1))
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
        // A coin is reserved.
        val reserved = coins.first().copy(isLocked = true)
        every { f.db.txoDao().observeUnspentByWallet(any()) } returns MutableStateFlow(listOf(reserved) + coins.drop(1))
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
        every { f.db.txoDao().observeUnspentByWallet(any()) } returns f.coins
        // The engine balance disagrees with the coins.
        f.engineBalance -= 1
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
        f.engineBalance += 1
        // The L1 view is not synced.
        every { f.chainState.isSynced() } returns false
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
        every { f.chainState.isSynced() } returns true
        // A different wallet is bound.
        every { f.manager.wallets } returns MutableStateFlow(mapOf("02".repeat(32) to mockk()))
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
        every { f.manager.wallets } returns MutableStateFlow(mapOf(walletId to mockk()))
        assertEquals(saved, f.consolidation)
        assertNotNull(f.service.inFlightMigration())
        f.assertNothingBuilt()

        // Proven: the record is released back to the choice; nothing spent.
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
        assertEquals("", f.consolidation)
        assertNull(f.service.inFlightMigration())
        assertNull(f.done)
        f.assertNothingBuilt()
    }

    @Test fun freshMoveReleasesAnInvalidStageOnlyOnProofBeforeSpending() = runTest {
        val f = Fixture(this)
        f.consolidation = rawStage("consolidating")
        f.coinJoinLocks = 1
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        assertTrue(f.expired())
        runCurrent()
        f.assertNothingBuilt()
        // Proven: the user's move drops the invalid record, then runs with every gate.
        f.coinJoinLocks = 0
        f.mockConsolidation { }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, destination, 1L, true, any()) }
    }

    @Test fun invalidStageWithATxidIsReleasedOnlyOnceThatTransactionIsFinal() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value.take(3))
        f.consolidation = rawStage("ready", tx.txId.toString())
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        // Unknown to the store: not proof, even though every other check holds.
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.retryPendingMigration())
        assertNotNull(f.service.inFlightMigration())
        f.store(tx, 0)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.retryPendingMigration())
        assertNotNull(f.service.inFlightMigration())
        f.storedTx = f.storedTx!!.copy(context = 2)
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
        assertNull(f.service.inFlightMigration())
        assertEquals("", f.consolidation)
        f.assertNothingBuilt()
    }

    @Test fun batchDestinationOutsideTheCoinJoinAccountIsRefusedBeforeBuilding() = runTest {
        val f = Fixture(this)
        // A SHIELD batch whose saved destination is the wallet's BIP44 address.
        f.consolidation = CoinJoinConsolidationState(walletId, regular).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        f.assertNothingBuilt()
        assertEquals(true, f.service.inFlightMigration()?.failed)
        assertEquals(CoinJoinConsolidationState.READY, CoinJoinConsolidationState.decode(f.consolidation).phase)
    }

    /**
     * Releases an invalid stage while its observation watcher is still waiting,
     * then records a new migration: that migration's watcher must start at once,
     * and the old one must never wake to act on (expire) the new marker.
     */
    private fun releasedObservationNeverTouchesTheNextMigration(release: suspend (Fixture) -> Unit) = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value.take(3))
        f.consolidation = rawStage("ready", tx.txId.toString())
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertFalse(f.expired())
        f.store(tx)
        release(f)
        assertNull(f.service.inFlightMigration())
        f.mockConsolidation { }
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, destination, 1L, true, any()) }
        advanceTimeBy(30_000); runCurrent()
        assertFalse(f.expired())
    }

    @Test fun releasingAnInvalidStageStopsItsWatcherFirst() =
        releasedObservationNeverTouchesTheNextMigration { assertTrue(it.service.releaseFailedMigration()) }

    @Test fun retryReleasingAnInvalidStageStopsItsWatcherFirst() = releasedObservationNeverTouchesTheNextMigration {
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, it.service.retryPendingMigration())
    }

    @Test fun fullSizeSignedBytesValidateWithoutARegex() {
        // 500 inputs with signature-sized scriptSigs: ~150K hex characters.
        val tx = Transaction(params).apply {
            (0 until 500).map(::coin).forEach {
                addInput(TransactionInput(params, this, ByteArray(107) { 1 }, TransactionOutPoint(params, it.outpoint, 0)))
            }
            addOutput(Coin.COIN, Address.fromBase58(params, destination))
        }
        val hex = Utils.HEX.encode(tx.bitcoinSerialize())
        assertTrue(hex.length > 140_000)
        val txid = tx.txId.toString()
        assertEquals(hex, CoinJoinConsolidationState.decode(rawStage("waiting", txid, "", hex)).rawTx)
        assertEquals(hex.uppercase(), CoinJoinConsolidationState.decode(rawStage("waiting", txid, "", hex.uppercase())).rawTx)
        assertEquals("", invalid(rawStage("waiting", txid, "", hex.dropLast(2))).observation?.rawTx)
        assertEquals("", invalid(rawStage("waiting", txid, "", hex.dropLast(1))).observation?.rawTx)
        assertEquals("", invalid(rawStage("waiting", txid, "", hex.dropLast(2) + "zz")).observation?.rawTx)
    }
}
