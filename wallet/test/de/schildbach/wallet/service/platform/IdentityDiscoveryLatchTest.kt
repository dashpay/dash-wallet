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

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

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

    private fun service() = PlatformSynchronizationService(
        platform = mockk(relaxed = true),
        platformRepo = mockk(relaxed = true),
        analytics = mockk(relaxed = true),
        config = mockk(relaxed = true),
        walletApplication = mockk(relaxed = true),
        transactionMetadataProvider = mockk(relaxed = true),
        transactionMetadataChangeCacheDao = mockk(relaxed = true),
        transactionMetadataDocumentDao = mockk(relaxed = true),
        blockchainIdentityDataDao = mockk(relaxed = true),
        dashPayProfileDao = mockk(relaxed = true),
        dashPayContactRequestDao = mockk(relaxed = true),
        dashPayConfig = mockk(relaxed = true),
        giftCardDao = mockk(relaxed = true),
        invitationsDao = mockk(relaxed = true),
        usernameRequestDao = mockk(relaxed = true),
        usernameVoteDao = mockk(relaxed = true),
        identityConfig = mockk(relaxed = true),
        topUpRepository = mockk(relaxed = true),
        identityRepository = mockk(relaxed = true),
        walletDataProvider = mockk(relaxed = true),
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
        contactRequestNotificationService = mockk(relaxed = true)
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

    @Test
    fun releasingTheLatchIsIdempotent() = runBlocking {
        val service = service()
        val latch = service.discoveryLatch()

        service.stopSync()
        service.clearDatabases()

        assertFalse(latch.get())
    }
}
