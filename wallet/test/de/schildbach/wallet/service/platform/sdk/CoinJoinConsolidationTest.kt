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
    private fun address(index: Int = 0) = CoreAddressEntity(
        destination, poolTypeTag = 0, addressIndex = index, derivationPath = "", accountId = 7L
    )
    private fun coin(index: Int) : TxoEntity {
        val hash = Sha256Hash.of(index.toString().toByteArray())
        return TxoEntity(TransactionOutPoint(params, 0, hash).bitcoinSerialize(), 0, 1_000_000,
            destination, isConfirmed = true, walletId = Utils.HEX.decode(walletId),
            txid = hash.reversedBytes, accountId = 7L)
    }
    private fun transaction(coins: List<TxoEntity>, to: String = destination): Transaction = Transaction(params).apply {
        coins.forEach {
            addInput(TransactionInput(params, this, byteArrayOf(),
                TransactionOutPoint(params, 0, Sha256Hash.wrapReversed(it.txid!!))))
        }
        addOutput(Coin.valueOf(coins.sumOf { it.amount } - 100_000), Address.fromBase58(params, to))
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
            coEvery { config.set(DashPayConfig.MIXED_FUNDS_CONSOLIDATION, any()) } answers { consolidation = secondArg() }
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE) } answers { directPhase }
            coEvery { config.set(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE, any()) } answers { directPhase = secondArg() }
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
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any()) } coAnswers {
            arg<suspend (ByteArray) -> Unit>(3)(tx.bitcoinSerialize())
            assertEquals(tx.txId.toString(), CoinJoinConsolidationState.decode(f.consolidation).txid)
            SdkWriteResult.Broadcast(tx.txId.toString())
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.shieldMixedFunds(Dash.COIN))
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(destination, 1L, true, any()) }
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
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
    }

    @Test fun restartWithPendingConsolidationDoesNotBuildAnotherTransaction() = runTest {
        val f = Fixture(this)
        f.consolidation = CoinJoinConsolidationState(walletId, destination,
            CoinJoinConsolidationState.WAITING, "02".repeat(32)).encode()
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun missingAddressOrIncompleteSyncCannotStartConsolidation() = runTest {
        val f = Fixture(this)
        f.addresses.value = emptyList()
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        f.addresses.value = listOf(address())
        every { f.chainState.isSynced() } returns false
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertEquals("", f.consolidation)
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
    }

    @Test fun databaseBalanceMismatchPreventsSpending() = runTest {
        val f = Fixture(this)
        f.engineBalance += 100
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun thousandsOfOutputsConsolidateInBoundedBatches() = runTest {
        val f = Fixture(this)
        f.coins.value = (0 until 2000).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        var batches = 0
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any()) } coAnswers {
            val selected = f.coins.value.sortedByDescending { it.amount }.take(500)
            val tx = transaction(selected)
            arg<suspend (ByteArray) -> Unit>(3)(tx.bitcoinSerialize())
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
        val regular = "yXfXh3jFYHHxnJZVsXnPcktCENqPaAhcX1"
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        f.coins.value = (0 until 2000).map(::coin)
        f.engineBalance = f.coins.value.sumOf { it.amount }
        val batchSizes = mutableListOf<Int>()
        val destinations = mutableListOf<String>()
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any()) } coAnswers {
            val to = arg<String>(0)
            val selected = f.coins.value.sortedByDescending { it.amount }.take(500)
            val tx = transaction(selected, to)
            arg<suspend (ByteArray) -> Unit>(3)(tx.bitcoinSerialize())
            batchSizes += selected.size
            destinations += to
            f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 2)
            val output = coin(10_000 + batchSizes.size).copy(
                outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
                txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
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
        val regular = "yXfXh3jFYHHxnJZVsXnPcktCENqPaAhcX1"
        val tx = transaction(f.coins.value, regular)
        f.consolidation = CoinJoinConsolidationState(walletId, destination,
            CoinJoinConsolidationState.TRANSFERRING, tx.txId.toString(), regular).encode()
        f.coins.value = emptyList()
        f.engineBalance = 0
        f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 0)
        assertTrue(f.service.restorePendingShieldMigration())
        runCurrent()
        assertEquals(MixedFundsMigrationAction.COMBINE, f.service.inFlightMigration()?.action)
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.retryPendingMigration())
        runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertNotNull(f.service.inFlightMigration())
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
        f.storedTx = f.storedTx!!.copy(context = 1)
        advanceTimeBy(15_000); runCurrent()
        assertNull(f.service.inFlightMigration())
        verify(exactly = 0) { f.wallet.unadvertisedDestinationLive() }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any(), any()) }
    }

    @Test fun singleInputRegularTransferIsAllowedAndTracked() = runTest {
        val f = Fixture(this)
        val regular = "yXfXh3jFYHHxnJZVsXnPcktCENqPaAhcX1"
        every { f.wallet.unadvertisedDestinationLive() } returns Address.fromBase58(params, regular)
        f.coins.value = listOf(coin(0))
        f.engineBalance = f.coins.value.sumOf { it.amount }
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any()) } coAnswers {
            val tx = transaction(f.coins.value, regular)
            arg<suspend (ByteArray) -> Unit>(3)(tx.bitcoinSerialize())
            assertEquals(CoinJoinConsolidationState.TRANSFERRING,
                CoinJoinConsolidationState.decode(f.consolidation).phase)
            SdkWriteResult.Broadcast(tx.txId.toString())
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(regular, 1L, true, any()) }
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

        // The drain is confirmed: the CoinJoin account is empty.
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
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
    }

    @Test fun persistedInstantSendLockAdvancesConsolidationBeforeABlock() = runTest {
        val f = Fixture(this)
        val tx = transaction(f.coins.value)
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any()) } coAnswers {
            arg<suspend (ByteArray) -> Unit>(3)(tx.bitcoinSerialize())
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
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
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
        val regular = "yXfXh3jFYHHxnJZVsXnPcktCENqPaAhcX1"
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
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }

        // Settled and nothing pending: the switch to the wallet balance runs.
        f.coinJoinLocks = 0
        coEvery { f.send.consolidateCoinJoin(any(), any(), any(), any()) } coAnswers {
            val tx = transaction(f.coins.value, regular)
            arg<suspend (ByteArray) -> Unit>(3)(tx.bitcoinSerialize())
            SdkWriteResult.Broadcast(tx.txId.toString())
        }
        assertEquals(MixedFundsMigrationOutcome.STARTED, f.service.combineIntoUnmixedBalance())
        runCurrent()
        val marker = f.service.inFlightMigration()
        assertEquals(MixedFundsMigrationAction.COMBINE, marker?.action)
        assertEquals(false, marker?.failed)
        coVerify(exactly = 1) { f.send.consolidateCoinJoin(regular, 1L, true, any()) }
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
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
    }
}
