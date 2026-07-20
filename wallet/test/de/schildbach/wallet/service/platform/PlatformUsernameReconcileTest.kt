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

import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dashj.platform.dashpay.UsernameStatus
import org.dashj.platform.dpp.document.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Host-JVM tests for [PlatformSynchronizationService]'s username status
 * RECONCILE pass (`reconcileUsernameStatus`, reached through
 * [PlatformSyncService.checkUsernameVotingStatus]): a username that
 * platform truth shows registered to this identity must self-correct the
 * local NOT_PRESENT status to CONFIRMED (the live test12345 case — the
 * restore worker ran before Drive indexed the domain document and nothing
 * ever re-checked), while negative/uncertain evidence must never mutate
 * local state.
 */
class PlatformUsernameReconcileTest {

    private val myUserId = "G2HnoKSdqpTzcfd1HcU1RYk3R7Zmrc7yPYExrS3bXiDf"
    private val otherUserId = "6XqBkTZTUnDMcgGvKzs5NRotZbAMBjKhJ4bQzKzXcCwr"

    private fun domainDocumentOwnedBy(ownerId: String): Document = mockk {
        every { data } returns mapOf("records" to mapOf("identity" to ownerId))
    }

    private fun identityData(
        creationState: IdentityCreationState = IdentityCreationState.USERNAME_REGISTERING,
        username: String? = "test12345",
        usernameSecondary: String? = null
    ) = BlockchainIdentityData(
        creationState,
        null,
        username,
        usernameSecondary,
        myUserId,
        false
    )

    private val platformRepo = mockk<PlatformRepo>(relaxed = true)
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)
    private val identityConfig = mockk<BlockchainIdentityConfig>(relaxed = true)
    private val dashPayProfileDao =
        mockk<de.schildbach.wallet.database.dao.DashPayProfileDao>(relaxed = true)

    private fun service() = PlatformSynchronizationService(
        platform = mockk(relaxed = true),
        platformRepo = platformRepo,
        analytics = mockk(relaxed = true),
        config = mockk(relaxed = true),
        walletApplication = mockk(relaxed = true),
        transactionMetadataProvider = mockk(relaxed = true),
        transactionMetadataChangeCacheDao = mockk(relaxed = true),
        transactionMetadataDocumentDao = mockk(relaxed = true),
        blockchainIdentityDataDao = mockk(relaxed = true),
        dashPayProfileDao = dashPayProfileDao,
        dashPayContactRequestDao = mockk(relaxed = true),
        dashPayConfig = mockk(relaxed = true),
        giftCardDao = mockk(relaxed = true),
        invitationsDao = mockk(relaxed = true),
        usernameRequestDao = mockk(relaxed = true),
        usernameVoteDao = mockk(relaxed = true),
        identityConfig = identityConfig,
        topUpRepository = mockk(relaxed = true),
        identityRepository = identityRepository,
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
        cutoverAutoCommitObserver = mockk(relaxed = true)
    )

    @Test
    fun reconcile_confirmsARegisteredUsernameFromPlatformTruth() = runBlocking {
        // The live case: creation stuck at USERNAME_REGISTERING with the
        // status never confirmed, while the name IS on platform under our
        // identity.
        val data = identityData()
        data.usernameStatus = UsernameStatus.NOT_PRESENT
        coEvery { identityConfig.load() } returns data
        every { platformRepo.getUsername("test12345") } returns
            Resource.success(domainDocumentOwnedBy(myUserId))
        coEvery { identityRepository.getLocalUserProfile() } returns
            DashPayProfile(myUserId, "")

        service().checkUsernameVotingStatus()

        assertEquals(UsernameStatus.CONFIRMED, data.usernameStatus)
        // The stuck state machine is completed — the name is on chain; a
        // "retry" from USERNAME_REGISTERING would double-register.
        assertEquals(IdentityCreationState.DONE_AND_DISMISS, data.creationState)
        coVerify(exactly = 1) { identityRepository.updateBlockchainIdentityData(data) }
        // The local profile row picks up the confirmed name.
        coVerify(exactly = 1) {
            dashPayProfileDao.insert(match<DashPayProfile> { it.username == "test12345" })
        }
    }

    @Test
    fun reconcile_confirmsTheSecondaryUsernameOfADualRegistration() = runBlocking {
        val data = identityData(username = "alice", usernameSecondary = "alice2")
        data.usernameStatus = UsernameStatus.CONFIRMED
        data.usernameSecondaryStatus = UsernameStatus.NOT_PRESENT
        coEvery { identityConfig.load() } returns data
        every { platformRepo.getUsername("alice2") } returns
            Resource.success(domainDocumentOwnedBy(myUserId))

        service().checkUsernameVotingStatus()

        assertEquals(UsernameStatus.CONFIRMED, data.usernameSecondaryStatus)
        coVerify(exactly = 1) { identityRepository.updateBlockchainIdentityData(data) }
    }

    @Test
    fun reconcile_neverActsOnANameOwnedByAnotherIdentity() = runBlocking {
        val data = identityData()
        data.usernameStatus = UsernameStatus.NOT_PRESENT
        coEvery { identityConfig.load() } returns data
        every { platformRepo.getUsername("test12345") } returns
            Resource.success(domainDocumentOwnedBy(otherUserId))

        service().checkUsernameVotingStatus()

        assertEquals(UsernameStatus.NOT_PRESENT, data.usernameStatus)
        assertNotEquals(IdentityCreationState.DONE_AND_DISMISS, data.creationState)
        coVerify(exactly = 0) { identityRepository.updateBlockchainIdentityData(any()) }
    }

    @Test
    fun reconcile_lookupFailuresAreNeverEvidence() = runBlocking {
        // Network/platform errors must neither mutate local state nor
        // escape the sync loop.
        val data = identityData()
        data.usernameStatus = UsernameStatus.NOT_PRESENT
        coEvery { identityConfig.load() } returns data
        every { platformRepo.getUsername(any()) } throws RuntimeException("platform down")

        service().checkUsernameVotingStatus()

        assertEquals(UsernameStatus.NOT_PRESENT, data.usernameStatus)
        coVerify(exactly = 0) { identityRepository.updateBlockchainIdentityData(any()) }
    }

    @Test
    fun reconcile_leavesTheVotingStateToTheVotingChecker() = runBlocking {
        // A contested name mid-vote is NOT confirmable from a domain
        // document — checkVotingStatus owns that state.
        val data = identityData(creationState = IdentityCreationState.VOTING)
        data.usernameStatus = UsernameStatus.NOT_PRESENT
        coEvery { identityConfig.load() } returns data
        every { platformRepo.getUsername("test12345") } returns
            Resource.success(domainDocumentOwnedBy(myUserId))
        every { platformRepo.getVoteContenders("test12345") } returns mockk {
            every { winner } returns java.util.Optional.empty()
        }

        service().checkUsernameVotingStatus()

        assertEquals(UsernameStatus.NOT_PRESENT, data.usernameStatus)
        assertEquals(IdentityCreationState.VOTING, data.creationState)
        coVerify(exactly = 0) { identityRepository.updateBlockchainIdentityData(any()) }
    }
}
