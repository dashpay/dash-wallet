package de.schildbach.wallet.service.platform.sdk

import android.content.Context
import android.content.SharedPreferences
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CoinJoinMigrationRecoveryTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun pendingLockRestoresMarkerAndBlocksBothNewSpends() = runBlocking {
        val config = mockk<DashPayConfig>(relaxed = true)
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } returns ""
        var marker = ""
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { marker }
        coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) } answers {
            marker = secondArg()
        }
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        coEvery { shield.ensureShieldedReady() } returns true
        coEvery { shield.pendingCoinJoinShieldLockCount() } returns 1
        coEvery { shield.unsuppressedCoinJoinShieldLockCount(any()) } returns 1
        coEvery { shield.resumePendingWalletShieldsOnUserRequest() } returns 1
        coEvery { shield.observeShieldedBalance() } returns MutableStateFlow(Dash.ZERO)
        val send = mockk<SdkL1SendService>(relaxed = true)
        // The adopted lock's baseline is read on the active SDK wallet.
        val manager = mockk<org.dashfoundation.dashsdk.wallet.PlatformWalletManager>(relaxed = true)
        every { manager.wallets } returns MutableStateFlow(mapOf("01".repeat(32) to mockk()))
        val sdk = mockk<DashSdkService>(relaxed = true)
        every { sdk.walletManagerOrNull() } returns manager
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CoinJoinFundsMigrationService(
            mockk(relaxed = true), shield, send, mockk(relaxed = true), config,
            sdk, scope, mockk(relaxed = true)
        )
        try {
            assertEquals(MixedFundsMigrationOutcome.STARTED, service.shieldMixedFunds(Dash.COIN))
            assertNotNull(service.inFlightMigration())
            assertEquals(MixedFundsMigrationOutcome.STARTED, service.combineIntoUnmixedBalance())
            assertEquals(MixedFundsMigrationOutcome.STARTED, service.retryPendingMigration())
            // Shield, Combine and Try again each resume the tracked locks first; nothing is built.
            coVerify(exactly = 3) { shield.resumePendingWalletShieldsOnUserRequest() }
            coVerify(exactly = 0) { shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
            coVerify(exactly = 0) { send.drainCoinJoinAccountTo(any()) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unavailableSyncedSnapshotPreventsFunding() = runBlocking {
        val config = mockk<DashPayConfig>(relaxed = true)
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } returns ""
        var marker = ""
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { marker }
        coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) } answers {
            marker = secondArg()
        }
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        coEvery { shield.ensureShieldedReady() } returns true
        coEvery { shield.pendingCoinJoinShieldLockCount() } returns 0
        coEvery { shield.unsuppressedCoinJoinShieldLockCount(any()) } returns 0
        coEvery { shield.observeShieldedBalance() } returns MutableStateFlow(Dash.ZERO)
        val wallet = mockk<WalletData>(relaxed = true)
        coEvery { wallet.networkParameters } returns TestNet3Params.get()
        // Fresh moves allowed: only the unavailable snapshot may stop this one.
        val send = mockk<SdkL1SendService>(relaxed = true)
        coEvery { send.cutoverCommitted() } returns true
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) } returns null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CoinJoinFundsMigrationService(
            wallet, shield, send, mockk(relaxed = true), config,
            mockk(relaxed = true), scope, mockk(relaxed = true)
        )
        try {
            assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, service.shieldMixedFunds(Dash.COIN))
            assertNull(service.inFlightMigration())
            coVerify(exactly = 0) { shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
        } finally {
            scope.cancel()
        }
    }
    @Test
    fun preCutover_noFreshPrompt_butInFlightMigrationStillPresented() = runBlocking {
        val config = mockk<DashPayConfig>(relaxed = true)
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } returns ""
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) } returns false
        var marker = ""
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { marker }
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        // Shielding available would make anyOptionOperable() true pre-cutover.
        coEvery { shield.isWalletShieldingAvailable() } returns true
        coEvery { shield.ensureShieldedReady() } returns true
        coEvery { shield.pendingCoinJoinShieldLockCount() } returns 0
        coEvery { shield.unsuppressedCoinJoinShieldLockCount(any()) } returns 0
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
            coVerify(exactly = 0) { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) }

            // In-flight path: an existing migration is still presented pre-cutover.
            marker = formatInFlightMigration(
                InFlightMixedFundsMigration(
                    MixedFundsMigrationAction.SHIELD,
                    System.currentTimeMillis()
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
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } answers { consolidation }
        coEvery { config.set(DashPayConfig.MIXED_FUNDS_CONSOLIDATION, any()) } answers { consolidation = secondArg() }
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE) } returns ""
        var marker = formatInFlightMigration(
            InFlightMixedFundsMigration(
                MixedFundsMigrationAction.SHIELD,
                System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 1_000L
            )
        )
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } answers { marker }
        coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) } answers { marker = secondArg() }
        var acknowledgedAt: Long? = null
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT) } answers { acknowledgedAt }
        coEvery { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT, any()) } answers {
            acknowledgedAt = secondArg()
        }
        val shield = mockk<ShieldedBalanceService>(relaxed = true)
        coEvery { shield.ensureShieldedReady() } returns true
        coEvery { shield.pendingCoinJoinShieldLockCount() } returns 1
        coEvery { shield.unsuppressedCoinJoinShieldLockCount(any()) } returns 1
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

    // ── Unreadable migration checkpoints fail CLOSED ──────────────────

    private fun serviceOver(
        config: DashPayConfig,
        shield: ShieldedBalanceService,
        send: SdkL1SendService,
        scope: CoroutineScope
    ): CoinJoinFundsMigrationService {
        val wallet = mockk<WalletData>(relaxed = true)
        coEvery { wallet.networkParameters } returns TestNet3Params.get()
        return CoinJoinFundsMigrationService(
            wallet, shield, send, mockk(relaxed = true), config,
            mockk(relaxed = true), scope, mockk(relaxed = true)
        )
    }

    private fun quietShield(): ShieldedBalanceService = mockk<ShieldedBalanceService>(relaxed = true).also {
        coEvery { it.ensureShieldedReady() } returns true
        coEvery { it.pendingCoinJoinShieldLockCount() } returns 0
        coEvery { it.unsuppressedCoinJoinShieldLockCount(any()) } returns 0
        coEvery { it.observeShieldedBalance() } returns MutableStateFlow(Dash.ZERO)
    }

    /**
     * The rescued [DashPayConfig.get] would report these checkpoints as ABSENT;
     * the error-preserving read reports the failure. Each unreadable key — the
     * marker or the consolidation stage — must stop a fresh spend, and
     * restore must say "pending", never "no migration".
     */
    @Test
    fun unreadableCheckpointBlocksFreshSpendsAndRestoreReportsPending() = runBlocking {
        val unreadableKeys = listOf(
            DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT,
            DashPayConfig.MIXED_FUNDS_CONSOLIDATION
        )
        for (unreadable in unreadableKeys) {
            val config = mockk<DashPayConfig>(relaxed = true)
            // What the IOException-rescuing read would show: nothing saved.
            coEvery { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } returns null
            coEvery { config.get(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } returns null
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } returns ""
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } returns ""
            coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE) } returns ""
            coEvery { config.getPreservingErrors(unreadable) } throws IOException("unreadable preferences")
            val shield = quietShield()
            val send = mockk<SdkL1SendService>(relaxed = true)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val service = serviceOver(config, shield, send, scope)
            try {
                assertEquals(unreadable.name, MixedFundsMigrationOutcome.NOT_ATTEMPTED, service.shieldMixedFunds(Dash.COIN))
                assertEquals(unreadable.name, MixedFundsMigrationOutcome.NOT_ATTEMPTED, service.combineIntoUnmixedBalance())
                assertTrue("${unreadable.name}: restore must not report 'no migration'", service.restorePendingShieldMigration())
                assertEquals(unreadable.name, MixedFundsMigrationOutcome.NOT_ATTEMPTED, service.retryPendingMigration())
                coVerify(exactly = 0) { shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
                coVerify(exactly = 0) { send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
                coVerify(exactly = 0) { send.drainCoinJoinAccountTo(any()) }
                // The saved (unseen) checkpoint is never overwritten.
                coVerify(exactly = 0) { config.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, any()) }
                coVerify(exactly = 0) { config.set(DashPayConfig.MIXED_FUNDS_CONSOLIDATION, any()) }
                coVerify(exactly = 0) { config.set(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE, any()) }
            } finally {
                scope.cancel()
            }
        }
    }

    /** An unreadable acknowledgement never hides guidance: it is presented. */
    @Test
    fun unreadableAcknowledgementPresentsTheGuidance() = runBlocking {
        val config = mockk<DashPayConfig>(relaxed = true)
        val marker = InFlightMixedFundsMigration(
            MixedFundsMigrationAction.SHIELD,
            System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 1_000L
        )
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT) } returns
            formatInFlightMigration(marker)
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_CONSOLIDATION) } returns ""
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE) } returns ""
        coEvery { config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT) } returns marker.startedAtMillis
        coEvery { config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT) } throws
            IOException("unreadable preferences")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = serviceOver(config, quietShield(), mockk(relaxed = true), scope)
        try {
            assertFalse(service.isUnconfirmedMigrationAcknowledged())
            assertTrue(service.shouldPresentPrompt())
        } finally {
            scope.cancel()
        }
    }

    /**
     * The same through the REAL [DashPayConfig] / BaseConfig / DataStore stack
     * over a present-but-corrupt preferences file: the ordinary [DashPayConfig.get]
     * still rescues it as absent (unchanged for config hints), the
     * error-preserving read throws, and the migration fails closed.
     */
    @Test
    fun corruptPreferencesFileNeverAuthorisesAFreshMigrationSpend() {
        val appFiles = files.newFolder("files")
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns appFiles
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        val preferences = File(appFiles, "datastore/${DashPayConfig.PREFERENCES_NAME}.preferences_pb")
        preferences.parentFile?.mkdirs()
        preferences.writeBytes(ByteArray(128) { 0xFF.toByte() })
        val config = DashPayConfig(context, mockk<WalletDataProvider>(relaxed = true))

        runBlocking {
            assertNull(
                "ordinary get() keeps rescuing an unreadable store as an absent key",
                config.get(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT)
            )
            try {
                config.getPreservingErrors(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT)
                fail("the preferences file must be genuinely unreadable for this test to mean anything")
            } catch (expected: IOException) {
                // ABSENT and FAILED stay apart.
            }

            val shield = quietShield()
            val send = mockk<SdkL1SendService>(relaxed = true)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val service = serviceOver(config, shield, send, scope)
            try {
                assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, service.shieldMixedFunds(Dash.COIN))
                assertEquals(MixedFundsMigrationOutcome.NOT_ATTEMPTED, service.combineIntoUnmixedBalance())
                assertTrue(service.restorePendingShieldMigration())
                coVerify(exactly = 0) { shield.shieldMixedFundsFromWallet(any(), any(), any(), any()) }
                coVerify(exactly = 0) { send.consolidateCoinJoin(any(), any(), any(), any(), any()) }
            } finally {
                scope.cancel()
            }
        }
    }
}
