package de.schildbach.wallet.service.platform.sdk

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoinJoinMigrationRecoveryTest {
    @Test
    fun pendingLockRestoresMarkerAndBlocksBothNewSpends() = runBlocking {
        val config = mockk<DashPayConfig>(relaxed = true)
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } returns ""
        var marker = ""
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { marker }
        coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) } answers {
            marker = secondArg()
        }
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        coEvery { shield.ensureShieldedReady() } returns true
        coEvery { shield.pendingWalletShieldLockCount() } returns 1
        coEvery { shield.resumePendingWalletShields() } returns 1
        coEvery { shield.observeShieldedBalance() } returns MutableStateFlow(Dash.ZERO)
        val send = mockk<SdkL1SendService>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CoinJoinFundsMigrationService(
            mockk(relaxed = true), shield, send, mockk(relaxed = true), config,
            mockk(relaxed = true), scope
        )
        try {
            assertEquals(MixedFundsMigrationOutcome.STARTED, service.shieldMixedFunds(Dash.COIN))
            assertNotNull(service.inFlightMigration())
            assertEquals(MixedFundsMigrationOutcome.STARTED, service.combineIntoUnmixedBalance())
            assertEquals(MixedFundsMigrationOutcome.STARTED, service.retryPendingMigration())
            coVerify(exactly = 1) { shield.resumePendingWalletShields() }
            coVerify(exactly = 0) { shield.shieldMixedFundsFromWallet(any(), any()) }
            coVerify(exactly = 0) { send.drainCoinJoinAccountTo(any()) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unavailableSyncedSnapshotPreventsFunding() = runBlocking {
        val config = mockk<DashPayConfig>(relaxed = true)
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } returns ""
        var marker = ""
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { marker }
        coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) } answers {
            marker = secondArg()
        }
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        coEvery { shield.ensureShieldedReady() } returns true
        coEvery { shield.pendingWalletShieldLockCount() } returns 0
        coEvery { shield.observeShieldedBalance() } returns MutableStateFlow(Dash.ZERO)
        val wallet = mockk<WalletData>(relaxed = true)
        coEvery { wallet.networkParameters } returns TestNet3Params.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CoinJoinFundsMigrationService(
            wallet, shield, mockk(relaxed = true), mockk(relaxed = true), config,
            mockk(relaxed = true), scope
        )
        try {
            assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, service.shieldMixedFunds(Dash.COIN))
            assertNull(service.inFlightMigration())
            coVerify(exactly = 0) { shield.shieldMixedFundsFromWallet(any(), any()) }
        } finally {
            scope.cancel()
        }
    }
}
