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
import de.schildbach.wallet.database.entity.IdentityRecordKey
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dashpay.UsernameStatus
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.voting.BlockInfo
import org.dashj.platform.dpp.voting.Contenders
import org.dashj.platform.dpp.voting.ContestedDocumentVotePollWinnerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Optional

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

    private fun copyOf(record: BlockchainIdentityData) = BlockchainIdentityData(
        record.creationState,
        record.creationStateErrorMessage,
        record.username,
        record.usernameSecondary,
        record.userId,
        record.restoring,
        usernameRequested = record.usernameRequested,
        votingPeriodStart = record.votingPeriodStart
    ).also {
        it.usernameStatus = record.usernameStatus
        it.usernameSecondaryStatus = record.usernameSecondaryStatus
    }

    /** [data] is the stored record; the conditional write finds it unchanged. */
    private fun givenStored(data: BlockchainIdentityData) {
        coEvery { identityConfig.load() } returns data
        coEvery { identityRepository.updateBlockchainIdentityDataIfUnchanged(any(), any()) } returns true
    }

    private val transparentUsernameCreation =
        mockk<de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation>(relaxed = true)

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
        shieldedTransferExecutor = mockk(relaxed = true),
        contactRequestNotificationService = mockk(relaxed = true),
        dashPaySyncStatus = de.schildbach.wallet.service.DashPaySyncStatus(),
        transparentUsernameCreation = javax.inject.Provider { transparentUsernameCreation }
    )

    @Test
    fun reconcile_confirmsARegisteredUsernameFromPlatformTruth() = runBlocking {
        // The live case: creation stuck at USERNAME_REGISTERING with the
        // status never confirmed, while the name IS on platform under our
        // identity.
        val data = identityData()
        data.usernameStatus = UsernameStatus.NOT_PRESENT
        givenStored(data)
        every { platformRepo.getUsername("test12345") } returns
            Resource.success(domainDocumentOwnedBy(myUserId))
        coEvery { identityRepository.getLocalUserProfile() } returns
            DashPayProfile(myUserId, "")

        service().checkUsernameVotingStatus()

        assertEquals(UsernameStatus.CONFIRMED, data.usernameStatus)
        // The stuck state machine is completed — the name is on chain; a
        // "retry" from USERNAME_REGISTERING would double-register.
        assertEquals(IdentityCreationState.DONE_AND_DISMISS, data.creationState)
        coVerify(exactly = 1) { identityRepository.updateBlockchainIdentityDataIfUnchanged(data, any()) }
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
        givenStored(data)
        every { platformRepo.getUsername("alice2") } returns
            Resource.success(domainDocumentOwnedBy(myUserId))

        service().checkUsernameVotingStatus()

        assertEquals(UsernameStatus.CONFIRMED, data.usernameSecondaryStatus)
        coVerify(exactly = 1) { identityRepository.updateBlockchainIdentityDataIfUnchanged(data, any()) }
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
        coVerify(exactly = 0) { identityRepository.updateBlockchainIdentityDataIfUnchanged(any(), any()) }
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
        coVerify(exactly = 0) { identityRepository.updateBlockchainIdentityDataIfUnchanged(any(), any()) }
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
        coVerify(exactly = 0) { identityRepository.updateBlockchainIdentityDataIfUnchanged(any(), any()) }
    }

    @Test
    fun theSyncTicker_reconcilesAnInterruptedNameOnlyRetry_onEveryPass() = runBlocking {
        // discovery runs once per service lifetime once an identity exists, so a reconcile it
        // deferred (ownership unreadable) must be retried by the recurring pass
        val service = service()
        try {
            service.initSync(runFirstUpdateBlocking = false)

            coVerify(timeout = 5_000, atLeast = 1) { transparentUsernameCreation.reconcileInterruptedNameRetry() }
        } finally {
            service.stopSync()
        }
    }

    // ── a lookup that started before a name-only retry replaced the name ──

    /** The durable record: every load reads a copy of it, every whole-record write replaces it. */
    private inner class Store(initial: BlockchainIdentityData) {
        @Volatile var record: BlockchainIdentityData = initial
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val writes = java.util.concurrent.atomic.AtomicInteger()

        init {
            coEvery { identityConfig.load() } answers { copyOf(record) }
            coEvery { identityRepository.updateBlockchainIdentityDataIfUnchanged(any(), any()) } answers {
                attempts.incrementAndGet()
                // the compare and the write are one store edit (see IdentityConditionalWriteTest)
                synchronized(this@Store) {
                    if (IdentityRecordKey.of(record) != secondArg<IdentityRecordKey>()) return@answers false
                    record = copyOf(firstArg())
                    writes.incrementAndGet()
                    true
                }
            }
        }

        /** Waits until the lookup's conditional write has run. */
        fun awaitWriteAttempt() {
            val deadline = System.currentTimeMillis() + 5_000
            while (attempts.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
        }
    }

    /** What beginNameOnlyRetry leaves on the record for a replacement [name]. */
    private fun replacementAccepted(name: String, error: String? = null) =
        identityData(creationState = IdentityCreationState.USERNAME_REGISTERING, username = name).also {
            it.creationStateErrorMessage = error
        }

    private fun votingFor(name: String) = identityData(creationState = IdentityCreationState.VOTING, username = name)

    /** Runs [checkUsernameVotingStatus] with the vote lookup paused; [whilePaused] runs during it. */
    private fun staleVoteLookup(outcome: ContestedDocumentVotePollWinnerInfo, whilePaused: () -> Unit) = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        every { platformRepo.getUsername(any()) } returns Resource.success(null)
        every { platformRepo.getVoteContenders("alice") } answers {
            started.complete(Unit)
            release.await()
            Contenders(Optional.of(Pair(outcome, mockk<BlockInfo>())), mapOf(), 0, 0)
        }
        val check = launch(Dispatchers.IO) { service().checkUsernameVotingStatus() }
        started.await()
        whilePaused()
        release.countDown()
        check.join()
    }

    private fun finishedVote(isLocked: Boolean): ContestedDocumentVotePollWinnerInfo = mockk {
        every { this@mockk.isLocked } returns isLocked
        every { noWinner } returns false
        every { isWinner(any()) } returns false
    }

    @Test
    fun aStaleLockedVoteResult_neverPutsTheOldNameBackOverAnAcceptedReplacement() {
        val store = Store(votingFor("alice"))

        staleVoteLookup(finishedVote(isLocked = true)) { store.record = replacementAccepted("alice3") }
        store.awaitWriteAttempt()

        assertEquals("alice3", store.record.username)
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, store.record.creationState)
        assertNull(store.record.usernameRequested)
        assertEquals("the stale write is skipped", 0, store.writes.get())
    }

    @Test
    fun aStaleLostVoteResult_neverErasesTheReplacementsRecordedError() {
        val error = "name-only retry: DuplicateUniqueIndexError"
        val store = Store(votingFor("alice"))

        staleVoteLookup(finishedVote(isLocked = false)) { store.record = replacementAccepted("alice3", error) }
        store.awaitWriteAttempt()

        assertEquals("alice3", store.record.username)
        assertEquals(error, store.record.creationStateErrorMessage)
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, store.record.creationState)
        assertEquals(0, store.writes.get())
    }

    @Test
    fun aVoteResultForTheUnchangedRecord_isStillWritten() {
        val store = Store(votingFor("alice"))

        staleVoteLookup(finishedVote(isLocked = true)) { }
        store.awaitWriteAttempt()

        assertEquals(UsernameRequestStatus.LOCKED, store.record.usernameRequested)
        assertEquals(1, store.writes.get())
    }

    @Test
    fun aWonVoteWithItsDomainDocument_writesApprovedAndDoneOnce_thenUpdatesTheProfile() = runBlocking {
        // the result and the completion are one conditional write: as two writes against the
        // same loaded key, whichever landed second was refused, and the profile update with it
        val store = Store(votingFor("alice"))
        val identity = mockk<org.dashj.platform.dpp.identity.Identity> {
            every { id } returns org.dashj.platform.dpp.identifier.Identifier.from(myUserId)
        }
        coEvery { identityConfig.load() } answers { copyOf(store.record).also { it.identity = identity } }
        // The first write is slow to land: a second write racing it (the old split result and
        // completion writes) lands first, and the first is then refused.
        val secondAttempt = java.util.concurrent.CountDownLatch(1)
        val attemptNo = java.util.concurrent.atomic.AtomicInteger()
        coEvery { identityRepository.updateBlockchainIdentityDataIfUnchanged(any(), any()) } answers {
            store.attempts.incrementAndGet()
            if (attemptNo.incrementAndGet() == 1) {
                secondAttempt.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                secondAttempt.countDown()
            }
            synchronized(store) {
                if (IdentityRecordKey.of(store.record) != secondArg<IdentityRecordKey>()) return@answers false
                store.record = copyOf(firstArg())
                store.writes.incrementAndGet()
                true
            }
        }
        every { platformRepo.getUsername("alice") } returns Resource.success(domainDocumentOwnedBy(myUserId))
        val won: ContestedDocumentVotePollWinnerInfo = mockk {
            every { isLocked } returns false
            every { noWinner } returns false
            every { isWinner(any()) } returns true
        }
        every { platformRepo.getVoteContenders("alice") } returns
            Contenders(Optional.of(Pair(won, mockk<BlockInfo>())), mapOf(), 0, 0)
        coEvery { identityRepository.getLocalUserProfile() } returns DashPayProfile(myUserId, "alice2instant")

        service().checkUsernameVotingStatus()
        store.awaitWriteAttempt()
        coVerify(timeout = 5_000) { dashPayProfileDao.insert(match { it.username == "alice" }) }

        assertEquals(UsernameRequestStatus.APPROVED, store.record.usernameRequested)
        assertEquals(IdentityCreationState.DONE, store.record.creationState)
        // (the registration reconcile that follows may write the DONE record once more)
        assertEquals("no write refused", store.attempts.get(), store.writes.get())
    }

    @Test
    fun aStaleReconcile_neverPutsTheOldNameBackOverAnAcceptedReplacement() = runBlocking {
        val stale = identityData(username = "alice").also { it.usernameStatus = UsernameStatus.NOT_PRESENT }
        val store = Store(stale)
        val started = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        every { platformRepo.getUsername("alice") } answers {
            started.complete(Unit)
            release.await()
            Resource.success(domainDocumentOwnedBy(myUserId))
        }
        val check = launch(Dispatchers.IO) { service().checkUsernameVotingStatus() }
        started.await()
        store.record = replacementAccepted("alice3")
        release.countDown()
        check.join()

        assertEquals("alice3", store.record.username)
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, store.record.creationState)
        assertEquals(0, store.writes.get())
    }
}
