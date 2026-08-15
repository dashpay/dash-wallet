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

import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Context
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [PlatformSynchronizationService.updateContactRequests] single-flight
 * guard must be RECOVERABLE: a pass that hangs inside a platform call holds
 * [updatingContacts] forever (its finally never runs), and before this fix
 * every later attempt logged "already running" and returned — contact sync
 * was dead until an app restart (observed live: "already running: None" with
 * the stage pinned at None because stage writes are preblock-gated).
 *
 * Contract under test: a caller that finds the guard held longer than
 * [PlatformSynchronizationService.CONTACT_SYNC_STALE_TAKEOVER_MS] cancels the
 * recorded owner and takes over; callers within the window still no-op.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformSyncContactGuardTest {

    private val ownId = Base58.encode(ByteArray(32) { 3 })

    private val wallet = mockk<Wallet>(relaxed = true) {
        every { context } returns Context(TestNet3Params.get())
    }
    private val walletApplication = mockk<de.schildbach.wallet.WalletApplication>(relaxed = true) {
        every { wallet } returns this@PlatformSyncContactGuardTest.wallet
    }
    private val identityData = mockk<BlockchainIdentityData>(relaxed = true) {
        every { creationState } returns IdentityCreationState.DONE
        every { username } returns "tester"
        every { userId } returns ownId
    }
    private val identityRepository = mockk<IdentityRepository>(relaxed = true) {
        every { hasBlockchainIdentity } returns true
    }
    private val contactRequests = mockk<org.dashj.platform.dashpay.ContactRequests> {
        every { get(any<String>(), any(), any(), any(), null) } returns emptyList()
    }
    private val platform = mockk<PlatformService>(relaxed = true) {
        every { hasApp("dashpay") } returns true
        every { contactRequests } returns this@PlatformSyncContactGuardTest.contactRequests
    }
    private val config = mockk<DashPayConfig>(relaxed = true) {
        // The post-claim hang point: a pass parked here holds the guard at a
        // suspension point, exactly like the live wedge.
        coEvery { get(DashPayConfig.FREQUENT_CONTACTS) } coAnswers { awaitCancellation() }
    }
    private val platformRepo = mockk<de.schildbach.wallet.ui.dashpay.PlatformRepo>(relaxed = true) {
        every { walletApplication } returns this@PlatformSyncContactGuardTest.walletApplication
    }

    private fun service() = PlatformSynchronizationService(
        platform = platform,
        platformRepo = platformRepo,
        analytics = mockk(relaxed = true),
        config = config,
        walletApplication = walletApplication,
        transactionMetadataProvider = mockk(relaxed = true),
        transactionMetadataChangeCacheDao = mockk(relaxed = true),
        transactionMetadataDocumentDao = mockk(relaxed = true),
        blockchainIdentityDataDao = mockk(relaxed = true) {
            coEvery { load() } returns identityData
        },
        dashPayProfileDao = mockk(relaxed = true),
        dashPayContactRequestDao = mockk(relaxed = true),
        dashPayConfig = mockk(relaxed = true),
        giftCardDao = mockk(relaxed = true),
        invitationsDao = mockk(relaxed = true),
        usernameRequestDao = mockk(relaxed = true),
        usernameVoteDao = mockk(relaxed = true),
        identityConfig = mockk(relaxed = true),
        topUpRepository = mockk(relaxed = true),
        identityRepository = identityRepository,
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

    @Test
    fun hungPass_blocksWithinWindow_isTakenOverWhenStale() = runTest {
        var now = 1L
        val svc = service()
        svc.contactSyncClock = { now }

        // Pass 1 claims the guard and hangs at the post-claim suspend point.
        val job1 = launch { svc.updateContactRequests(initialSync = true) }
        advanceUntilIdle()
        verify(exactly = 1) { identityRepository.updateIdentity() }
        assertTrue(job1.isActive)

        // Within the window: a second caller must no-op against the guard.
        svc.updateContactRequests(initialSync = true)
        verify(exactly = 1) { identityRepository.updateIdentity() }
        assertTrue(job1.isActive)

        // Past the window: the caller cancels the hung owner and takes over.
        now += PlatformSynchronizationService.CONTACT_SYNC_STALE_TAKEOVER_MS + 1
        val job3 = launch { svc.updateContactRequests(initialSync = true) }
        advanceUntilIdle()

        assertTrue("hung pass must be cancelled by the takeover", job1.isCancelled)
        verify(exactly = 2) { identityRepository.updateIdentity() }
        assertTrue("takeover pass runs (and parks at the same hang point)", job3.isActive)
        assertFalse(job3.isCancelled)

        job3.cancel()
    }
}
