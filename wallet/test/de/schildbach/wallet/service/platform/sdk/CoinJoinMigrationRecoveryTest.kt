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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        coEvery { shield.pendingCoinJoinShieldLockCount() } returns 1
        coEvery { shield.resumePendingWalletShieldsOnUserRequest() } returns 1
        coEvery { shield.observeShieldedBalance() } returns MutableStateFlow(Dash.ZERO)
        val send = mockk<SdkL1SendService>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CoinJoinFundsMigrationService(
            mockk(relaxed = true), shield, send, mockk(relaxed = true), config,
            mockk(relaxed = true), scope, mockk(relaxed = true)
        )
        try {
            assertEquals(MixedFundsMigrationOutcome.STARTED, service.shieldMixedFunds(Dash.COIN))
            assertNotNull(service.inFlightMigration())
            assertEquals(MixedFundsMigrationOutcome.STARTED, service.combineIntoUnmixedBalance())
            assertEquals(MixedFundsMigrationOutcome.STARTED, service.retryPendingMigration())
            coVerify(exactly = 1) { shield.resumePendingWalletShieldsOnUserRequest() }
            coVerify(exactly = 0) { shield.shieldMixedFundsFromWallet(any(), any(), any()) }
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
        coEvery { shield.pendingCoinJoinShieldLockCount() } returns 0
        coEvery { shield.observeShieldedBalance() } returns MutableStateFlow(Dash.ZERO)
        val wallet = mockk<WalletData>(relaxed = true)
        coEvery { wallet.networkParameters } returns TestNet3Params.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CoinJoinFundsMigrationService(
            wallet, shield, mockk(relaxed = true), mockk(relaxed = true), config,
            mockk(relaxed = true), scope, mockk(relaxed = true)
        )
        try {
            assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, service.shieldMixedFunds(Dash.COIN))
            assertNull(service.inFlightMigration())
            coVerify(exactly = 0) { shield.shieldMixedFundsFromWallet(any(), any(), any()) }
        } finally {
            scope.cancel()
        }
    }
    @Test
    fun preCutover_noFreshPrompt_butInFlightMigrationStillPresented() = runBlocking {
        val config = mockk<DashPayConfig>(relaxed = true)
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } returns ""
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) } returns false
        var marker = ""
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { marker }
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        // Shielding available would make anyOptionOperable() true pre-cutover.
        coEvery { shield.isWalletShieldingAvailable() } returns true
        coEvery { shield.ensureShieldedReady() } returns true
        coEvery { shield.pendingCoinJoinShieldLockCount() } returns 0
        coEvery { shield.observeShieldedBalance() } returns MutableStateFlow(Dash.ZERO)
        val send = mockk<SdkL1SendService>(relaxed = true)
        coEvery { send.cutoverCommitted() } returns false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CoinJoinFundsMigrationService(
            mockk(relaxed = true), shield, send, mockk(relaxed = true), config,
            mockk(relaxed = true), scope, mockk(relaxed = true)
        )
        try {
            // Fresh path: nothing in flight, pre-cutover -> no prompt, and the
            // gate short-circuits before the rest of the fresh-prompt checks.
            assertFalse(service.shouldPrompt())
            coVerify(exactly = 0) { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) }

            // In-flight path: an existing migration is still presented pre-cutover.
            marker = formatInFlightMigration(
                InFlightMixedFundsMigration(
                    MixedFundsMigrationAction.SHIELD,
                    System.currentTimeMillis(),
                    0L
                )
            )
            assertTrue(service.shouldPrompt())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun acknowledgedUnconfirmedMigrationStopsAutomaticPresentationButKeepsTheRecord() = runBlocking {
        val config = mockk<DashPayConfig>(relaxed = true)
        var consolidation = "kept"
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } answers { consolidation }
        coEvery { config.set(DashPayConfig.MIXED_FUNDS_CONSOLIDATION, any()) } answers { consolidation = secondArg() }
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE) } returns ""
        var marker = formatInFlightMigration(
            InFlightMixedFundsMigration(
                MixedFundsMigrationAction.SHIELD,
                System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 1_000L,
                0L
            )
        )
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { marker }
        coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) } answers { marker = secondArg() }
        var acknowledgedAt: Long? = null
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT) } answers { acknowledgedAt }
        coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT, any()) } answers {
            acknowledgedAt = secondArg()
        }
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        coEvery { shield.ensureShieldedReady() } returns true
        coEvery { shield.pendingCoinJoinShieldLockCount() } returns 1
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CoinJoinFundsMigrationService(
            mockk(relaxed = true), shield, mockk(relaxed = true), mockk(relaxed = true), config,
            mockk(relaxed = true), scope, mockk(relaxed = true)
        )
        try {
            // Not yet seen: the automatic triggers present it.
            assertTrue(service.shouldPresentPrompt())
            service.acknowledgeUnconfirmedMigration()
            // Closed by the user: no longer re-presented on resume / startup ...
            assertFalse(service.shouldPresentPrompt())
            // ... but the record is intact: still pending, still blocking a fresh spend.
            assertNotNull(service.inFlightMigration())
            assertEquals("kept", consolidation)
            coVerify(exactly = 0) { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, "") }

            // Try again from Tools restarts the window: the new attempt is presented again.
            val current = parseInFlightMigration(marker)!!
            marker = formatInFlightMigration(current.copy(startedAtMillis = System.currentTimeMillis()))
            assertTrue(service.shouldPresentPrompt())

            // A definitive failure is always presented, never acknowledged away.
            marker = formatInFlightMigration(current.copy(failed = true))
            service.acknowledgeUnconfirmedMigration()
            assertTrue(service.shouldPresentPrompt())
        } finally {
            scope.cancel()
        }
    }
}
