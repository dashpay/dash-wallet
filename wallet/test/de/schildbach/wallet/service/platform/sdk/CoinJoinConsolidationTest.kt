package de.schildbach.wallet.service.platform.sdk

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        /** When set, an `editPreferences` transaction fails to commit with this (nothing applied). */
        var editFailure: Throwable? = null
        /** Committed `editPreferences` transactions. */
        var edits = 0
        /** How long an `editPreferences` transaction waits before it runs (a slow store). */
        var editDelayMs = 0L
        /** Runs after each committed `editPreferences` transaction (another writer stepping in). */
        var afterEdit: () -> Unit = {}
        /** Runs as each `editPreferences` transaction starts, before it reads (may throw to fail it). */
        var beforeEdit: () -> Unit = {}
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
        /** Their `asset_locks` status by display-hex txid; Consumed (4) when absent. */
        val shieldLockStatus = mutableMapOf<String, Int>()
        /** [DashPayConfig.SHIELD_LOCKS_PLATFORM_CONSUMED]. */
        var platformConsumed = emptySet<String>()
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
            // DataStore's edit: the transform runs on a copy, applied only if the commit succeeds.
            coEvery { config.editPreferences(any()) } coAnswers {
                if (editDelayMs > 0L) delay(editDelayMs)
                beforeEdit()
                val preferences = mutablePreferencesOf(
                    DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS to shieldInputs,
                    DashPayConfig.MIXED_FUNDS_CONSOLIDATION to consolidation,
                    DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT to marker,
                    DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE to directPhase
                )
                done?.let { preferences[DashPayConfig.MIXED_FUNDS_MIGRATION_DONE] = it }
                firstArg<(MutablePreferences) -> Unit>().invoke(preferences)
                editFailure?.let { throw it }
                val doneAfter = preferences[DashPayConfig.MIXED_FUNDS_MIGRATION_DONE]
                if (doneAfter != done) doneWriteFailure?.let { throw it }
                shieldInputs = preferences[DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS] ?: ""
                val stage = preferences[DashPayConfig.MIXED_FUNDS_CONSOLIDATION] ?: ""
                if (stage != consolidation && stage.isNotEmpty()) consolidationWrites += stage
                consolidation = stage
                marker = preferences[DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT] ?: ""
                directPhase = preferences[DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE] ?: ""
                done = doneAfter
                edits++
                afterEdit()
            }
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
            coEvery { config.getPreservingErrors(DashPayConfig.SHIELD_LOCKS_PLATFORM_CONSUMED) } answers {
                platformConsumed
            }
            every {
                db.assetLockDao().observeByWalletAndFundingType(any(), ASSET_LOCK_FUNDING_TYPE_SHIELDED_TOPUP)
            } answers {
                flowOf(
                    shieldLocks.map { txid ->
                        AssetLockEntity(
                            "$txid:0", Utils.HEX.decode(walletId), ByteArray(0), fundingTypeRaw = 5,
                            identityIndexRaw = 0, amountDuffs = 1L,
                            statusRaw = shieldLockStatus[txid] ?: ASSET_LOCK_STATUS_CONSUMED
                        )
                    }
                )
            }
            coEvery { config.set(DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS, any()) } answers { shieldInputs = secondArg() }
            // Post-cutover, not yet handled: a fresh move is allowed.
            coEvery { send.cutoverCommitted() } returns true
            coEvery { send.coinJoinConsolidationGateOpen() } returns true
            // The pinned SDK has no raw-bytes broadcast.
            coEvery { send.rebroadcastCoinJoinConsolidation(any(), any(), any()) } returns ConsolidationReplay.UNAVAILABLE
            coEvery { blockchain.getState() } returns chainState
            every { chainState.isSynced() } returns true
            coEvery { shield.ensureShieldedReady() } returns true
            coEvery { shield.pendingCoinJoinShieldLockCount() } answers { coinJoinLocks }
            // Unless a test models Platform records (trackLocks), no lock is retry-suppressed.
            coEvery { shield.unsuppressedCoinJoinShieldLockCount(any()) } coAnswers {
                shield.pendingCoinJoinShieldLockCount()
            }
            every { shield.observeShieldedBalance() } returns shieldedBalance
            coEvery { shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } returns
                SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 1)
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        val output = coin(999).copy(outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
            txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
        f.coins.value = listOf(output)
        f.engineBalance = output.amount
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        // Broadcast by this process: no replay; the settled stage drops the bytes.
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) }
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
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) } coAnswers {
            replayed += secondArg<ByteArray>()
            ConsolidationReplay.SUBMITTED
        }
        // Process death after the prepared checkpoint, before the native broadcast.
        f.consolidation = waitingState(tx)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, replayed.size)
        assertArrayEquals(raw, replayed.single())
        coVerify(exactly = 1) { f.send.rebroadcastCoinJoinConsolidation(any(), any(), tx.txId.toString()) }
        // Try again replays the identical bytes once more and resumes observation.
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.retryPendingMigration())
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, replayed.size)
        assertArrayEquals(raw, replayed[1])
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        // Submitted: the stage settles like any broadcast consolidation.
        f.storedTx = TransactionEntity(tx.txId.reversedBytes, raw, context = 1)
        val output = coin(999).copy(outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
            txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
        f.coins.value = listOf(output)
        f.engineBalance = output.amount
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        assertEquals(2, replayed.size)
    }

    // ── Reset Wallet: nothing of the wiped wallet's migration reaches the next one ──

    /** [tx]'s CoinJoin output lands in the snapshot (finality and the new UTXO set reached Room). */
    private fun Fixture.landBatch(tx: Transaction) {
        store(tx)
        val output = coin(999).copy(outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
            txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
        coins.value = listOf(output)
        engineBalance = output.amount
    }

    /** The wipe's config clear. */
    private fun Fixture.clearConfigs() {
        consolidation = ""
        marker = ""
        directPhase = ""
        consolidationWrites.clear()
    }

    /**
     * Between the wipe's stop and its config clear nothing relaunches the watcher or
     * writes: no prompt, no restore, no move, no watcher start — even once the old
     * batch settles. After the wipe completes the next wallet migrates normally.
     */
    @Test fun walletWipeLatchesTheServiceUntilTheWipeCompletes() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        f.consolidation = waitingState(tx)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        f.service.stopForWalletWipe()
        val writes = f.consolidationWrites.size
        val marker = f.marker
        assertFalse(f.service.shouldPresentPrompt())
        assertFalse(f.service.shouldPrompt())
        assertFalse(f.service.restorePendingShieldMigration())
        f.service.startInFlightWatcherIfNeeded()
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
        f.landBatch(tx)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(writes, f.consolidationWrites.size)
        assertEquals(marker, f.marker)
        f.assertNothingBuilt()

        // The wipe completed: the restored wallet (same id) starts its own move.
        f.clearConfigs()
        f.service.resumeAfterWalletWipe()
        f.mockConsolidation { }
        f.coins.value = (0 until 101).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(walletId, destination, any(), any(), any()) }
    }

    /** A user move inside its native call when the wipe starts: the wipe waits for it, and it writes nothing after. */
    @Test fun walletWipeWaitsForARunningMoveWhichThenWritesNothing() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val inNativeCall = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } coAnswers {
            inNativeCall.complete(Unit)
            delay(5_000)
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        }
        val move = async { f.service.shieldMixedFunds(Dash.COIN) }
        inNativeCall.await()
        val marker = f.marker
        assertEquals(CoinJoinFundsMigrationService.DIRECT_SHIELD_PREPARING, f.directPhase)
        val wipe = async { f.service.stopForWalletWipe() }
        runCurrent()
        assertFalse(wipe.isCompleted)
        advanceTimeBy(6_000); runCurrent()
        assertTrue(wipe.isCompleted)
        assertTrue(move.isCancelled)
        assertNull(f.done)
        assertEquals(marker, f.marker)
        assertEquals(CoinJoinFundsMigrationService.DIRECT_SHIELD_PREPARING, f.directPhase)
    }

    /**
     * A watcher stuck in a non-cancellable native step: the wipe proceeds after its cap,
     * and when the step returns — even after the wipe completed — its write is refused.
     */
    @Test fun walletWipeProceedsPastAStuckWatcherWhoseLaterWritesAreRefused() = runTest {
        val f = Fixture(this)
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) {
                delay(60_000)
                arg<suspend (ByteArray) -> Unit>(4)(transaction(f.coins.value).bitcoinSerialize())
            }
            SdkWriteResult.Broadcast("stuck")
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        val wipe = async { f.service.stopForWalletWipe() }
        advanceTimeBy(CoinJoinFundsMigrationService.WIPE_JOIN_MS - 1); runCurrent()
        assertFalse(wipe.isCompleted)
        advanceTimeBy(2); runCurrent()
        assertTrue(wipe.isCompleted)
        f.clearConfigs()
        f.service.resumeAfterWalletWipe()
        // The next wallet's stage: a retried (swallowed) refusal would build from it.
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(emptyList<String>(), f.consolidationWrites)
        assertEquals("", f.marker)
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
    }

    /** Reset Wallet forgets this process's replayed txids: the next wallet's stage is replayed once again. */
    @Test fun walletWipeForgetsReplayedConsolidations() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        var replays = 0
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) } coAnswers {
            replays++
            ConsolidationReplay.SUBMITTED
        }
        f.consolidation = waitingState(tx)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertEquals(1, replays)
        f.service.stopForWalletWipe()
        f.marker = ""
        f.service.resumeAfterWalletWipe()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertEquals(2, replays)
        f.service.stopForWalletWipe()
    }

    /**
     * A public writer called before the wipe whose write lands after it completed carries the
     * wiped wallet's epoch: refused, so it never touches the next wallet's config.
     */
    @Test fun preWipeCallsNeverWriteIntoTheNextWalletsConfig() = runTest {
        val f = Fixture(this)
        // Acknowledge: its marker read spans the wipe.
        f.marker = shieldMarker()
        var acknowledged: Long? = null
        coEvery { f.config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT, any()) } answers {
            acknowledged = secondArg()
        }
        coEvery { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } coAnswers {
            delay(5_000)
            f.marker
        }
        val acknowledge = async { f.service.acknowledgeUnconfirmedMigration() }
        // Clear: its one transaction commits after the wipe; it must not clear the next wallet's stage.
        f.editDelayMs = 5_000
        val clear = async { f.service.clearInFlightMigration() }
        runCurrent()
        f.service.stopForWalletWipe()
        f.service.resumeAfterWalletWipe()
        // The next wallet records its own migration.
        val next = CoinJoinConsolidationState(walletId, destination).encode()
        f.consolidation = next
        advanceTimeBy(6_000); runCurrent()
        assertTrue(acknowledge.isCancelled)
        assertTrue(clear.isCancelled)
        assertNull(acknowledged)
        assertEquals(next, f.consolidation)
        // The next wallet's own calls work.
        coEvery { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { f.marker }
        f.service.acknowledgeUnconfirmedMigration()
        assertNotNull(acknowledged)
    }

    // ── SHIELD completion: the migration's own proof, never a shielded balance ──

    /** A direct SHIELD of 100 coins broadcast; its inputs recorded, its lock not yet consumed. */
    private suspend fun TestScope.directShieldInFlight(f: Fixture) {
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        assertEquals(100, decodeOutpoints(f.shieldInputs).size)
        assertNotNull(f.service.inFlightMigration())
    }

    /**
     * Completion needs BOTH halves of the proof: the recorded inputs spent by a shielded top-up
     * lock, and no CoinJoin-funded lock still resumable. Either one alone keeps the marker.
     */
    @Test fun shieldCompletesOnlyOnTheConsumedLockAndItsSpentInputs() = runTest {
        // Inputs spent by the lock, the lock still pending: not complete.
        val f = Fixture(this)
        directShieldInFlight(f)
        f.coinJoinLocks = 1
        f.settleShieldLock()
        advanceTimeBy(120_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertEquals(0, f.service.verifiedLandings)
        // Consumed: complete.
        f.coinJoinLocks = 0
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(1, f.service.verifiedLandings)

        // No CoinJoin lock pending, the recorded inputs not (yet) spent by a shield lock: not complete.
        val g = Fixture(this)
        directShieldInFlight(g)
        advanceTimeBy(120_000); runCurrent()
        assertNotNull(g.service.inFlightMigration())
        g.settleShieldLock()
        advanceTimeBy(15_000); runCurrent()
        assertNull(g.service.inFlightMigration())
        // Each built exactly its one shield; nothing was rebuilt while waiting.
        for (x in listOf(f, g)) {
            coVerify(exactly = 1) { x.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        }
    }

    /**
     * Review 4326681403: during a restore or rescan the note store fills in with pre-existing
     * notes after the runtime is up. That rise, with the migration's lock not yet consumed,
     * never completes the migration.
     */
    @Test fun preExistingNotesSyncingInWhileTheLockIsPendingDoNotComplete() = runTest {
        val f = Fixture(this)
        directShieldInFlight(f)
        f.coinJoinLocks = 1
        f.settleShieldLock()
        for (balance in listOf(Dash.COIN, Dash.valueOf(5_00000000L), Dash.valueOf(9_00000000L))) {
            f.shieldedBalance.value = balance
            advanceTimeBy(30_000); runCurrent()
            assertNotNull(f.service.inFlightMigration())
            assertEquals(0, f.service.verifiedLandings)
        }
        f.coinJoinLocks = 0
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(1, f.service.verifiedLandings)
    }

    /** The consumed lock completes the migration with the shielded balance flat (zero or not). */
    @Test fun consumedLockCompletesWithAFlatShieldedBalance() = runTest {
        for (flat in listOf(Dash.ZERO, Dash.COIN)) {
            val f = Fixture(this)
            f.shieldedBalance.value = flat
            directShieldInFlight(f)
            f.settleShieldLock()
            advanceTimeBy(15_000); runCurrent()
            assertNull("$flat", f.service.inFlightMigration())
            assertEquals("$flat", 1, f.service.verifiedLandings)
            assertEquals("$flat", flat, f.shieldedBalance.value)
        }
    }

    /**
     * Markers already persisted by older builds and by the previous round: any baseline value
     * parses, is ignored, and the marker completes on the proof alone.
     */
    @Test fun legacyBaselineMarkersParseAndCompleteByTheProofAlone() = runTest {
        val legacy = listOf("0", "5", "${Dash.COIN.duffs}", "-1", "-2", "${Long.MAX_VALUE}", "${Long.MIN_VALUE}")
        for (baseline in legacy) {
            val f = Fixture(this)
            f.shieldInputs = encodeOutpoints(f.coins.value.take(3).map { it.outpoint })
            f.marker = "SHIELD|${System.currentTimeMillis()}|$baseline"
            f.shieldedBalance.value = Dash.COIN
            assertNotNull(baseline, parseInFlightMigration(f.marker))
            assertTrue(baseline, f.service.restorePendingShieldMigration())
            advanceTimeBy(60_000); runCurrent()
            assertNotNull(baseline, f.service.inFlightMigration())
            f.settleShieldLock()
            advanceTimeBy(15_000); runCurrent()
            assertNull(baseline, f.service.inFlightMigration())
            assertEquals(baseline, true, f.done)
            assertEquals(baseline, 1, f.service.verifiedLandings)
            f.assertNothingBuilt()
        }
    }

    /** The SDK store wiped (txos and asset_locks deleted, as a wallet recreation does). */
    private fun Fixture.wipeSdkStore() {
        txos.clear()
        inputRows = emptyList()
        shieldLocks.clear()
    }

    /**
     * The tables wiped between the proof's reads, right after the lock count or right after the
     * spender's funding type: the emptied store reads as "no lock pending", but the settling
     * lock's own row is gone, so the migration does NOT complete.
     */
    @Test fun storeWipedBetweenTheProofReadsDoesNotComplete() = runTest {
        for (point in listOf("after the count", "after the spender")) {
            val f = Fixture(this)
            directShieldInFlight(f)
            f.settleShieldLock()
            var wiped = false
            if (point == "after the count") {
                coEvery { f.shield.pendingCoinJoinShieldLockCount() } answers {
                    if (!wiped) {
                        wiped = true
                        f.wipeSdkStore()
                    }
                    0
                }
            } else {
                val lock = f.shieldLocks.single()
                coEvery { f.db.assetLockDao().fundingTypeForTxid(any()) } answers {
                    val type = if (firstArg<String>() == lock) ASSET_LOCK_FUNDING_TYPE_SHIELDED_TOPUP else null
                    if (!wiped) {
                        wiped = true
                        f.wipeSdkStore()
                    }
                    type
                }
            }
            advanceTimeBy(60_000); runCurrent()
            assertTrue(point, wiped)
            assertNotNull(point, f.service.inFlightMigration())
            assertEquals(point, 0, f.service.verifiedLandings)
        }
    }

    /**
     * The settling lock's own row decides, and only the SDK's Consumed (4) settles it: a
     * nonterminal row (2, 3, 5) or an unknown status keeps the migration waiting even when
     * the count says nothing is pending AND Platform's (unauthenticated) already-consumed
     * record lists the lock. It expires into "could not confirm" with its marker — the
     * recovery path — kept; no verified landing.
     */
    @Test fun platformConsumedRecordNeverSettlesTheSettlingLock() = runTest {
        for (status in listOf(2, 3, ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN, 42)) {
            val f = Fixture(this)
            directShieldInFlight(f)
            f.settleShieldLock()
            val lock = f.shieldLocks.single()
            f.shieldLockStatus[lock] = status
            f.platformConsumed = setOf("$lock:0")
            advanceTimeBy(MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS + 60_000); runCurrent()
            // The watcher stood down without clearing; on the wall clock the window has lapsed too.
            val kept = requireNotNull(f.service.inFlightMigration())
            f.marker = formatInFlightMigration(
                kept.copy(startedAtMillis = System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 60_000L)
            )
            assertTrue("$status", f.expired())
            assertFalse("$status", requireNotNull(f.service.inFlightMigration()).failed)
            assertEquals("$status", MixedFundsMigrationAction.SHIELD, f.service.inFlightMigration()?.action)
            // Its recovery path stays: Tools still offers the recorded migration.
            assertTrue("$status", f.service.shouldOfferInTools())
            assertNotEquals("$status: inputs kept", "", f.shieldInputs)
            assertEquals("$status", 0, f.service.verifiedLandings)
        }
    }

    // ── "Close this migration": the user's dismissal of a stuck, unprovable shield ──

    /**
     * A direct SHIELD whose OWN settling lock sits at [status] (Platform's already-consumed record
     * on it when [reported]), past its honesty window: the "could not confirm" guidance. Returns the
     * lock's display txid.
     */
    private suspend fun TestScope.stuckOwnLock(
        f: Fixture,
        status: Int = ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN,
        reported: Boolean = true
    ): String {
        directShieldInFlight(f)
        f.settleShieldLock()
        val lock = f.shieldLocks.single()
        f.shieldLockStatus[lock] = status
        if (reported) f.platformConsumed = setOf("$lock:0")
        advanceTimeBy(MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS + 60_000); runCurrent()
        val kept = requireNotNull(f.service.inFlightMigration())
        f.marker = formatInFlightMigration(
            kept.copy(startedAtMillis = System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 60_000L)
        )
        assertTrue(f.expired())
        return lock
    }

    /** Nothing was closed: the record, its inputs and the absence of any landing are intact. */
    private suspend fun Fixture.assertNotClosable(why: String) {
        assertFalse(why, service.canCloseUnconfirmedMigration())
        assertFalse(why, service.closeUnconfirmedMigration())
        assertNotNull(why, service.inFlightMigration())
        assertEquals(why, 0, service.verifiedLandings)
    }

    /**
     * The stuck state: the shield's OWN lock is final on L1 (2, 3 or 5) and Platform answered it
     * already-consumed, so it is never retried and never reaches Consumed. Close is offered, and
     * closing drops the record — marker, inputs, journal — WITHOUT a landing or the DONE flag.
     */
    @Test fun closeIsOfferedForAStuckOwnLockAndRecordsNoSuccess() = runTest {
        for (status in listOf(2, 3, ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN)) {
            val f = Fixture(this)
            stuckOwnLock(f, status)
            f.done = null
            assertTrue("$status", f.service.canCloseUnconfirmedMigration())
            assertTrue("$status", f.service.closeUnconfirmedMigration())
            assertNull("$status", f.service.inFlightMigration())
            assertEquals("$status", "", f.shieldInputs)
            assertEquals("$status", "", f.consolidation)
            assertEquals("$status", "", f.directPhase)
            assertEquals("$status: a dismissal, not a landing", 0, f.service.verifiedLandings)
            assertNull("$status: never DONE", f.done)
            // Nothing left to close.
            assertFalse("$status", f.service.canCloseUnconfirmedMigration())
        }
    }

    /**
     * Try again on a closable record re-asks for its Platform-consumed own lock (explicit retry). The
     * lock LANDS (Consumed, record cleared): the migration completes with a verified landing and is
     * no longer closable. Nothing new is built.
     */
    @Test fun tryAgainOnAClosableRecordWhoseLockLandsCompletesIt() = runTest {
        val f = Fixture(this)
        val lock = stuckOwnLock(f)
        assertTrue(f.service.canCloseUnconfirmedMigration())
        coEvery { f.shield.resumePendingWalletShieldsOnUserRequest() } answers {
            f.shieldLockStatus[lock] = ASSET_LOCK_STATUS_CONSUMED
            f.platformConsumed = emptySet()
            1
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.retryPendingMigration())
        assertFalse(f.service.canCloseUnconfirmedMigration())
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(1, f.service.verifiedLandings)
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
    }

    /** A final own lock Platform has NOT answered is still resumed and may reach Consumed: not closable. */
    @Test fun closeIsNotOfferedBeforePlatformAnsweredTheOwnLock() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f, ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN, reported = false)
        f.assertNotClosable("unreported")
    }

    /** A not-yet-final (0, 1) or unknown own lock may still own live inputs: not closable, reported or not. */
    @Test fun closeIsNotOfferedForANonFinalOrUnknownOwnLock() = runTest {
        for (status in listOf(0, 1, 42)) {
            val f = Fixture(this)
            stuckOwnLock(f, status)
            f.assertNotClosable("status $status")
        }
    }

    /** Still inside its honesty window (processing, not "could not confirm"): not closable. */
    @Test fun closeIsNotOfferedWhileTheWindowIsOpen() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f)
        f.marker = formatInFlightMigration(
            requireNotNull(f.service.inFlightMigration()).copy(startedAtMillis = System.currentTimeMillis())
        )
        f.assertNotClosable("window open")
    }

    /** A failed marker has its own release path: not closable. */
    @Test fun closeIsNotOfferedForAFailedMarker() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f)
        f.marker = formatInFlightMigration(requireNotNull(f.service.inFlightMigration()).copy(failed = true))
        f.assertNotClosable("failed")
    }

    /** An adopted record (its own lock unknown) and a legacy no-inputs record: not closable. */
    @Test fun closeIsNotOfferedWithoutAKnownOwnLock() = runTest {
        for (inputs in listOf(CoinJoinFundsMigrationService.ADOPTED_SHIELD_INPUTS, "")) {
            val f = Fixture(this)
            stuckOwnLock(f)
            f.shieldInputs = inputs
            f.assertNotClosable("inputs '$inputs'")
        }
    }

    /** The own lock's `asset_locks` rows gone (e.g. a wiped table): nothing shows it stuck, so not closable. */
    @Test fun closeIsNotOfferedWhenTheOwnLockRowsAreMissing() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f)
        every {
            f.db.assetLockDao().observeByWalletAndFundingType(any(), ASSET_LOCK_FUNDING_TYPE_SHIELDED_TOPUP)
        } returns flowOf(emptyList())
        f.assertNotClosable("no rows")
    }

    /** An invalid consolidation stage, or a COMBINE marker: not closable. */
    @Test fun closeIsNotOfferedForAnInvalidStageOrACombine() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f)
        f.consolidation = "not a stage"
        f.assertNotClosable("invalid stage")
        val g = Fixture(this)
        stuckOwnLock(g)
        g.marker = formatInFlightMigration(
            requireNotNull(g.service.inFlightMigration()).copy(action = MixedFundsMigrationAction.COMBINE)
        )
        g.assertNotClosable("combine")
    }

    /** Latched for Reset Wallet: neither offered nor closed; the record is left to the wipe. */
    @Test fun closeIsRefusedDuringAWalletWipe() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f)
        f.service.stopForWalletWipe()
        f.assertNotClosable("wiping")
        f.service.resumeAfterWalletWipe()
        assertTrue(f.service.canCloseUnconfirmedMigration())
    }

    /**
     * After the close the stuck lock (still tracked at 5, Platform-consumed) blocks no fresh drain,
     * and it is no longer any record's OWN lock: the next SHIELD completes on its own Consumed lock.
     */
    @Test fun closeUnblocksAFreshMigrationWhichCompletesOnItsOwnLock() = runTest {
        val f = Fixture(this)
        val stuck = stuckOwnLock(f)
        val locks = f.trackLocks(
            PendingWalletShieldLock(
                "$stuck:0", ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN, 1L, coinJoinFunded = true, platformConsumed = true
            )
        )
        // Before the close, the record blocks a fresh spend.
        f.newCoinJoinCoinsArrive()
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        assertTrue(f.service.closeUnconfirmedMigration())
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 2) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        runCurrent()
        f.settleShieldLock(Sha256Hash.of("second-lock".toByteArray()))
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(1, f.service.verifiedLandings)
        assertEquals(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN, locks.single().statusRaw)
    }

    /**
     * The closed record's lock stays tracked at 5 (Platform-consumed) for good. A LATER adopted lock
     * B that reaches Consumed still returns to the choice: the closed lock does not wedge it.
     */
    @Test fun afterACloseALaterAdoptedLockStillReleases() = runTest {
        val f = Fixture(this)
        val stuck = stuckOwnLock(f)
        val locks = f.trackLocks(
            PendingWalletShieldLock(
                "$stuck:0", ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN, 1L, coinJoinFunded = true, platformConsumed = true
            )
        )
        assertTrue(f.service.closeUnconfirmedMigration())
        // A live CoinJoin lock B with no record appears; the next move adopts it.
        val lockB = PendingWalletShieldLock("0b".repeat(32) + ":0", 2, 1L, coinJoinFunded = true)
        locks += lockB
        f.newCoinJoinCoinsArrive()
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(CoinJoinFundsMigrationService.ADOPTED_SHIELD_INPUTS, f.shieldInputs)
        f.done = null
        locks[1] = lockB.copy(statusRaw = ASSET_LOCK_STATUS_CONSUMED)
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
        assertEquals("", f.marker)
        assertNull(f.done)
        assertEquals(0, f.service.verifiedLandings)
        // Only the closed migration's own shield was ever built.
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
    }

    // ── "Close this migration" for a legacy no-inputs record ──

    /** A live CoinJoin lock B Platform has not answered. */
    private fun lockB() = PendingWalletShieldLock("0b".repeat(32) + ":0", 2, 1L, coinJoinFunded = true)

    /**
     * A legacy SHIELD record with no recorded inputs, past its honesty window, over [locks]; the
     * CoinJoin account affirmatively empty on a synced chain.
     */
    private fun Fixture.stuckLegacyRecord(vararg locks: PendingWalletShieldLock): MutableList<PendingWalletShieldLock> {
        val tracked = trackLocks(*locks)
        coins.value = emptyList()
        engineBalance = 0
        marker = "SHIELD|${System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 60_000L}|0"
        return tracked
    }

    /**
     * Held only by Platform-consumed (retry-suppressed) CoinJoin locks, the legacy proof can never
     * pass: Close is offered, and drops the record with no landing and no DONE.
     */
    @Test fun legacyRecordHeldOnlyByPlatformConsumedLocksIsClosable() = runTest {
        val cases = listOf(2, 3, ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).map {
            listOf(lockA(it).copy(platformConsumed = true))
        } + listOf(listOf(lockA(2).copy(platformConsumed = true), lockB().copy(statusRaw = 3, platformConsumed = true)))
        for (locks in cases) {
            val f = Fixture(this)
            f.stuckLegacyRecord(*locks.toTypedArray())
            val label = locks.joinToString { "${it.statusRaw}" }
            assertTrue(label, f.service.canCloseUnconfirmedMigration())
            assertTrue(label, f.service.closeUnconfirmedMigration())
            assertEquals(label, "", f.marker)
            assertNull(label, f.done)
            assertEquals(label, 0, f.service.verifiedLandings)
            assertFalse(label, f.service.canCloseUnconfirmedMigration())
        }
    }

    /** One counted lock Platform has not answered (still resumed, may reach Consumed): not closable. */
    @Test fun legacyRecordWithAnUnansweredLockIsNotClosable() = runTest {
        val f = Fixture(this)
        f.stuckLegacyRecord(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true), lockB())
        f.assertNotClosable("unanswered B")
    }

    /** No CoinJoin lock pending at all: the legacy proof itself completes it (Try again), so not closable. */
    @Test fun legacyRecordWithNoPendingLockIsNotClosable() = runTest {
        val f = Fixture(this)
        f.stuckLegacyRecord()
        f.assertNotClosable("no locks")
    }

    /** The CoinJoin account not empty, or unreadable: not closable. */
    @Test fun legacyRecordOverANonEmptyOrUnreadableAccountIsNotClosable() = runTest {
        val f = Fixture(this)
        f.stuckLegacyRecord(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true))
        f.engineBalance = 1_000L
        f.assertNotClosable("not empty")
        val g = Fixture(this)
        g.stuckLegacyRecord(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true))
        coEvery { g.manager.accountBalances(any()) } throws IllegalStateException("unreadable")
        g.assertNotClosable("unreadable")
    }

    /** The chain not synced: not closable. */
    @Test fun legacyRecordOnAnUnsyncedChainIsNotClosable() = runTest {
        val f = Fixture(this)
        f.stuckLegacyRecord(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true))
        every { f.chainState.isSynced() } returns false
        f.assertNotClosable("unsynced")
    }

    /** Window still open, a failed marker, or any stage: not closable. */
    @Test fun legacyRecordOpenFailedOrStagedIsNotClosable() = runTest {
        val stuck = lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true)
        val f = Fixture(this)
        f.stuckLegacyRecord(stuck)
        f.marker = "SHIELD|${System.currentTimeMillis()}|0"
        f.assertNotClosable("window open")
        val g = Fixture(this)
        g.stuckLegacyRecord(stuck)
        g.marker += "|failed"
        g.assertNotClosable("failed")
        val h = Fixture(this)
        h.stuckLegacyRecord(stuck)
        h.consolidation = rawStage("shield_preparing")
        h.assertNotClosable("staged")
    }

    /** The legacy record blocks every move until closed; after the close a fresh migration spends. */
    @Test fun closingALegacyRecordUnblocksAFreshMigration() = runTest {
        val f = Fixture(this)
        f.stuckLegacyRecord(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true))
        f.newCoinJoinCoinsArrive()
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        f.assertNothingBuilt()
        f.coins.value = emptyList()
        f.engineBalance = 0
        assertTrue(f.service.closeUnconfirmedMigration())
        f.newCoinJoinCoinsArrive()
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
        assertEquals(0, f.service.verifiedLandings)
    }

    // ── Explicit user entries resume tracked shield locks FIRST ──

    /** What the record looked like (marker, stage) at each explicit resume. */
    private fun Fixture.recordAtEachResume(): MutableList<Pair<String, String>> {
        val seen = mutableListOf<Pair<String, String>>()
        coEvery { shield.resumePendingWalletShieldsOnUserRequest() } answers {
            seen += marker to consolidation
            0
        }
        return seen
    }

    /**
     * Try again on an adopted record, an invalid stage and a failed record: the explicit resume runs
     * while the record is still there — before the release that drops it — and nothing is built.
     */
    @Test fun tryAgainResumesTrackedShieldsBeforeAnyRelease() = runTest {
        // Adopted (live 2), Platform reported it consumed afterwards.
        val f = Fixture(this)
        val locks = f.trackLocks(lockA(2))
        f.newCoinJoinCoinsArrive()
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        val adopted = f.marker
        locks[0] = locks[0].copy(platformConsumed = true)
        val seen = f.recordAtEachResume()
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
        assertEquals(listOf(adopted to ""), seen)
        assertEquals("", f.marker)
        f.assertNoSecondDrain()

        // An invalid stage past a Platform-consumed lock.
        val k = Fixture(this)
        k.trackLocks(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true))
        k.consolidation = rawStage("shielding", regularDestination = regular)
        assertTrue(k.service.restorePendingShieldMigration())
        runCurrent()
        val stage = k.consolidation
        val stageMarker = k.marker
        val kSeen = k.recordAtEachResume()
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, k.service.retryPendingMigration())
        assertEquals(listOf(stageMarker to stage), kSeen)
        assertEquals("", k.consolidation)
        k.assertNothingBuilt()

        // A failed record: Shield resumes first, then releases it and spends afresh.
        val g = Fixture(this)
        g.trackLocks(lockA(3).copy(platformConsumed = true))
        val failed = shieldMarker(failed = true)
        g.marker = failed
        val gSeen = g.recordAtEachResume()
        g.newCoinJoinCoinsArrive()
        assertEquals(MixedFundsMigrationOutcome.STARTED, g.service.shieldMixedFunds(Dash.COIN))
        assertEquals(listOf(failed to ""), gSeen)
        coVerifyOrder {
            g.shield.resumePendingWalletShieldsOnUserRequest()
            g.shield.shieldMixedFundsFromWallet(any(), any(), any(), any())
        }
    }

    /**
     * No record and an EMPTY CoinJoin account, a Platform-consumed lock still tracked: Tools still
     * offers the sheet, whose retry resumes it (nothing built, nothing recorded). The same after a
     * Close, and for a restored status-5 stranger on a Shield tap (resumed before the drain).
     */
    @Test fun aSuppressedLockStaysReachableByAnExplicitRetry() = runTest {
        val f = Fixture(this)
        f.trackLocks(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true))
        f.coins.value = emptyList()
        f.engineBalance = 0
        val seen = f.recordAtEachResume()
        assertNull(f.service.mixedFundsToMigrate())
        assertTrue(f.service.hasRetrySuppressedShields())
        assertTrue(f.service.shouldOfferInTools())
        f.service.retryPendingShieldsOnUserRequest()
        assertEquals(listOf("" to ""), seen)
        f.assertNothingBuilt()
        assertEquals("", f.marker)
        assertNull(f.done)
        // Latched for a wipe: neither offered nor run.
        f.service.stopForWalletWipe()
        assertFalse(f.service.hasRetrySuppressedShields())
        assertEquals(0, f.service.retryPendingShieldsOnUserRequest())
        assertEquals(1, seen.size)

        // After a Close.
        val c = Fixture(this)
        val stuck = stuckOwnLock(c)
        c.trackLocks(
            PendingWalletShieldLock(
                "$stuck:0", ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN, 1L, coinJoinFunded = true, platformConsumed = true
            )
        )
        assertTrue(c.service.closeUnconfirmedMigration())
        c.coins.value = emptyList()
        c.engineBalance = 0
        val cSeen = c.recordAtEachResume()
        assertTrue(c.service.shouldOfferInTools())
        c.service.retryPendingShieldsOnUserRequest()
        assertEquals(listOf("" to ""), cSeen)
        coVerify(exactly = 1) { c.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }

        // A restored status-5 stranger: the Shield tap resumes it before draining the new coins.
        val r = Fixture(this)
        r.restoredWithAPlatformConsumedStranger()
        val rSeen = r.recordAtEachResume()
        assertEquals(MixedFundsMigrationOutcome.STARTED, r.service.shieldMixedFunds(Dash.COIN))
        assertEquals(listOf("" to ""), rSeen)
        coVerifyOrder {
            r.shield.resumePendingWalletShieldsOnUserRequest()
            r.shield.shieldMixedFundsFromWallet(any(), any(), any(), any())
        }
    }

    /** Combine resumes the tracked locks first too. */
    @Test fun combineResumesTrackedShieldsFirst() = runTest {
        val f = Fixture(this)
        f.restoredWithAPlatformConsumedStranger()
        val seen = f.recordAtEachResume()
        combineInFlight(f)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        assertEquals(listOf("" to ""), seen)
    }

    // ── The record is cleared in ONE transaction ──

    /**
     * A failure mid-clear (the commit, or any one key's write) leaves the record wholly intact:
     * never a marker without its inputs, stage or journal. A successful clear removes them all.
     */
    @Test fun recordClearIsAllOrNothing() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f)
        f.consolidation = ""
        val before = listOf(f.marker, f.shieldInputs, f.consolidation, f.directPhase)
        assertNotEquals("", f.marker)
        assertNotEquals("", f.shieldInputs)
        assertNotEquals("", f.directPhase)
        f.editFailure = java.io.IOException("disk full")
        coEvery { f.config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, "") } throws
            java.io.IOException("disk full")
        f.service.closeUnconfirmedMigration()
        assertEquals(before, listOf(f.marker, f.shieldInputs, f.consolidation, f.directPhase))
        assertEquals(0, f.edits)

        val g = Fixture(this)
        stuckOwnLock(g)
        g.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        g.marker = formatInFlightMigration(
            requireNotNull(g.service.inFlightMigration()).copy(
                startedAtMillis = System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 60_000L
            )
        )
        g.service.clearInFlightMigration()
        assertEquals(listOf("", "", "", ""), listOf(g.marker, g.shieldInputs, g.consolidation, g.directPhase))
        assertEquals(1, g.edits)
    }

    /**
     * A clear by a step whose wipe epoch ended (a Close suspended in its check while Reset Wallet
     * runs) writes nothing.
     */
    @Test fun aClearAfterTheWipeEpochEndedWritesNothing() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f)
        val rows = f.db.assetLockDao()
            .observeByWalletAndFundingType(Utils.HEX.decode(walletId), ASSET_LOCK_FUNDING_TYPE_SHIELDED_TOPUP)
            .first()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        every {
            f.db.assetLockDao().observeByWalletAndFundingType(any(), ASSET_LOCK_FUNDING_TYPE_SHIELDED_TOPUP)
        } returns flow {
            gate.await()
            emit(rows)
        }
        val saved = f.marker
        val close = async { f.service.closeUnconfirmedMigration() }
        runCurrent()
        val wipe = async { f.service.stopForWalletWipe() }
        advanceTimeBy(CoinJoinFundsMigrationService.WIPE_JOIN_MS + 1); runCurrent()
        assertTrue(wipe.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertFalse(close.await())
        assertEquals(saved, f.marker)
        assertNotEquals("", f.shieldInputs)
        assertEquals(0, f.edits)
    }

    // ── Conditional writes: never over (or resurrecting) a record that changed ──

    /** The watcher's verified landing, committed: the whole record cleared, DONE set. */
    private fun Fixture.landNow() {
        marker = ""
        shieldInputs = ""
        consolidation = ""
        directPhase = ""
        done = true
    }

    /**
     * The settling lock reaches Consumed and the watcher clears the record BETWEEN Try again's
     * marker read and its renewal: the renewal writes nothing, nothing is resurrected.
     */
    @Test fun retryRenewalNeverResurrectsARecordCompletedAfterItsRead() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f)
        var armed = true
        // Try again reads the marker, then the stage: the landing commits in between.
        coEvery { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } answers {
            if (armed) {
                armed = false
                f.landNow()
            }
            f.consolidation
        }
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
        assertFalse(armed)
        assertEquals("", f.marker)
        assertEquals("", f.shieldInputs)
        assertEquals("", f.directPhase)
    }

    /** Without a race the renewal still gives the same record a fresh window, inputs kept. */
    @Test fun retryRenewalRenewsTheSameRecord() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f)
        val inputs = f.shieldInputs
        assertTrue(f.expired())
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
        assertFalse(f.expired())
        assertEquals(inputs, f.shieldInputs)
        assertEquals(MixedFundsMigrationAction.SHIELD, f.service.inFlightMigration()?.action)
    }

    /** Reset Wallet begins between Try again's read and its renewal: nothing is written. */
    @Test fun aWipeDuringTheRetryRenewalWritesNothing() = runTest {
        val f = Fixture(this)
        stuckOwnLock(f)
        val saved = f.marker
        var armed = true
        coEvery { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } coAnswers {
            if (armed) {
                armed = false
                f.service.stopForWalletWipe()
            }
            f.consolidation
        }
        val outcome = runCatching { f.service.retryPendingMigration() }
        assertFalse(armed)
        assertTrue(outcome.exceptionOrNull() is StoppedForWalletWipeException)
        assertEquals(saved, f.marker)
        assertEquals(0, f.edits)
    }

    /**
     * The watcher proves the landing of the record it read, but the record was replaced meanwhile:
     * it writes neither DONE nor the clear, and counts no landing.
     */
    @Test fun aStaleWatcherNeverCompletesAReplacedRecord() = runTest {
        val f = Fixture(this)
        directShieldInFlight(f)
        f.done = null
        val replaced = formatInFlightMigration(
            InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis() + 5)
        )
        f.marker = replaced
        f.settleShieldLock()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(replaced, f.marker)
        assertNull(f.done)
        assertEquals(0, f.service.verifiedLandings)
    }

    /**
     * The record changes between the landing's DONE commit and its clear: nothing is cleared and the
     * landing is not counted (the sheet never shows a success for a record that was not completed).
     */
    @Test fun aLandingWhoseClearFindsTheRecordChangedIsNotCounted() = runTest {
        val f = Fixture(this)
        directShieldInFlight(f)
        f.done = null
        val replaced = formatInFlightMigration(
            InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis() + 5)
        )
        var armed = true
        f.afterEdit = {
            if (armed && f.done == true) {
                armed = false
                f.marker = replaced
            }
        }
        f.settleShieldLock()
        advanceTimeBy(30_000); runCurrent()
        assertFalse(armed)
        assertEquals(replaced, f.marker)
        assertNotEquals("", f.shieldInputs)
        assertEquals(0, f.service.verifiedLandings)
    }

    /**
     * A watcher that stops on an error expires the marker it then reads — never one replaced
     * between that read and the commit.
     */
    @Test fun aStoppedWatcherNeverExpiresARecordReplacedAfterItsRead() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val locked = SdkWriteResult.NotBroadcast(
            REASON_PRE_BROADCAST_ASSET_LOCK_SIGNING,
            Exception("Signing failed: mnemonic resolver failed with code 3")
        )
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } returns locked
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        val replaced = formatInFlightMigration(
            InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis() + 5)
        )
        var edit = 0
        f.beforeEdit = {
            when (++edit) {
                // The signer back-off's renewal fails: the watcher stops and expires the marker.
                1 -> throw java.io.IOException("disk full")
                // Another writer replaces the record before that expiry commits.
                2 -> f.marker = replaced
            }
        }
        advanceTimeBy(31_000); runCurrent()
        assertEquals(2, edit)
        assertEquals(replaced, f.marker)
    }

    /** A definitive failure the watcher derived for the record it read is never written over a replacement. */
    @Test fun aStaleWatcherNeverFailsAReplacedRecord() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val replaced = formatInFlightMigration(
            InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis() + 5)
        )
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } coAnswers {
            f.marker = replaced
            SdkWriteResult.NotBroadcast(REASON_PRE_BROADCAST_ASSET_LOCK_SELECTION, Exception("coin selection is short"))
        }
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        assertEquals(replaced, f.marker)
        assertEquals(false, f.service.inFlightMigration()?.failed)
    }

    /** Restore recreates a marker from a stage only while the stage is still there with no marker. */
    @Test fun restoreNeverResurrectsAMarkerForACompletedStage() = runTest {
        val f = Fixture(this)
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        var armed = true
        coEvery { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } answers {
            val read = f.consolidation
            if (armed) {
                armed = false
                f.landNow()
            }
            read
        }
        assertTrue("re-evaluated later", f.service.restorePendingShieldMigration())
        assertEquals("", f.marker)
        assertEquals("", f.consolidation)
        assertFalse(f.service.restorePendingShieldMigration())
    }

    /** A malformed marker is repaired only over the same malformed marker it read. */
    @Test fun repairNeverWritesOverAMarkerThatChanged() = runTest {
        val f = Fixture(this)
        f.marker = "SHIELD|not-a-number|0"
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        val replaced = shieldMarker()
        var reads = 0
        coEvery { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } answers {
            // The repair's own stage read (after its marker read): a valid marker lands meanwhile.
            if (++reads == 1) f.marker = replaced
            f.consolidation
        }
        f.service.restorePendingShieldMigration()
        assertEquals(replaced, f.marker)
    }

    /** Adoption writes only while there is still no record: never over one recorded meanwhile. */
    @Test fun adoptionNeverWritesOverARecordThatAppeared() = runTest {
        val f = Fixture(this)
        f.trackLocks(lockA(2))
        val appeared = CoinJoinConsolidationState(walletId, destination).encode()
        coEvery { f.shield.recoveredCoinJoinShieldLockCount() } answers {
            f.consolidation = appeared
            0
        }
        f.newCoinJoinCoinsArrive()
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
        assertNotEquals(CoinJoinFundsMigrationService.ADOPTED_SHIELD_INPUTS, f.shieldInputs)
        assertEquals("", f.marker)
        assertEquals(appeared, f.consolidation)
    }

    // ── Marker read errors: never "absent" ──

    /** A second service over the same store, as after a restart; the first one is stopped. */
    private suspend fun TestScope.restarted(f: Fixture): CoinJoinFundsMigrationService {
        f.service.stopForWalletWipe()
        return CoinJoinFundsMigrationService(
            f.wallet, f.shield, f.send, f.blockchain, f.config, f.sdk, backgroundScope, f.instantSendLocks
        )
    }

    /** Transient marker read errors do not end the watcher: it retries, then completes on the proof. */
    @Test fun watcherSurvivesTransientMarkerReadErrors() = runTest {
        val f = Fixture(this)
        directShieldInFlight(f)
        val g = restarted(f)
        var failures = 3
        coEvery { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers {
            if (failures > 0) {
                failures--
                throw java.io.IOException("unreadable")
            }
            f.marker
        }
        g.startInFlightWatcherIfNeeded()
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, failures)
        assertNotEquals("", f.marker)
        f.settleShieldLock()
        advanceTimeBy(30_000); runCurrent()
        assertEquals("", f.marker)
        assertEquals(1, g.verifiedLandings)
    }

    /**
     * Persistent read errors never clear, replace or complete the marker; the watcher stands down
     * after its cap (no endless polling), and a later restore, once readable, carries on.
     */
    @Test fun persistentMarkerReadErrorsKeepTheMarker() = runTest {
        val f = Fixture(this)
        directShieldInFlight(f)
        val g = restarted(f)
        val saved = f.marker
        var reads = 0
        var unreadable = true
        coEvery { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers {
            reads++
            if (unreadable) throw java.io.IOException("unreadable")
            f.marker
        }
        var writes = 0
        coEvery { f.config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) } answers {
            writes++
            f.marker = secondArg()
        }
        f.settleShieldLock()
        g.startInFlightWatcherIfNeeded()
        advanceTimeBy(MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS + 120_000); runCurrent()
        assertEquals(saved, f.marker)
        assertEquals(0, writes)
        assertEquals(0, g.verifiedLandings)
        assertTrue(reads > 3)
        val atCap = reads
        advanceTimeBy(30L * 60_000); runCurrent()
        assertEquals("stood down", atCap, reads)
        unreadable = false
        assertTrue(g.restorePendingShieldMigration())
        advanceTimeBy(30_000); runCurrent()
        assertEquals("", f.marker)
        assertEquals(1, g.verifiedLandings)
    }

    /** The signer-locked retry's marker re-read survives a read error and keeps retrying. */
    @Test fun signerLockedRetrySurvivesAMarkerReadError() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val locked = SdkWriteResult.NotBroadcast(
            REASON_PRE_BROADCAST_ASSET_LOCK_SIGNING,
            Exception("Signing failed: mnemonic resolver failed with code 3")
        )
        var deviceLocked = true
        var attempts = 0
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } coAnswers {
            attempts++
            if (deviceLocked) locked else SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
        }
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertEquals(1, attempts)
        // The watcher waits out the signer back-off; its marker re-read then fails three times.
        var failures = 3
        coEvery { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers {
            if (failures > 0) {
                failures--
                throw java.io.IOException("unreadable")
            }
            f.marker
        }
        deviceLocked = false
        advanceTimeBy(45_000); runCurrent()
        assertEquals(0, failures)
        assertEquals(2, attempts)
        assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
    }

    /** A read error in the marker observation emits nothing (never "absent") and observation resumes. */
    @Test fun markerObservationSurvivesAReadError() = runTest {
        val f = Fixture(this)
        val first = shieldMarker()
        val second = shieldMarker(failed = true)
        // A cold store flow, as DataStore's: each collection reads again.
        var subscriptions = 0
        every { f.config.observePreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } returns flow {
            subscriptions++
            if (subscriptions == 1) {
                emit(first)
                throw java.io.IOException("unreadable")
            }
            emit(second)
        }
        val seen = mutableListOf<InFlightMixedFundsMigration?>()
        val job = launch { f.service.observeInFlightMigration().collect { seen += it } }
        advanceTimeBy(5_000); runCurrent()
        job.cancel()
        assertEquals(listOf(parseInFlightMigration(first), parseInFlightMigration(second)), seen)
        assertEquals(2, subscriptions)
    }

    /** An unreadable marker never lets a fresh move start: every entry fails closed, nothing is built. */
    @Test fun anUnreadableMarkerFailsEveryFreshMoveClosed() = runTest {
        val f = Fixture(this)
        coEvery { f.config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } throws
            java.io.IOException("unreadable")
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.retryPendingMigration())
        runCurrent()
        f.assertNoSecondDrain()
        coVerify(exactly = 0) { f.config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) }
    }

    /** The SDK's Consumed (4) completes it, with or without a Platform record. */
    @Test fun settlingLockConsumedCompletes() = runTest {
        for (reported in listOf(false, true)) {
            val f = Fixture(this)
            directShieldInFlight(f)
            f.settleShieldLock()
            val lock = f.shieldLocks.single()
            f.shieldLockStatus[lock] = ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN
            if (reported) f.platformConsumed = setOf("$lock:0")
            advanceTimeBy(60_000); runCurrent()
            assertNotNull("$reported", f.service.inFlightMigration())
            f.shieldLockStatus[lock] = ASSET_LOCK_STATUS_CONSUMED
            advanceTimeBy(15_000); runCurrent()
            assertNull("$reported", f.service.inFlightMigration())
            assertEquals("$reported", true, f.done)
            assertEquals("$reported", 1, f.service.verifiedLandings)
        }
    }

    /** An unknown status stays pending: no completion within the window, whatever Platform reported. */
    @Test fun settlingLockUnknownStatusStaysPending() = runTest {
        val f = Fixture(this)
        directShieldInFlight(f)
        f.settleShieldLock()
        val lock = f.shieldLocks.single()
        f.shieldLockStatus[lock] = 42
        advanceTimeBy(60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertFalse(f.expired())
        f.platformConsumed = setOf("$lock:0")
        advanceTimeBy(60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertEquals(0, f.service.verifiedLandings)
    }

    /** No shielded balance is read before a move: an unreadable one refuses or defers nothing. */
    @Test fun shieldStartsWithAnUnreadableShieldedBalance() = runTest {
        for (coinCount in listOf(100, 101)) {
            val f = Fixture(this)
            f.coins.value = (0 until coinCount).map(::coin)
            f.engineBalance = f.coins.value.sumOf { it.amount }
            every { f.shield.observeShieldedBalance() } returns flow { throw IllegalStateException("note store") }
            assertEquals("$coinCount", MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
            assertNotNull("$coinCount", f.service.inFlightMigration())
            if (coinCount == 100) {
                coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), walletId, any()) }
            } else {
                assertNotEquals("$coinCount", "", f.consolidation)
            }
        }
        // A stage recovered without its marker is marked straight away, before the shield call too.
        val g = Fixture(this)
        g.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        every { g.shield.observeShieldedBalance() } returns flow { throw IllegalStateException("note store") }
        assertTrue(g.service.restorePendingShieldMigration())
        assertEquals(MixedFundsMigrationAction.SHIELD, g.service.inFlightMigration()?.action)
    }

    // ── Transient SDK failures keep the watcher polling ──

    /**
     * The SDK starting/stopped, or a store read failing, is transient: the watcher keeps
     * polling inside its window (no early "could not confirm"), and continues once it is back.
     */
    @Test fun transientSdkFailuresKeepTheWatcherPolling() = runTest {
        for (kind in listOf("ensureStarted", "tx store", "snapshot")) {
            val f = Fixture(this)
            val tx = transaction(f.coins.value)
            f.consolidation = waitingState(tx)
            var down = true
            val failure = IllegalStateException("SDK unavailable")
            when (kind) {
                "ensureStarted" -> coEvery { f.sdk.ensureStarted() } answers { if (down) throw failure }
                "tx store" -> coEvery { f.db.transactionDao().getByTxid(any()) } answers {
                    if (down) throw failure else f.storedTx
                }
                else -> every { f.db.txoDao().observeUnspentByWallet(any()) } answers {
                    if (down) throw failure else f.coins
                }
            }
            if (kind == "snapshot") f.store(tx) // final, so the snapshot read is reached
            assertTrue(kind, f.service.restorePendingShieldMigration())
            runCurrent()
            advanceTimeBy(120_000); runCurrent()
            assertFalse(kind, f.expired())
            f.assertNothingBuilt()
            down = false
            f.landBatch(tx)
            advanceTimeBy(30_000); runCurrent()
            coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), walletId, any()) }
            f.service.stopForWalletWipe()
        }
    }

    /** The proof's ownership rule IS the snapshot's: a row counts as a CoinJoin coin for both, or for neither. */
    @Test fun proofOwnershipMatchesTheSnapshotRule() = runTest {
        val f = Fixture(this)
        val rows = listOf(
            "account and address" to coin(0),
            "address only" to coin(1).copy(accountId = null),
            "account only" to coin(2).copy(address = regular),
            "address, BIP44 account" to coin(3).copy(accountId = 9L),
            "BIP44" to bip44(coin(4)),
            "neither" to coin(5).copy(address = regular, accountId = null)
        )
        for ((name, row) in rows) {
            f.coins.value = listOf(row)
            f.engineBalance = row.amount
            val inSnapshot = coinJoinConsolidationSnapshot(f.sdk)?.coins?.isNotEmpty() == true
            val spent = row.copy(isSpent = true, spendingTxid = Sha256Hash.of("s".toByteArray()).reversedBytes)
            val proven = coinJoinSoleSpender(f.db, walletId, listOf(row.outpoint), listOf(spent)) != null
            assertEquals(name, inSnapshot, proven)
            assertEquals(name, name !in setOf("BIP44", "neither"), proven)
        }
    }

    @Test fun replayConflictFailsClosedWithoutRebuilding() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) } returns ConsolidationReplay.CONFLICT
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
        coVerify(exactly = 2) { f.send.rebroadcastCoinJoinConsolidation(any(), any(), tx.txId.toString()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) }
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
        coVerify(exactly = 1) { f.send.rebroadcastCoinJoinConsolidation(any(), any(), tx.txId.toString()) }
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
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) }
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        // A legacy state carries no signed bytes: observation only, as before.
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) }
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
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } coAnswers {
            assertNotNull(parseInFlightMigration(f.marker))
            SdkWriteResult.NotBroadcast("definitive rejection")
        }
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertNull(f.service.inFlightMigration())
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
    }

    @Test fun databaseBalanceMismatchPreventsSpending() = runTest {
        val f = Fixture(this)
        f.engineBalance += 100
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } returns
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
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
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } coAnswers {
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
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } returns
            SdkWriteResult.NotBroadcast(REASON_PRE_BROADCAST_ASSET_LOCK_SELECTION, Exception("coin selection is short"))
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        // Recorded as failed (choice + fallback offered), not as "could not confirm".
        assertEquals(true, f.service.inFlightMigration()?.failed)
        assertEquals(false, f.service.inFlightMigration()?.isExpired())
        advanceTimeBy(5L * 60_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
        assertEquals(false, f.service.inFlightMigration()?.isExpired())
        // The lock row shows up late: it is resumed, never rebuilt.
        f.coinJoinLocks = 1
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.resumePendingWalletShields() }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        assertEquals(false, f.service.inFlightMigration()?.isExpired())
        f.engineBalance = f.coins.value.sumOf { it.amount }
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
        assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
    }

    @Test fun shieldStageRecordsTheSubmissionRightBeforeTheFundingCall() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } coAnswers {
            assertEquals(CoinJoinConsolidationState.SHIELD_PREPARING,
                CoinJoinConsolidationState.decode(f.consolidation).phase)
            arg<suspend () -> Unit>(3)()
            assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
            SdkWriteResult.Ambiguous(Exception("broadcast outcome unknown"))
        }
        f.consolidation = CoinJoinConsolidationState(walletId, destination).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(CoinJoinConsolidationState.SHIELDING, CoinJoinConsolidationState.decode(f.consolidation).phase)
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
    }

    @Test fun directShieldInterruptedBeforeSubmissionIsReconciledButASubmittedOneIsKept() = runTest {
        val f = Fixture(this)
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val stale = formatInFlightMigration(
            InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis())
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
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } coAnswers {
            assertEquals(CoinJoinFundsMigrationService.DIRECT_SHIELD_PREPARING, f.directPhase)
            assertNotNull(parseInFlightMigration(f.marker))
            arg<suspend () -> Unit>(3)()
            SdkWriteResult.Ambiguous(Exception("broadcast outcome unknown"))
        }
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(CoinJoinFundsMigrationService.DIRECT_SHIELD_SUBMITTED, f.directPhase)
        assertTrue(f.service.restorePendingShieldMigration())
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coEvery { shield.unsuppressedCoinJoinShieldLockCount(any()) } answers {
            val settling = firstArg<String?>()
            locks.count { it.blocksFreshDrain || (it.pendingCoinJoinDrain && it.isOfTransaction(settling)) }
        }
        coEvery { shield.recoveredCoinJoinShieldLockCount() } answers {
            locks.count { it.blocksFreshDrain && it.statusRaw == ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN }
        }
        coEvery { shield.pendingWalletShieldLockCount() } answers { locks.count { it.blocksCutover } }
        coEvery { shield.retrySuppressedShieldLockCount() } answers {
            locks.count { it.resumable && it.retrySuppressed }
        }
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

    /**
     * IS-locked (2), ChainLocked (3) and RecoveredFromChain (5) all still hold A's CoinJoin inputs, and so
     * may any status this build does not know (only Consumed, 4, is terminal): adopted like 2 and 3.
     */
    private val unresolvedLockStatuses = listOf(2, 3, 5, 6, 42, -1)

    @Test fun expiredAcknowledgedShieldWithAFailingLockNeverStartsASecondDrain() = runTest {
        for (status in unresolvedLockStatuses) {
            val f = Fixture(this)
            f.trackLocks(lockA(status))
            // The direct shield of A reached the native call and its window expired.
            val startedAt = System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 60_000L
            f.marker = formatInFlightMigration(
                InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, startedAt)
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

            // Try again: lock A's extra resume, nothing else. Every explicit entry (Shield, Combine,
            // Try again) resumes the tracked locks first.
            assertEquals("status $status", MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
            coVerify(exactly = 3) { f.shield.resumePendingWalletShieldsOnUserRequest() }
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
            // Each explicit entry (Shield, Combine, Try again) resumed the tracked locks first.
            coVerify(exactly = 3) { f.shield.resumePendingWalletShieldsOnUserRequest() }
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
                coVerify(exactly = 1) {
                    f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any())
                }
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
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        assertNotEquals(CoinJoinFundsMigrationService.ADOPTED_SHIELD_INPUTS, f.shieldInputs)
    }

    /** A restored wallet's historical CoinJoin lock A (status 5) that Platform reported consumed. */
    private fun Fixture.restoredWithAPlatformConsumedStranger(): MutableList<PendingWalletShieldLock> {
        val locks = trackLocks(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true))
        newCoinJoinCoinsArrive()
        return locks
    }

    /**
     * The restored wallet's new SHIELD starts past stranger A and completes once its OWN new lock
     * reaches Consumed (4): A, which stays at 5, holds neither the drain nor the proof.
     */
    @Test fun restoredWalletShieldPastAPlatformConsumedStrangerCompletesOnItsOwnConsumedLock() = runTest {
        val f = Fixture(this)
        f.restoredWithAPlatformConsumedStranger()
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
        assertNotEquals(CoinJoinFundsMigrationService.ADOPTED_SHIELD_INPUTS, f.shieldInputs)
        runCurrent()
        assertNotNull(f.service.inFlightMigration())
        f.settleShieldLock()
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(1, f.service.verifiedLandings)
    }

    /**
     * The same wallet's new SHIELD does NOT complete while its OWN settling lock is Platform-consumed
     * but not Consumed: identified by txid, it is never set aside as a stranger. It expires with its
     * marker kept, and completes once the SDK writes Consumed.
     */
    @Test fun restoredWalletShieldNeverCompletesOnItsOwnPlatformConsumedLock() = runTest {
        for (status in listOf(2, 3, ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN)) {
            val f = Fixture(this)
            val locks = f.restoredWithAPlatformConsumedStranger()
            assertEquals("$status", MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
            runCurrent()
            val own = Sha256Hash.of("shield-lock".toByteArray())
            f.settleShieldLock(own)
            f.shieldLockStatus[own.toString()] = status
            f.platformConsumed = setOf("$own:0")
            locks += PendingWalletShieldLock("$own:0", status, 1L, coinJoinFunded = true, platformConsumed = true)
            advanceTimeBy(MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS + 60_000); runCurrent()
            assertNotNull("$status", f.service.inFlightMigration())
            assertNotEquals("$status", "", f.shieldInputs)
            assertEquals("$status", 0, f.service.verifiedLandings)

            // The SDK's Consumed: Try again's fresh window completes it.
            f.shieldLockStatus[own.toString()] = ASSET_LOCK_STATUS_CONSUMED
            locks[locks.lastIndex] = locks.last().copy(statusRaw = ASSET_LOCK_STATUS_CONSUMED)
            f.service.retryPendingMigration()
            advanceTimeBy(15_000); runCurrent()
            assertNull("$status", f.service.inFlightMigration())
            assertEquals("$status", 1, f.service.verifiedLandings)
        }
    }

    /** A consolidated SHIELD drains past stranger A and completes on its own Consumed lock. */
    @Test fun restoredWalletConsolidatedShieldPastAPlatformConsumedStrangerCompletes() = runTest {
        val f = Fixture(this)
        f.trackLocks(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true))
        consolidatedShieldInFlight(f)
        landShield(f)
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(1, f.service.verifiedLandings)
    }

    /** SHIELD_PREPARING never reached the native call: stranger A does not stand in for its drain. */
    @Test fun shieldPreparingPastAPlatformConsumedStrangerDrainsOnce() = runTest {
        val f = Fixture(this)
        f.trackLocks(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true))
        f.coins.value = f.coins.value.take(100)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        f.consolidation = CoinJoinConsolidationState(walletId, destination,
            CoinJoinConsolidationState.SHIELD_PREPARING).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
    }

    /**
     * A legacy marker with no recorded inputs has no settling lock to tell apart: a CoinJoin lock
     * Platform reported consumed may be its own, so it never completes until that lock is Consumed.
     */
    @Test fun legacyNoInputsMarkerNeverCompletesPastAPlatformConsumedLock() = runTest {
        for (status in listOf(2, 3, ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN)) {
            val f = Fixture(this)
            val locks = f.trackLocks(lockA(status).copy(platformConsumed = true))
            f.coins.value = emptyList()
            f.engineBalance = 0
            f.marker = "SHIELD|${System.currentTimeMillis()}|0"
            assertTrue("$status", f.service.restorePendingShieldMigration())
            advanceTimeBy(60_000); runCurrent()
            assertNotNull("$status", f.service.inFlightMigration())
            assertEquals("$status", 0, f.service.verifiedLandings)
            locks[0] = locks[0].copy(statusRaw = ASSET_LOCK_STATUS_CONSUMED)
            advanceTimeBy(15_000); runCurrent()
            assertNull("$status", f.service.inFlightMigration())
            assertEquals("$status", 1, f.service.verifiedLandings)
        }
    }

    /**
     * A legacy marker with no recorded inputs, and the store wiped between the proof's lock-count
     * reads: the later read sees the emptied table ("nothing pending"), but the count read FIRST
     * saw the pending lock, so the migration does NOT complete.
     */
    @Test fun legacyNoInputsMarkerStoreWipedBetweenCountReadsDoesNotComplete() = runTest {
        val f = Fixture(this)
        val locks = f.trackLocks(lockA(ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN).copy(platformConsumed = true))
        var reads = 0
        // Only the second read sees the wiped store; the rebind then rebuilds the lock.
        coEvery { f.shield.pendingCoinJoinShieldLockCount() } answers {
            reads++
            if (reads == 2) 0 else locks.count { it.pendingCoinJoinDrain }
        }
        f.coins.value = emptyList()
        f.engineBalance = 0
        f.marker = "SHIELD|${System.currentTimeMillis()}|0"
        assertTrue(f.service.restorePendingShieldMigration())
        advanceTimeBy(60_000); runCurrent()
        assertTrue(reads >= 2)
        assertNotNull(f.service.inFlightMigration())
        assertEquals(0, f.service.verifiedLandings)
    }

    /** The restored wallet's new COMBINE starts past stranger A and completes on its own transfer. */
    @Test fun restoredWalletCombinePastAPlatformConsumedStrangerCompletes() = runTest {
        val f = Fixture(this)
        f.restoredWithAPlatformConsumedStranger()
        combineInFlight(f)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertEquals(MixedFundsMigrationAction.COMBINE, f.service.inFlightMigration()?.action)
        f.storedTx = f.storedTx?.copy(context = 1)
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        assertEquals(1, f.service.verifiedLandings)
    }

    /**
     * Release/clear sites (back to the choice, never a success). A lock Platform reported consumed
     * (final on L1, never resumed again) does not keep any of them — not even where it may be the
     * record's OWN lock (an adopted shield, an invalid stage): keeping those would block every move
     * forever. A pending lock (not reported) keeps every one of them.
     */
    @Test fun releaseSitesSetAsidePlatformConsumedLocksButNeverAPendingOne() = runTest {
        for (settled in listOf(false, true)) {
            for (status in listOf(2, 3, ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN)) {
                val label = "status $status, settled $settled"
                fun reported() = lockA(status).copy(platformConsumed = true)
                fun current() = if (settled) reported().copy(statusRaw = ASSET_LOCK_STATUS_CONSUMED) else reported()

                for (pending in listOf(false, true)) {
                    val tag = "$label, pending $pending"
                    // Adopted (live 2/3 only: a 5 is never adopted).
                    if (status != ASSET_LOCK_STATUS_RECOVERED_FROM_CHAIN) {
                        val f = Fixture(this)
                        val locks = f.trackLocks(lockA(status))
                        f.newCoinJoinCoinsArrive()
                        assertEquals(tag, MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
                        assertEquals(tag, CoinJoinFundsMigrationService.ADOPTED_SHIELD_INPUTS, f.shieldInputs)
                        locks[0] = if (pending) lockA(status) else current()
                        val retried = f.service.retryPendingMigration()
                        if (pending) {
                            assertEquals(tag, MixedFundsMigrationOutcome.UNCONFIRMED, retried)
                            assertEquals(tag, CoinJoinFundsMigrationService.ADOPTED_SHIELD_INPUTS, f.shieldInputs)
                            assertNotNull(tag, f.service.inFlightMigration())
                        } else {
                            assertEquals(tag, MixedFundsMigrationOutcome.NOT_ATTEMPTED, retried)
                            assertEquals(tag, "", f.marker)
                            assertEquals(tag, "", f.shieldInputs)
                        }
                        assertNull(tag, f.done)
                        assertEquals(tag, 0, f.service.verifiedLandings)
                        f.assertNoSecondDrain()
                        if (!pending) {
                            // Back at the choice, the next move spends afresh.
                            assertEquals(tag, MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
                            coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
                        }
                    }

                    // An invalid stage.
                    val k = Fixture(this)
                    k.trackLocks(if (pending) lockA(status) else current())
                    k.consolidation = rawStage("shielding", regularDestination = regular)
                    assertTrue(tag, k.service.restorePendingShieldMigration())
                    runCurrent()
                    val saved = k.consolidation
                    val outcome = k.service.retryPendingMigration()
                    if (pending) {
                        assertEquals(tag, MixedFundsMigrationOutcome.UNCONFIRMED, outcome)
                        assertEquals(tag, saved, k.consolidation)
                        assertNotNull(tag, k.service.inFlightMigration())
                    } else {
                        assertEquals(tag, MixedFundsMigrationOutcome.NOT_ATTEMPTED, outcome)
                        assertEquals(tag, "", k.consolidation)
                        assertNull(tag, k.service.inFlightMigration())
                    }
                    assertNull(tag, k.done)
                    assertEquals(tag, 0, k.service.verifiedLandings)
                    k.assertNothingBuilt()

                    // A definitively failed record.
                    val g = Fixture(this)
                    g.trackLocks(if (pending) lockA(status) else current())
                    val failed = shieldMarker(failed = true)
                    g.marker = failed
                    assertEquals(tag, !pending, g.service.releaseFailedMigration())
                    assertEquals(tag, if (pending) failed else "", g.marker)

                    // A direct shield interrupted before the native call.
                    val h = Fixture(this)
                    h.trackLocks(if (pending) lockA(status) else current())
                    h.marker = formatInFlightMigration(
                        InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis())
                    )
                    h.directPhase = CoinJoinFundsMigrationService.DIRECT_SHIELD_PREPARING
                    assertEquals(tag, pending, h.service.restorePendingShieldMigration())
                    assertEquals(tag, !pending, h.service.inFlightMigration() == null)
                }
            }
        }
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
            coVerify(exactly = 1) {
                f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any())
            }
            assertEquals(next, 0, f.service.verifiedLandings)
        }
    }

    /** An adopted lock that resolves WITH our credit is a verified landing, as before. */
    /**
     * An adopted lock's inputs are unknown, so its resolution (a lock count of 0) cannot be told
     * apart from a lock that was dropped: never a verified landing, even as the shielded balance
     * rises. The next move returns it to the choice, without success, and spends.
     */
    @Test fun adoptedLockIsNeverAVerifiedLandingEvenWhenTheBalanceRises() = runTest {
        val f = Fixture(this)
        val locks = f.trackLocks(lockA(3))
        f.newCoinJoinCoinsArrive()
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        locks.clear()
        f.shieldedBalance.value = Dash.COIN
        advanceTimeBy(60_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        assertNull(f.done)
        assertEquals(0, f.service.verifiedLandings)
        f.assertNoSecondDrain()
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
        assertEquals(0, f.service.verifiedLandings)
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        assertEquals(CoinJoinConsolidationState.WAITING, CoinJoinConsolidationState.decode(f.consolidation).phase)
        // The persisted InstantSend lock is finality for both the checkpoint and the snapshot.
        f.instantLocked = setOf(tx.txId.toString())
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
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
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } returns
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
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }

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
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
    }

    @Test fun failedRecordIsKeptWhileAConsolidationStageIsUnsettled() = runTest {
        val f = Fixture(this)
        f.marker = formatInFlightMigration(InFlightMixedFundsMigration(
            MixedFundsMigrationAction.SHIELD, System.currentTimeMillis(), failed = true))
        f.consolidation = CoinJoinConsolidationState(walletId, destination,
            CoinJoinConsolidationState.SHIELDING).encode()
        assertFalse(f.service.releaseFailedMigration())
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(true, f.service.inFlightMigration()?.failed)
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params), any(), any()) }
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
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } returns
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
            MixedFundsMigrationAction.SHIELD, System.currentTimeMillis()))
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
        InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis(), failed = failed)
    )

    private fun combineMarker() = formatInFlightMigration(
        InFlightMixedFundsMigration(MixedFundsMigrationAction.COMBINE, System.currentTimeMillis())
    )

    private fun Fixture.assertNothingBuilt() {
        coVerify(exactly = 0) { send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
        // A negative start or a non-numeric legacy baseline field is malformed.
        "SHIELD|-1|0", "SHIELD|${System.currentTimeMillis()}|bogus"
    )

    @Test fun negativeStartIsMalformedButAnyLegacyBaselineParses() {
        assertNull(parseInFlightMigration("COMBINE|-1|0"))
        assertNull(parseInFlightMigration("SHIELD|-1|0|failed"))
        // The legacy baseline field is ignored: every number any build wrote is valid.
        for (baseline in listOf("-2", "-1", "0", "7", "${Long.MAX_VALUE}")) {
            val now = System.currentTimeMillis()
            assertEquals(
                baseline,
                InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, now),
                parseInFlightMigration("SHIELD|$now|$baseline")
            )
        }
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
        val stage = rawStage("ready", wallet = "01")
        f.consolidation = stage
        f.marker = shieldMarker()
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        // Never continued: straight to the guidance.
        assertTrue(f.expired())
        f.assertNothingBuilt()
        // No wallet identity: the bound wallet's clean snapshot proves nothing about the
        // stage's own wallet, so neither a fresh move nor Try again releases it.
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals(MixedFundsMigrationOutcome.UNCONFIRMED, f.service.retryPendingMigration())
        assertTrue(f.service.releaseFailedMigration())
        assertEquals(stage, f.consolidation)
        assertTrue(f.expired())
        f.assertNothingBuilt()
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
        marker = combineMarker()
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
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } coAnswers {
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
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
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
            InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis())
        )
        f.coinJoinLocks = 1
        f.service.startInFlightWatcherIfNeeded()
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        f.assertNothingBuilt()
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) }
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
        coVerify(exactly = 0) { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) }
        assertTrue(f.expired())
        assertEquals(tx.txId.toString(), invalid(f.consolidation).txid)
    }

    @Test fun transferWithoutDestinationIsObservedNeverSettledNorRebuilt() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value, regular)
        f.consolidation = rawStage("transferring", tx.txId.toString(), "", Utils.HEX.encode(tx.bitcoinSerialize()))
        f.coinJoinLocks = 1
        val replayed = mutableListOf<ByteArray>()
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) } coAnswers {
            replayed += secondArg<ByteArray>()
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
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) } returns ConsolidationReplay.SUBMITTED
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertEquals(MixedFundsMigrationAction.COMBINE, f.service.inFlightMigration()?.action)
        advanceTimeBy(30_000); runCurrent()
        coVerify(exactly = 1) { f.send.rebroadcastCoinJoinConsolidation(any(), tx.bitcoinSerialize(), tx.txId.toString()) }
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
        coEvery { f.send.rebroadcastCoinJoinConsolidation(any(), any(), any()) } returns ConsolidationReplay.CONFLICT
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
        // A batch of the wallet's own CoinJoin coins: its inputs are persisted as spent by it.
        f.persistSettled(tx)
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

    /**
     * An invalid TRANSFERRING stage (here: signed bytes that do not hash to its
     * txid) is released only once its BIP44 destination output is persisted —
     * a final txid with spent inputs is not enough. Without a recorded BIP44
     * destination it is never released by proof.
     */
    @Test fun invalidTransferStageIsReleasedOnlyOnceItsBip44OutputIsPersisted() = runTest {
        val f = Fixture(this)
        val tx = transaction((0 until 3).map(::coin), regular)
        val other = Utils.HEX.encode(transaction((3 until 5).map(::coin), regular).bitcoinSerialize())
        val stage = rawStage("transferring", tx.txId.toString(), regular, other)
        assertEquals("signed bytes do not hash to the txid", invalid(stage).reason)
        f.consolidation = stage
        f.marker = combineMarker()
        f.store(tx)
        f.coins.value = emptyList()
        f.engineBalance = 0
        f.persistSettled(tx)
        val output = Utils.HEX.encode(TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize())
        val persistedOutput = requireNotNull(f.txos.remove(output))
        // Final, inputs spent by it, account empty — but the destination output is not persisted.
        assertTrue(f.service.releaseFailedMigration())
        assertEquals(stage, f.consolidation)
        assertNotNull(f.service.inFlightMigration())
        // Persisted on the destination: provably nothing is out.
        f.txos[output] = persistedOutput
        assertTrue(f.service.releaseFailedMigration())
        assertEquals("", f.consolidation)
        assertNull(f.service.inFlightMigration())
        f.assertNothingBuilt()

        // No recoverable BIP44 destination: never released, however settled it looks.
        val noDestination = rawStage("transferring", tx.txId.toString(), "", Utils.HEX.encode(tx.bitcoinSerialize()))
        f.consolidation = noDestination
        f.marker = formatInFlightMigration(
            InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, System.currentTimeMillis())
        )
        assertTrue(f.service.releaseFailedMigration())
        assertEquals(noDestination, f.consolidation)
        f.assertNothingBuilt()
    }

    /**
     * A future start (Long.MAX_VALUE, or the clock moved back) reads as EXPIRED:
     * "could not confirm" guidance with its action, failed flag and stage kept —
     * never a watcher, never a repair that re-arms the stage.
     */
    @Test fun futureMarkerReadsAsExpiredGuidanceKeepingItsRecord() = runTest {
        val soon = System.currentTimeMillis() + 60 * 60_000L
        for (raw in listOf("COMBINE|${Long.MAX_VALUE}|0", "COMBINE|$soon|0|failed")) {
            val f = Fixture(this)
            // A definitively failed (READY) stage: re-arming it would spend without a tap.
            val stage = CoinJoinConsolidationState(walletId, destination, regularDestination = regular).encode()
            f.consolidation = stage
            f.marker = raw
            val read = requireNotNull(f.service.inFlightMigration())
            assertEquals(raw, MixedFundsMigrationAction.COMBINE, read.action)
            assertEquals(raw, raw.endsWith("failed"), read.failed)
            assertTrue(raw, read.isExpired())
            assertTrue(raw, f.service.restorePendingShieldMigration())
            runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(raw, raw, f.marker)
            assertEquals(raw, stage, f.consolidation)
            f.assertNothingBuilt()
        }
    }

    /**
     * The honesty window never exceeds the timeout (plus the clock-jitter allowance before a
     * slightly-future start), at any reading time, and its arithmetic cannot overflow.
     */
    @Test fun markerWindowNeverExceedsTheTimeoutAndCannotOverflow() {
        val now = 1_700_000_000_000L
        val t = MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS
        val skew = MIXED_FUNDS_START_SKEW_MS
        val starts = listOf(0L, now - 2 * t, now - t - 1, now - t, now - 1, now, now + 5, now + skew - 1,
            now + skew, now + skew + 1, now + t, now + 86_400_000L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val readings = listOf(0L, now - 1, now, now + 1, now + t, now + t + 1, now + 2 * t, Long.MAX_VALUE)
        for (start in starts) for (at in readings) {
            val marker = InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, start)
            val remaining = marker.remainingMillis(at)
            val case = "start=$start at=$at"
            assertTrue(case, remaining in -1L..t)
            // Pending only from up to `skew` before its start until the window lapses.
            assertEquals(case, at - start < -skew || at - start > t, marker.isExpired(at))
            if (!marker.isExpired(at)) assertEquals(case, t - (at - start).coerceAtLeast(0L), remaining)
        }
        // Total wall-clock window: at most the timeout plus the allowance.
        val ahead = InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, now + skew)
        assertFalse(ahead.isExpired(now))
        assertFalse(ahead.isExpired(now + skew + t))
        assertTrue(ahead.isExpired(now + skew + t + 1))
        // A few milliseconds ahead (a backward clock step right after writing) is still pending.
        val jitter = InFlightMixedFundsMigration(MixedFundsMigrationAction.SHIELD, now + 5)
        assertFalse(jitter.isExpired(now))
        assertEquals(t, jitter.remainingMillis(now))
        // Parsing keeps the recorded start: the record (and its acknowledgement key) is unchanged.
        val parsed = requireNotNull(parseInFlightMigration("SHIELD|${Long.MAX_VALUE}|0"))
        assertEquals(Long.MAX_VALUE, parsed.startedAtMillis)
    }

    /** Route availability is per route: shielding alone never makes Combine operable. */
    @Test fun routeAvailabilityReportsEachRouteOnItsOwnGate() = runTest {
        val f = Fixture(this)
        coEvery { f.shield.isWalletShieldingAvailable() } returns true
        coEvery { f.send.coinJoinConsolidationGateOpen() } returns false
        // 101 coins must be consolidated first: with the send gate shut, Shield cannot run either.
        assertEquals(MixedFundsRoutes(shieldOperable = false, combineOperable = false), f.service.routeAvailability())
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        // 20 coins drain directly: Shield alone is offered.
        f.coins.value = (0 until 20).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        assertEquals(MixedFundsRoutes(shieldOperable = true, combineOperable = false), f.service.routeAvailability())
        assertTrue(f.service.shouldOfferInTools())
        // An unreadable snapshot (balance mismatch) offers neither.
        f.engineBalance -= 1
        coEvery { f.send.coinJoinConsolidationGateOpen() } returns true
        assertEquals(MixedFundsRoutes(shieldOperable = false, combineOperable = false), f.service.routeAvailability())
        f.engineBalance += 1
        coEvery { f.shield.isWalletShieldingAvailable() } throws IllegalStateException("probe failed")
        coEvery { f.send.coinJoinConsolidationGateOpen() } returns true
        assertEquals(MixedFundsRoutes(shieldOperable = false, combineOperable = true), f.service.routeAvailability())
        coEvery { f.send.cutoverCommitted() } returns false
        assertEquals(MixedFundsRoutes(shieldOperable = false, combineOperable = false), f.service.routeAvailability())
    }

    /** App-locked CoinJoin outputs make BOTH routes inoperable, and Combine refuses before recording anything. */
    @Test fun appLockedCoinJoinOutputClosesBothRoutesAndCombineRecordsNothing() = runTest {
        val f = Fixture(this)
        coEvery { f.shield.isWalletShieldingAvailable() } returns true
        every { f.send.coinJoinOutputsLocked() } returns true
        assertEquals(MixedFundsRoutes(shieldOperable = false, combineOperable = false), f.service.routeAvailability())
        assertFalse(f.service.shouldOfferInTools())
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        f.mockConsolidation(to = regular)
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.combineIntoUnmixedBalance())
        assertEquals("", f.marker)
        assertEquals("", f.consolidation)
        runCurrent()
        f.assertNothingBuilt()
        // Unlocked: both routes, and the same move runs.
        every { f.send.coinJoinOutputsLocked() } returns false
        assertEquals(MixedFundsRoutes(shieldOperable = true, combineOperable = true), f.service.routeAvailability())
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
    }

    // ── Invalid stages: released / completed only on the WORST CASE their fields allow ──

    private enum class RecordedTx { NONE, BATCH, TRANSFER }
    private enum class Bytes { NONE, MATCHING, MISMATCHED }

    /** One persisted invalid stage, what the store holds, and the outcomes the worst-case rule allows. */
    private data class InvalidRow(
        val name: String,
        val stage: (batch: Transaction, transfer: Transaction, other: Transaction) -> String,
        val settled: RecordedTx,
        val outputPersisted: Boolean,
        val released: Boolean,
        val completed: Boolean
    )

    private fun invalidStageRows(): List<InvalidRow> {
        val rows = mutableListOf<InvalidRow>()
        val flags = listOf(true, false)
        for (phase in listOf("waiting", "transferring", "consolidating")) for (recorded in RecordedTx.values())
            for (withRegular in flags) for (bytes in Bytes.values()) for (persisted in flags) {
                val released = when (recorded) {
                    // Nothing identifies a tx: only a stage that recorded none and cannot be a transfer.
                    RecordedTx.NONE -> bytes == Bytes.NONE && phase != "transferring" &&
                        !(phase == "consolidating" && withRegular)
                    RecordedTx.BATCH -> true
                    RecordedTx.TRANSFER -> withRegular && persisted
                }
                rows += InvalidRow(
                    "$phase/$recorded/regular=$withRegular/$bytes/persisted=$persisted",
                    { batch, transfer, other ->
                        val tx = if (recorded == RecordedTx.BATCH) batch else transfer
                        rawStage(
                            phase,
                            if (recorded == RecordedTx.NONE) "" else tx.txId.toString(),
                            if (withRegular) regular else "",
                            when (bytes) {
                                Bytes.NONE -> null
                                Bytes.MATCHING -> Utils.HEX.encode(tx.bitcoinSerialize())
                                Bytes.MISMATCHED -> Utils.HEX.encode(other.bitcoinSerialize())
                            }
                        )
                    },
                    if (recorded == RecordedTx.NONE) RecordedTx.TRANSFER else recorded,
                    persisted, released,
                    completed = recorded == RecordedTx.TRANSFER && withRegular && persisted
                )
            }
        fun json(phase: Any, txid: Any, regularDestination: Any, rawTx: Any = "", wallet: String = walletId) =
            org.json.JSONObject().put("walletId", wallet).put("destination", destination).put("phase", phase)
                .put("txid", txid).put("regularDestination", regularDestination).put("rawTx", rawTx).toString()
        // Unreadable BIP44 destination: a transfer cannot be proven, a batch still can.
        rows += InvalidRow("unknown phase, unreadable destination, transfer",
            { _, t, _ -> json("consolidating", t.txId.toString(), 7) }, RecordedTx.TRANSFER,
            outputPersisted = true, released = false, completed = false)
        rows += InvalidRow("unknown phase, unreadable destination, batch",
            { b, _, _ -> json("consolidating", b.txId.toString(), 7) }, RecordedTx.BATCH,
            outputPersisted = true, released = true, completed = false)
        rows += InvalidRow("unknown phase, unreadable destination, no txid",
            { _, _, _ -> json("consolidating", "", 7) }, RecordedTx.TRANSFER,
            outputPersisted = true, released = false, completed = false)
        rows += InvalidRow("non-string phase, transfer",
            { _, t, _ -> json(5, t.txId.toString(), regular) }, RecordedTx.TRANSFER,
            outputPersisted = false, released = false, completed = false)
        // A recorded but unusable txid, or unreadable bytes: the tx cannot be identified.
        rows += InvalidRow("malformed txid", { _, _, _ -> json("waiting", "zz".repeat(32), "") },
            RecordedTx.TRANSFER,
            outputPersisted = true, released = false, completed = false)
        rows += InvalidRow("non-string txid", { _, _, _ -> json("waiting", 123, "") },
            RecordedTx.TRANSFER,
            outputPersisted = true, released = false, completed = false)
        rows += InvalidRow("non-string rawTx", { _, _, _ -> json("ready", "", "", 9) },
            RecordedTx.TRANSFER,
            outputPersisted = true, released = false, completed = false)
        // No wallet identity: never, even for a provable batch.
        rows += InvalidRow("no wallet identity, batch",
            { b, _, _ -> json("waiting", b.txId.toString(), "", wallet = "01") }, RecordedTx.BATCH,
            outputPersisted = true, released = false, completed = false)
        return rows
    }

    /** Sets the row up; null when its fields happen to form a VALID stage (not this table's subject). */
    private fun Fixture.setUpInvalidRow(row: InvalidRow): String? {
        val batch = transaction((0 until 3).map(::coin))
        val transfer = transaction((0 until 3).map(::coin), regular)
        val other = transaction((3 until 5).map(::coin), regular)
        val stage = row.stage(batch, transfer, other)
        if (CoinJoinConsolidationState.parse(stage) !is ConsolidationStageRead.Invalid) return null
        consolidation = stage
        marker = combineMarker()
        // The account is drained and the recorded tx is final with its inputs spent by it.
        val tx = if (row.settled == RecordedTx.BATCH) batch else transfer
        store(tx)
        coins.value = emptyList()
        engineBalance = 0
        persistSettled(tx)
        if (!row.outputPersisted) {
            txos.remove(Utils.HEX.encode(TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize()))
        }
        return stage
    }

    @Test fun invalidStagesAreReleasedByProofOnlyForTheirWorstCase() = runTest {
        var checked = 0
        var releasedRows = 0
        for (row in invalidStageRows()) {
            val f = Fixture(this)
            val stage = f.setUpInvalidRow(row) ?: continue
            checked++
            if (row.released) releasedRows++
            assertTrue(row.name, f.service.releaseFailedMigration())
            assertEquals(row.name, if (row.released) "" else stage, f.consolidation)
            assertNull(row.name, f.done)
            f.assertNothingBuilt()
        }
        // Both outcomes are exercised across many shapes.
        assertTrue("$checked", checked > 60)
        assertTrue("$releasedRows", releasedRows in 10 until checked - 10)
    }

    @Test fun invalidStageWatcherCompletesOnlyOnTheDestinationProof() = runTest {
        var completedRows = 0
        for (row in invalidStageRows()) {
            val f = Fixture(this)
            val stage = f.setUpInvalidRow(row) ?: continue
            if (row.completed) completedRows++
            f.service.startInFlightWatcherIfNeeded()
            runCurrent()
            advanceTimeBy(60_000); runCurrent()
            if (row.completed) {
                assertEquals(row.name, true, f.done)
                assertEquals(row.name, "", f.consolidation)
            } else {
                assertNull(row.name, f.done)
                assertEquals(row.name, stage, f.consolidation)
                assertNotNull(row.name, f.service.inFlightMigration())
                // A provable batch has nothing to complete: straight to the guidance.
                if (row.settled == RecordedTx.BATCH) assertTrue(row.name, f.expired())
            }
            f.assertNothingBuilt()
            f.service.clearInFlightMigration()
            advanceTimeBy(60_000); runCurrent()
        }
        assertTrue("$completedRows", completedRows > 0)
    }

    // ── Proofs are tied to THIS wallet's own CoinJoin coins ──────────

    /** Another of the wallet's CoinJoin account-0 addresses (not the stage destination). */
    private val otherCoinJoin =
        Address.fromKey(params, ECKey.fromPrivate(Sha256Hash.hash("cj-1".toByteArray()))).toString()

    /** Rewrite the persisted TXO row at [outpoint]. */
    private fun Fixture.retag(outpoint: ByteArray, change: (TxoEntity) -> TxoEntity) {
        val key = Utils.HEX.encode(outpoint)
        txos[key] = change(requireNotNull(txos[key]))
    }

    /** The wallet's own BIP44 TXO (address and account). */
    private fun bip44(row: TxoEntity): TxoEntity = row.copy(address = regular, accountId = 9L)

    /**
     * An invalid stage (unknown phase, no BIP44 destination: only a batch can release it) whose
     * recorded tx is final and pays the stage's CoinJoin destination. Released only when that
     * tx is a self-spend of THIS wallet's own CoinJoin coins; an unrelated payment to a CoinJoin
     * address, or a spend of anything else, keeps it blocked.
     */
    @Test fun invalidStageIsReleasedOnlyByASelfSpendOfTheWalletsOwnCoinJoinCoins() = runTest {
        val cases = listOf<Triple<String, Boolean, Fixture.(Transaction) -> Unit>>(
            Triple("own CoinJoin coins spent by it", true) { },
            Triple("anyone's coins: no persisted input rows", false) { tx ->
                tx.inputs.forEach { txos.remove(Utils.HEX.encode(it.outpoint.bitcoinSerialize())) }
            },
            Triple("another wallet's rows", false) { tx ->
                val other = Utils.HEX.decode("02".repeat(32))
                retag(tx.inputs[1].outpoint.bitcoinSerialize()) { it.copy(walletId = other) }
            },
            Triple("one BIP44 input", false) { tx -> retag(tx.inputs[0].outpoint.bitcoinSerialize(), ::bip44) },
            // The snapshot's rule: the CoinJoin account id alone is enough, whatever the address.
            Triple("a CoinJoin input by account only", true) { tx ->
                retag(tx.inputs[2].outpoint.bitcoinSerialize()) { it.copy(address = regular, accountId = 7L) }
            },
            Triple("an input spent by another tx", false) { tx ->
                val other = Sha256Hash.of("x".toByteArray()).reversedBytes
                markSpent(listOf(tx.inputs[0].outpoint.bitcoinSerialize()), other)
            }
        )
        for ((name, released, tamper) in cases) {
            val f = Fixture(this)
            val tx = transaction((0 until 3).map(::coin))
            val stage = rawStage("consolidating", tx.txId.toString(), "", Utils.HEX.encode(tx.bitcoinSerialize()))
            assertFalse(name, invalid(stage).mayBeFinalTransfer)
            f.consolidation = stage
            f.marker = combineMarker()
            f.store(tx)
            f.coins.value = emptyList()
            f.engineBalance = 0
            f.persistSettled(tx)
            f.tamper(tx)
            assertTrue(name, f.service.releaseFailedMigration())
            assertEquals(name, if (released) "" else stage, f.consolidation)
            f.assertNothingBuilt()
        }

        // Own coins, but paying another CoinJoin address than the stage's destination.
        val f = Fixture(this)
        f.ownedAddresses[otherCoinJoin] =
            CoreAddressEntity(otherCoinJoin, poolTypeTag = 0, addressIndex = 1, derivationPath = "", accountId = 7L)
        val elsewhere = transaction((0 until 3).map(::coin), otherCoinJoin)
        val stage =
            rawStage("consolidating", elsewhere.txId.toString(), "", Utils.HEX.encode(elsewhere.bitcoinSerialize()))
        f.consolidation = stage
        f.marker = combineMarker()
        f.store(elsewhere)
        f.coins.value = emptyList()
        f.engineBalance = 0
        f.persistSettled(elsewhere)
        assertTrue(f.service.releaseFailedMigration())
        assertEquals(stage, f.consolidation)
    }

    /**
     * The recorded final transfer completes the migration only when ITS inputs were the
     * wallet's own CoinJoin coins: a BIP44 self-send to the same destination, settled and
     * persisted, is not the migration — no DONE, the record kept.
     */
    @Test fun finalTransferCompletesOnlyFromTheWalletsOwnCoinJoinCoins() = runTest {
        for (fromCoinJoin in listOf(false, true)) {
            val f = Fixture(this)
            val tx = f.finalTransferInFlight((0 until 3).map(::coin))
            f.persistSettled(tx)
            if (!fromCoinJoin) {
                tx.inputs.forEach { f.retag(it.outpoint.bitcoinSerialize(), ::bip44) }
            }
            f.service.startInFlightWatcherIfNeeded()
            runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals("fromCoinJoin $fromCoinJoin", if (fromCoinJoin) true else null, f.done)
            assertEquals("fromCoinJoin $fromCoinJoin", !fromCoinJoin, f.service.inFlightMigration() != null)
            f.service.clearInFlightMigration()
            advanceTimeBy(60_000); runCurrent()
        }
    }

    /**
     * A shield completes only when its RECORDED inputs are the wallet's own CoinJoin coins
     * spent by one shield lock: a record naming BIP44 coins (spent by an ordinary BIP44
     * shield) never completes the migration.
     */
    @Test fun recordedShieldInputsCompleteOnlyAsTheWalletsOwnCoinJoinCoins() = runTest {
        for (coinJoinInputs in listOf(false, true)) {
            val label = "coinJoinInputs $coinJoinInputs"
            val f = Fixture(this)
            f.coins.value = f.coins.value.take(100)
            f.engineBalance = f.coins.value.sumOf { it.amount }
            coEvery { f.shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) } returns
                SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED)
            assertEquals(label, MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
            runCurrent()
            f.settleShieldLock()
            if (!coinJoinInputs) {
                decodeOutpoints(f.shieldInputs).forEach { f.retag(it, ::bip44) }
            }
            // The account-level fallback cannot stand in for the record: the coins stay.
            f.shieldedBalance.value = Dash.COIN
            advanceTimeBy(60_000); runCurrent()
            assertEquals(label, !coinJoinInputs, f.service.inFlightMigration() != null)
            f.service.clearInFlightMigration()
            advanceTimeBy(60_000); runCurrent()
        }
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
        f.persistSettled(tx)
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
