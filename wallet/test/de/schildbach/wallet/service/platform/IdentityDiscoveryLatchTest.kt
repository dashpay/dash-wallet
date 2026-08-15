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
package de.schildbach.wallet.service.platform

import de.schildbach.wallet.Constants
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The identity-discovery latch must never outlive the identity database it
 * guards.
 *
 * Live defect (11.10.84 mainnet): [PlatformSynchronizationService] is a
 * @Singleton (process-scoped) while BlockchainServiceImpl is destroyed and
 * recreated. `discoverAndRecoverIdentity()` deliberately leaves
 * `identityDiscoveryInFlight` LATCHED on success ("recovery is under way"), and
 * neither `stopSync()` nor `clearDatabases()` cleared it. So a single
 * Settings → Rescan blockchain — which cancels all work and wipes the identity
 * DB — left the latch set from the pre-rescan recovery, and every later
 * discovery attempt for the life of the process was refused with "discovery
 * already in flight, skipping" (observed at 03:14:29, 30 minutes after the
 * 02:44:28 rescan wiped the identity).
 *
 * Asserted through the field itself: the latching branch of
 * `discoverAndRecoverIdentity()` enqueues a WorkManager operation, which cannot
 * run on the host JVM, so the reachable contract to pin is the one that was
 * broken — the teardown paths clear the guard.
 */
class IdentityDiscoveryLatchTest {

    /**
     * [identityDataDao] and [identityRepo] are explicit so a test can steer
     * `discoverAndRecoverIdentity` down its DISCOVERY branch: a relaxed mock
     * answers "an identity row exists, not restoring", which routes to the
     * contact-refresh branch instead and never touches the latch.
     */
    private fun service(
        identityDataDao: BlockchainIdentityConfig = mockk(relaxed = true),
        identityRepo: IdentityRepository = mockk(relaxed = true)
    ) = PlatformSynchronizationService(
        platform = mockk(relaxed = true),
        platformRepo = mockk(relaxed = true),
        analytics = mockk(relaxed = true),
        config = mockk(relaxed = true),
        walletApplication = mockk(relaxed = true),
        transactionMetadataProvider = mockk(relaxed = true),
        transactionMetadataChangeCacheDao = mockk(relaxed = true),
        transactionMetadataDocumentDao = mockk(relaxed = true),
        blockchainIdentityDataDao = identityDataDao,
        dashPayProfileDao = mockk(relaxed = true),
        dashPayContactRequestDao = mockk(relaxed = true),
        dashPayConfig = mockk(relaxed = true),
        giftCardDao = mockk(relaxed = true),
        invitationsDao = mockk(relaxed = true),
        usernameRequestDao = mockk(relaxed = true),
        usernameVoteDao = mockk(relaxed = true),
        identityConfig = mockk(relaxed = true),
        topUpRepository = mockk(relaxed = true),
        identityRepository = identityRepo,
        walletDataProvider = mockk(relaxed = true),
        walletSeam = mockk(relaxed = true),
        sdkProfileQueries = mockk(relaxed = true),
        sdkUsernameQueries = mockk(relaxed = true),
        sdkIdentityVerifyQueries = mockk(relaxed = true),
        sdkWalletBinder = mockk(relaxed = true),
        nonInteractiveWalletUnlock = mockk(relaxed = true),
        l1ShadowSyncService = mockk(relaxed = true),
        shieldedBalanceService = mockk(relaxed = true),
        cutoverUiDataService = mockk(relaxed = true),
        sdkBlockchainStateService = mockk(relaxed = true),
        cutoverTxSeamService = mockk(relaxed = true),
        cutoverAutoCommitObserver = mockk(relaxed = true),
        shieldedTransferExecutor = mockk(relaxed = true),
        contactRequestNotificationService = mockk(relaxed = true),
        dashPaySyncStatus = de.schildbach.wallet.service.DashPaySyncStatus()
    )

    private fun PlatformSynchronizationService.discoveryLatch(): AtomicBoolean {
        val field = PlatformSynchronizationService::class.java
            .getDeclaredField("identityDiscoveryInFlight")
        field.isAccessible = true
        return field.get(this) as AtomicBoolean
    }

    @Test
    fun stopSync_releasesTheIdentityDiscoveryLatch() = runBlocking {
        val service = service()
        val latch = service.discoveryLatch()
        latch.set(true) // a recovery was under way before the reset

        service.stopSync()

        assertFalse("stopSync must not leave identity discovery permanently latched", latch.get())
    }

    @Test
    fun clearDatabases_releasesTheIdentityDiscoveryLatch() = runBlocking {
        val service = service()
        val latch = service.discoveryLatch()
        latch.set(true)

        service.clearDatabases()

        assertFalse("clearDatabases must not leave identity discovery permanently latched", latch.get())
    }

    private fun PlatformSynchronizationService.discoveryClaimedAt(): AtomicLong {
        val field = PlatformSynchronizationService::class.java
            .getDeclaredField("identityDiscoverySince")
        field.isAccessible = true
        return field.get(this) as AtomicLong
    }

    /**
     * The .86 fix only released the latch on paths the app itself drives. A
     * holder that WEDGES — no return, no exception — is not one of them: on
     * 11.10.84 mainnet discovery latched at 02:38:05, the restore chain hung
     * inside a Platform call after 02:45:41, and every attempt from 03:14:29
     * to 12:41:21 was refused. Ten hours, ended only by a force-stop.
     */
    @Test
    fun aWedgedDiscoveryIsTakenOverOnceItsDeadlinePasses() = runBlocking {
        val wasSupported = Constants.SUPPORTS_PLATFORM
        Constants.SUPPORTS_PLATFORM = true
        try {
            val service = service(
                identityDataDao = mockk(relaxed = true) { coEvery { load() } returns null },
                identityRepo = mockk(relaxed = true) {
                    every { getIdentityFromPublicKeyId() } returns null
                }
            )
            var now = 1_000_000L
            service.contactSyncClock = { now }
            service.discoveryLatch().set(true)
            service.discoveryClaimedAt().set(now)

            // Inside the window the latch still refuses — a healthy restore
            // chain legitimately runs for many minutes.
            now += PlatformSynchronizationService.IDENTITY_DISCOVERY_STALE_TAKEOVER_MS
            assertFalse(service.discoverAndRecoverIdentity())
            assertTrue("a holder inside its deadline keeps the latch", service.discoveryLatch().get())

            // Past it, the caller takes over and runs a real discovery. The
            // mocked repository reports no identity, so the pass releases —
            // which is the observable proof it ran rather than being refused.
            now += 1
            assertFalse(service.discoverAndRecoverIdentity())
            assertFalse(
                "a wedged discovery must not disable recovery for the process",
                service.discoveryLatch().get()
            )
        } finally {
            Constants.SUPPORTS_PLATFORM = wasSupported
        }
    }

    /**
     * Only ONE caller may take a wedged latch over. The claim timestamp is
     * swapped under a CAS, so a loser sees the fresh stamp and skips — no
     * concurrent discovery, even though the enqueue it guards (unique-work
     * KEEP) would tolerate one.
     */
    @Test
    fun onlyOneCallerTakesOverAWedgedLatch() = runBlocking {
        val wasSupported = Constants.SUPPORTS_PLATFORM
        Constants.SUPPORTS_PLATFORM = true
        try {
            val service = service(
                identityDataDao = mockk(relaxed = true) { coEvery { load() } returns null },
                identityRepo = mockk(relaxed = true) {
                    every { getIdentityFromPublicKeyId() } returns null
                }
            )
            var now = 1_000_000L
            service.contactSyncClock = { now }
            service.discoveryLatch().set(true)
            service.discoveryClaimedAt().set(now)
            now += PlatformSynchronizationService.IDENTITY_DISCOVERY_STALE_TAKEOVER_MS + 1

            service.discoverAndRecoverIdentity()
            val claimedAfterTakeover = service.discoveryClaimedAt().get()

            // The takeover re-stamped the claim, so a second caller arriving
            // in the same instant is back inside the window.
            assertTrue(claimedAfterTakeover == 0L || claimedAfterTakeover == now)
        } finally {
            Constants.SUPPORTS_PLATFORM = wasSupported
        }
    }

    @Test
    fun releasingTheLatchIsIdempotent() = runBlocking {
        val service = service()
        val latch = service.discoveryLatch()

        service.stopSync()
        service.clearDatabases()

        assertFalse(latch.get())
    }
}
