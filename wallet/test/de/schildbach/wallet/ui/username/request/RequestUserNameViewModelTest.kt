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
package de.schildbach.wallet.ui.username.request

import android.app.Application
import android.content.Intent
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.CREATION_STATE
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.IDENTITY_ID
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.USERNAME
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.USERNAME_REQUESTED
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.service.platform.ContestedUsernameFees
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.username.UsernameType
import de.schildbach.wallet.util.viewModels.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.voting.BlockInfo
import org.dashj.platform.dpp.voting.ContenderWithSerializedDocument
import org.dashj.platform.dpp.voting.Contenders
import org.dashj.platform.dpp.voting.ContestedDocumentVotePollWinnerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.bitcoinj.core.Coin
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The username availability states decide whether the user may go on to register a name, so each
 * outcome of the name lookup and the contested-name vote must map to the right flags - most of all,
 * a name nobody can register must never be reported as available.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
// a plain Application: nothing here needs the real one, and booting it would drag in Hilt
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class RequestUserNameViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val username = "dashuser"
    private val otherIdentity = Identifier.from(ByteArray(32) { 1 })

    private lateinit var identityConfig: BlockchainIdentityConfig
    private lateinit var platformRepo: PlatformRepo
    private lateinit var walletApplication: WalletApplication
    private lateinit var viewModel: RequestUserNameViewModel
    private val balance = MutableStateFlow(Coin.ZERO)

    @Before
    fun setUp() {
        ContestedUsernameFees.reset()
        identityConfig = mockk(relaxed = true)
        every { identityConfig.observe(IDENTITY_ID) } returns emptyFlow()
        coEvery { identityConfig.get(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK) } returns null

        platformRepo = mockk(relaxed = true)
        walletApplication = mockk(relaxed = true)

        val coinJoinConfig = mockk<CoinJoinConfig>(relaxed = true)
        every { coinJoinConfig.observeMode() } returns flowOf(CoinJoinMode.NONE)
        val walletData = mockk<WalletDataProvider>(relaxed = true)
        every { walletData.observeBalance(any()) } returns balance

        viewModel = RequestUserNameViewModel(
            walletApplication,
            identityConfig,
            walletData,
            platformRepo,
            mockk<UsernameRequestDao>(relaxed = true),
            coinJoinConfig,
            mockk<AnalyticsService>(relaxed = true),
            mockk<TopUpRepository>(relaxed = true)
        )
    }

    // --- checkUsername ---

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        ContestedUsernameFees.reset()
    }

    @Test
    fun reducedFee_recomputesAffordabilityWithoutLosingAvailability() = runTest {
        balance.value = Coin.parseCoin("0.20")
        runCurrent()
        withTimeout(5_000) { viewModel.walletBalance.first { it == balance.value } }
        viewModel.checkUsernameValid(username, UsernameType.Primary)
        nameLookupReturns(null)
        every { platformRepo.getVoteContendersOrNull(username) } returns contenders()
        assertFalse(checkUsername().enoughBalance)

        ContestedUsernameFees.updateProtocolVersion(14)
        val state = withTimeout(5_000) { viewModel.uiState.first { it.feeProtocolVersion == 14 } }

        assertTrue(state.enoughBalance)
        assertTrue(viewModel.hasAvailableResultFor(username))
        assertEquals(username, state.checkedUsername)

        balance.value = Coin.parseCoin("0.10")
        withTimeout(5_000) { viewModel.uiState.first { !it.enoughBalance } }
        assertTrue(viewModel.hasAvailableResultFor(username))
    }

    @Test
    fun checkUsername_lookupFails_isNotReportedAsChecked() {
        every { platformRepo.getUsername(username) } returns Resource.error("DAPI unavailable", null)

        val state = checkUsername()

        assertFalse("a failed lookup must not count as a successful check", state.usernameCheckSuccess)
        assertFalse(state.usernameExists)
        assertFalse(state.usernameBlocked)
        // the vote is not consulted once the lookup has failed
        verify(exactly = 0) { platformRepo.getVoteContendersOrNull(any()) }
    }

    @Test
    fun checkUsername_ballotLookupFailsAfterEmptyDomainLookup_isNotReportedAsChecked() {
        // The name lookup found no document, but the ballot could not be read: a locked or already
        // won name looks exactly like a free one at this point, so the check must stay unverified.
        nameLookupReturns(null)
        every { platformRepo.getVoteContendersOrNull(username) } returns null

        val state = checkUsername()

        assertFalse("a failed ballot lookup must not count as a successful check", state.usernameCheckSuccess)
        assertFalse(state.usernameExists)
        assertFalse(state.usernameBlocked)
    }

    @Test
    fun checkUsername_noDocumentAndNoVote_isAvailable() {
        nameLookupReturns(null)
        every { platformRepo.getVoteContendersOrNull(username) } returns contenders()

        val state = checkUsername()

        assertTrue(state.usernameCheckSuccess)
        assertFalse(state.usernameExists)
        assertFalse(state.usernameContested)
        assertFalse(state.usernameBlocked)
    }

    @Test
    fun checkUsername_documentExists_isTaken() {
        nameLookupReturns(mockk<Document>())
        every { platformRepo.getVoteContendersOrNull(username) } returns contenders()

        val state = checkUsername()

        assertTrue(state.usernameCheckSuccess)
        assertTrue(state.usernameExists)
        assertFalse(state.usernameBlocked)
    }

    @Test
    fun checkUsername_voteInProgress_isContestedButNotBlocked() {
        nameLookupReturns(null)
        every { platformRepo.getVoteContendersOrNull(username) } returns contenders(
            map = mapOf(otherIdentity to contender(votes = 3)),
            lockVoteTally = 1
        )

        val state = checkUsername()

        assertTrue(state.usernameCheckSuccess)
        assertTrue(state.usernameContested)
        assertFalse(state.usernameExists)
        assertFalse(state.usernameBlocked)
    }

    @Test
    fun checkUsername_voteInProgressWithNoContendersAndLockVotes_isBlocked() {
        nameLookupReturns(null)
        every { platformRepo.getVoteContendersOrNull(username) } returns contenders(lockVoteTally = 2)

        val state = checkUsername()

        assertTrue(state.usernameBlocked)
    }

    @Test
    fun checkUsername_lockedBallotWithEmptyContenderMap_isBlocked() {
        // A finished vote that locked the name: the contender map is empty and there are no lock
        // votes left in the tally, so the in-progress rule alone would call the name available.
        nameLookupReturns(null)
        every { platformRepo.getVoteContendersOrNull(username) } returns contenders(
            winner = finishedVote(isLocked = true)
        )

        val state = checkUsername()

        assertTrue(state.usernameCheckSuccess)
        assertTrue("masternodes locked this name, nobody can register it", state.usernameBlocked)
        assertFalse(state.usernameExists)
    }

    @Test
    fun checkUsername_wonBallotBeforeDomainLookupCatchesUp_isTaken() {
        // Another identity won the vote, but the name lookup does not return its domain document yet
        nameLookupReturns(null)
        every { platformRepo.getVoteContendersOrNull(username) } returns contenders(
            winner = finishedVote(isLocked = false, noWinner = false)
        )

        val state = checkUsername()

        assertTrue(state.usernameCheckSuccess)
        assertTrue("a won name is taken even before its document shows up", state.usernameExists)
        assertFalse(state.usernameBlocked)
    }

    @Test
    fun checkUsername_ballotEndedWithNoWinner_isAvailableAgain() {
        nameLookupReturns(null)
        every { platformRepo.getVoteContendersOrNull(username) } returns contenders(
            winner = finishedVote(isLocked = false, noWinner = true)
        )

        val state = checkUsername()

        assertTrue(state.usernameCheckSuccess)
        assertFalse(state.usernameExists)
        assertFalse(state.usernameBlocked)
        assertFalse(state.usernameContested)
    }

    @Test
    fun checkUsername_staleResultForEarlierInput_doesNotOverwriteTheCurrentName() {
        // A slow check for a free name finishes after the input has moved on to a locked name. The
        // late result must not report the locked name as available.
        val freeName = "freename"
        val lockedName = "lockedname"
        val freeNameInFlight = CountDownLatch(1)
        val releaseFreeName = CountDownLatch(1)
        every { platformRepo.getUsername(freeName) } answers {
            freeNameInFlight.countDown()
            releaseFreeName.await(5, TimeUnit.SECONDS)
            Resource.success(null)
        }
        every { platformRepo.getVoteContendersOrNull(freeName) } returns contenders()
        every { platformRepo.getUsername(lockedName) } returns Resource.success(null)
        every { platformRepo.getVoteContendersOrNull(lockedName) } returns contenders(
            winner = finishedVote(isLocked = true)
        )

        viewModel.checkUsername(freeName)
        assertTrue("the first lookup must be in flight", freeNameInFlight.await(5, TimeUnit.SECONDS))
        // the user keeps typing: the fragment validates the new input, then checks it
        viewModel.checkUsernameValid(lockedName, UsernameType.Primary)
        val lockedState = checkUsername(lockedName)
        assertTrue(lockedState.usernameBlocked)

        releaseFreeName.countDown()
        // give the superseded check every chance to publish
        Thread.sleep(300)

        val state = viewModel.uiState.value
        assertEquals(lockedName, state.checkedUsername)
        assertTrue("the locked name's result must survive the stale one", state.usernameBlocked)
        assertFalse(viewModel.hasAvailableResultFor(lockedName))
        assertFalse(viewModel.hasAvailableResultFor(freeName))
    }

    @Test
    fun checkUsername_inputChangesWhileCheckIsRunning_leavesNoResult() {
        // The input changed but its own check has not started yet (the fragment waits 600ms):
        // the earlier check's result must not be taken for the new input.
        val firstName = "firstname"
        val firstNameInFlight = CountDownLatch(1)
        val releaseFirstName = CountDownLatch(1)
        every { platformRepo.getUsername(firstName) } answers {
            firstNameInFlight.countDown()
            releaseFirstName.await(5, TimeUnit.SECONDS)
            Resource.success(null)
        }
        every { platformRepo.getVoteContendersOrNull(firstName) } returns contenders()

        viewModel.checkUsername(firstName)
        assertTrue("the first lookup must be in flight", firstNameInFlight.await(5, TimeUnit.SECONDS))
        viewModel.checkUsernameValid("secondname", UsernameType.Primary)

        releaseFirstName.countDown()
        Thread.sleep(300)

        val state = viewModel.uiState.value
        assertFalse(state.usernameCheckSuccess)
        assertEquals(null, state.checkedUsername)
        assertFalse(viewModel.hasAvailableResultFor("secondname"))
    }

    @Test
    fun hasAvailableResultFor_onlyMatchesTheCheckedAvailableName() {
        nameLookupReturns(null)
        every { platformRepo.getVoteContendersOrNull(username) } returns contenders()

        checkUsername()

        assertTrue(viewModel.hasAvailableResultFor(username))
        assertFalse(viewModel.hasAvailableResultFor("someothername"))
    }

    // --- USERNAME_REQUESTED recovery ---

    @Test
    fun usernameRequestedAbsent_isNeitherLockedNorLost() = runBlocking {
        // identity creation stuck before the request got a status: the key was never written
        givenRequestedName(creationState = IdentityCreationState.USERNAME_REGISTERING, requested = null)

        assertFalse(viewModel.isUsernameLocked())
        assertFalse(viewModel.isUsernameLostAfterVoting())
    }

    @Test
    fun usernameRequestedMalformed_isNeitherLockedNorLost() = runBlocking {
        givenRequestedName(creationState = IdentityCreationState.VOTING, requested = "NOT_A_STATUS")

        assertFalse(viewModel.isUsernameLocked())
        assertFalse(viewModel.isUsernameLostAfterVoting())
    }

    @Test
    fun usernameRequestedLocked_isLocked() = runBlocking {
        givenRequestedName(creationState = IdentityCreationState.VOTING, requested = UsernameRequestStatus.LOCKED.name)

        assertTrue(viewModel.isUsernameLocked())
        assertFalse(viewModel.isUsernameLostAfterVoting())
    }

    @Test
    fun usernameRequestedLostVote_isLostAfterVoting() = runBlocking {
        givenRequestedName(creationState = IdentityCreationState.VOTING, requested = UsernameRequestStatus.LOST_VOTE.name)

        assertTrue(viewModel.isUsernameLostAfterVoting())
        assertFalse(viewModel.isUsernameLocked())
    }

    // --- submit: reuse of an already registered identity ---

    @Test
    fun submit_usernameStepFailedAfterIdentityRegistered_reusesTheIdentity() {
        viewModel.identity = identity(
            creationState = IdentityCreationState.USERNAME_REGISTERING,
            errorMessage = "domain document rejected"
        )

        assertEquals(
            CreateIdentityService.createIntentForNewUsername(walletApplication, username, null).action,
            submitAndCaptureIntent().action
        )
    }

    @Test
    fun submit_failedBeforeIdentityRegistered_createsANewIdentity() {
        // the identity itself never made it, so there is nothing to reuse
        viewModel.identity = identity(
            creationState = IdentityCreationState.UPGRADING_WALLET,
            errorMessage = "asset lock failed"
        )

        assertEquals(
            CreateIdentityService.createIntent(walletApplication, username, null).action,
            submitAndCaptureIntent().action
        )
    }

    @Test
    fun submit_identityRegisteredWithoutError_createsANewIdentity() {
        viewModel.identity = identity(creationState = IdentityCreationState.IDENTITY_REGISTERED, errorMessage = null)

        assertEquals(
            CreateIdentityService.createIntent(walletApplication, username, null).action,
            submitAndCaptureIntent().action
        )
    }

    @Test
    fun submit_lostVote_reusesTheIdentity() {
        viewModel.identity = identity(
            creationState = IdentityCreationState.VOTING,
            errorMessage = null,
            usernameRequested = UsernameRequestStatus.LOST_VOTE
        )

        assertEquals(
            CreateIdentityService.createIntentForNewUsername(walletApplication, username, null).action,
            submitAndCaptureIntent().action
        )
    }

    // --- helpers ---

    /** Runs a check and waits for it to finish. checkingUsername is raised before the first suspension. */
    private fun checkUsername(name: String = username): RequestUserNameUIState {
        viewModel.checkUsername(name)
        return runBlocking {
            withTimeout(5_000) { viewModel.uiState.first { !it.checkingUsername } }
        }
    }

    private fun nameLookupReturns(document: Document?) {
        every { platformRepo.getUsername(username) } returns Resource.success(document)
    }

    private fun contenders(
        winner: ContestedDocumentVotePollWinnerInfo? = null,
        map: Map<Identifier, ContenderWithSerializedDocument> = mapOf(),
        lockVoteTally: Int = 0
    ): Contenders = Contenders(
        Optional.ofNullable(winner?.let { Pair(it, mockk<BlockInfo>()) }),
        map,
        0,
        lockVoteTally
    )

    private fun contender(votes: Int): ContenderWithSerializedDocument = mockk {
        every { this@mockk.votes } returns votes
        // no document, so the voting period start stays unknown and no deserializing is needed
        every { serializedDocument } returns null
    }

    private fun finishedVote(isLocked: Boolean, noWinner: Boolean = false): ContestedDocumentVotePollWinnerInfo =
        mockk {
            every { this@mockk.isLocked } returns isLocked
            every { this@mockk.noWinner } returns noWinner
        }

    private fun givenRequestedName(creationState: IdentityCreationState, requested: String?) {
        coEvery { identityConfig.get(USERNAME) } returns username
        coEvery { identityConfig.get(CREATION_STATE) } returns creationState.name
        coEvery { identityConfig.get(USERNAME_REQUESTED) } returns requested
    }

    private fun identity(
        creationState: IdentityCreationState,
        errorMessage: String?,
        usernameRequested: UsernameRequestStatus? = null
    ) = BlockchainIdentityData(
        creationState = creationState,
        creationStateErrorMessage = errorMessage,
        username = username,
        usernameSecondary = null,
        userId = otherIdentity.toString(),
        restoring = false,
        usernameRequested = usernameRequested
    )

    private fun submitAndCaptureIntent(): Intent {
        viewModel.requestedUserName = username
        val intent = slot<Intent>()
        every { walletApplication.startService(any()) } returns null

        viewModel.submit()

        // capture while verifying: MockK records the call before a stub's capture() runs, so a slot
        // filled by the stub could still be empty when verify(timeout) returns
        verify(timeout = 5_000) { walletApplication.startService(capture(intent)) }
        return intent.captured
    }
}
