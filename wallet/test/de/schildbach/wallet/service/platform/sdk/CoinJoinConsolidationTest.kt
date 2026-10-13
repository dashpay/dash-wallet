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
    private fun transaction(coins: List<TxoEntity>): Transaction = Transaction(params).apply {
        coins.forEach {
            addInput(TransactionInput(params, this, byteArrayOf(),
                TransactionOutPoint(params, 0, Sha256Hash.wrapReversed(it.txid!!))))
        }
        addOutput(Coin.valueOf(coins.sumOf { it.amount } - 100_000), Address.fromBase58(params, destination))
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
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        val send = mockk<SdkL1SendService>(relaxed = true)
        val sdk = mockk<DashSdkService>(relaxed = true)
        val db = mockk<DashDatabase>(relaxed = true)
        val manager = mockk<PlatformWalletManager>(relaxed = true)
        val blockchain = mockk<BlockchainStateProvider>(relaxed = true)
        val chainState = mockk<org.dash.wallet.common.data.entity.BlockchainState>(relaxed = true)
        val coins = MutableStateFlow((0 until 101).map(::coin))
        val addresses = MutableStateFlow(listOf(address()))
        var engineBalance = coins.value.sumOf { it.amount }
        var storedTx: TransactionEntity? = null
        val service: CoinJoinFundsMigrationService
        init {
            coEvery { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { marker }
            coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) } answers { marker = secondArg() }
            coEvery { config.get(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } answers { consolidation }
            coEvery { config.set(DashPayConfig.MIXED_FUNDS_CONSOLIDATION, any()) } answers { consolidation = secondArg() }
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
            coEvery { shield.pendingWalletShieldLockCount() } returns 0
            every { shield.observeShieldedBalance() } returns MutableStateFlow(Dash.ZERO)
            coEvery { shield.shieldMixedFundsFromWallet(any(), any()) } returns SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY)
            val wallet = mockk<WalletData>(relaxed = true)
            every { wallet.networkParameters } returns params
            service = CoinJoinFundsMigrationService(wallet, shield, send, blockchain, config, sdk, scope.backgroundScope)
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any()) }
        f.storedTx = TransactionEntity(tx.txId.reversedBytes, tx.bitcoinSerialize(), context = 1)
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any()) }
        val output = coin(999).copy(outpoint = TransactionOutPoint(params, 0, tx.txId).bitcoinSerialize(),
            txid = tx.txId.reversedBytes, amount = tx.outputs[0].value.value)
        f.coins.value = listOf(output)
        f.engineBalance = output.amount
        advanceTimeBy(15_000); runCurrent()
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), coinJoinAccountPath(params)) }
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
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any()) }
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
        coEvery { f.shield.shieldMixedFundsFromWallet(any(), any()) } coAnswers {
            assertNotNull(parseInFlightMigration(f.marker))
            SdkWriteResult.NotBroadcast("definitive rejection")
        }
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        assertNull(f.service.inFlightMigration())
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any()) }
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
    }

    @Test fun databaseBalanceMismatchPreventsSpending() = runTest {
        val f = Fixture(this)
        f.engineBalance += 100
        assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, f.service.shieldMixedFunds(Dash.COIN))
        coVerify(exactly = 0) { f.send.consolidateCoinJoin(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.shield.shieldMixedFundsFromWallet(any(), any()) }
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
        coVerify(exactly = 1) { f.shield.shieldMixedFundsFromWallet(any(), any()) }
    }
}
